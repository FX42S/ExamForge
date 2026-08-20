package com.ocr.app.service;

import com.ocr.app.dto.PdfAnalysisResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class PdfBookAnalysisService {

    private final ConfigService configService;
    private final DocumentConverterService documentConverterService;
    private final AiService aiService;
    private final PdfLayoutAnalyzerService pdfLayoutAnalyzerService;

    private static final String OUTPUT_DIR = "output";
    private static final String KEY_LAYOUT_ANALYZER_ENABLED = "pdf.layout.analyzer.enabled";

    // 进度回调接口
    public interface ProgressCallback {
        void onProgress(int progress, String step, String message);
    }

    // 带回调的分析方法
    public PdfAnalysisResponse analyzeBook(byte[] fileData, String filename, String outputDir, ProgressCallback callback) {
        return analyzePdfBookInternal(fileData, filename, outputDir, callback);
    }

    // 原有方法（无回调）
    public PdfAnalysisResponse analyzePdfBook(byte[] fileData, String filename) {
        return analyzePdfBookInternal(fileData, filename, null, null);
    }

    private PdfAnalysisResponse analyzePdfBookInternal(byte[] fileData, String filename, String customOutputDir, ProgressCallback callback) {
        long startTime = System.currentTimeMillis();
        Path outputPath = null;

        try {
            log.info("========================================");
            log.info("开始处理 PDF: {}", filename);
            log.info("========================================");

            outputPath = createOutputDirectory(filename, customOutputDir);
            List<String> generatedFiles = Collections.synchronizedList(new ArrayList<>());

            if (callback != null) callback.onProgress(5, "PDF转图片", "正在转换PDF为图片...");

            log.info("\n[步骤1] PDF转图片 -> tempimage目录");
            long stepStart = System.currentTimeMillis();
            List<String> allImageFilePaths = documentConverterService.convertToImagesAndSave(fileData, filename, outputPath);
            int totalPages = allImageFilePaths.size();
            log.info("PDF转图片完成: {} 页，耗时: {}ms", totalPages, System.currentTimeMillis() - stepStart);

            if (callback != null) callback.onProgress(10, "PDF转图片", "转换完成，共 " + totalPages + " 页");

            if (callback != null) callback.onProgress(15, "识别目录", "正在分析目录结构...");

            log.info("\n[步骤2] 识别目录页面，分析章节结构（前30张图片）");
            stepStart = System.currentTimeMillis();
            int previewPages = Math.min(30, totalPages);  // 前30张图片用于识别目录
            List<String> previewImagePaths = allImageFilePaths.subList(0, previewPages);
            TableOfContents toc = analyzeTableOfContents(previewImagePaths, totalPages);
            List<ChapterInfo> chapters = toc.getChapters();
            int contentStartIndex = toc.getContentStartIndex();
            log.info("识别到 {} 个章节，正文从第 {} 页开始，耗时: {}ms", chapters.size(), contentStartIndex + 1, System.currentTimeMillis() - stepStart);

            if (callback != null) callback.onProgress(20, "识别目录", "识别到 " + chapters.size() + " 个章节");

            log.info("\n[步骤3] 清理封面/目录图片，重新排序正文图片");
            stepStart = System.currentTimeMillis();
            List<String> contentImagePaths = cleanupAndRenameImages(outputPath, allImageFilePaths, contentStartIndex, chapters);
            log.info("清理完成，正文图片: {} 张，耗时: {}ms", contentImagePaths.size(), System.currentTimeMillis() - stepStart);

            if (callback != null) callback.onProgress(25, "清理图片", "正文图片已重新排序");

            log.info("\n[步骤4] 保存目录信息到本地MD文件");
            String tocFile = saveTableOfContentsMd(outputPath, filename, chapters, contentStartIndex, totalPages);
            generatedFiles.add(tocFile);

            log.info("\n[步骤5] 逐章处理：生成原文+大纲+题目");
            stepStart = System.currentTimeMillis();

            // 调用统一章节处理方法（包含进度更新和进度.md生成）
            processChaptersWithCallback(outputPath, contentImagePaths, chapters, generatedFiles, contentStartIndex, totalPages, callback);

            log.info("章节处理完成，耗时: {}ms", System.currentTimeMillis() - stepStart);

            if (callback != null) callback.onProgress(95, "生成汇总", "正在生成汇总文件...");

            log.info("\n[步骤6] 生成汇总文件");
            String summaryFile = generateSummaryMd(outputPath, filename, chapters, contentImagePaths.size());
            generatedFiles.add(0, summaryFile);

            long totalTime = System.currentTimeMillis() - startTime;
            log.info("\n========================================");
            log.info("PDF处理完成！");
            log.info("总耗时: {}ms (约 {} 分钟)", totalTime, totalTime / 60000);
            log.info("生成文件数: {}", generatedFiles.size());
            log.info("========================================");

            if (callback != null) callback.onProgress(100, "完成", "处理完成");

            return PdfAnalysisResponse.builder()
                    .success(true)
                    .filename(filename)
                    .outputPath(outputPath.toString())
                    .chapters(convertToResponseChapters(chapters))
                    .generatedFiles(generatedFiles)
                    .build();

        } catch (Exception e) {
            log.error("PDF处理失败", e);
            if (callback != null) callback.onProgress(0, "失败", "处理失败: " + e.getMessage());
            return PdfAnalysisResponse.builder()
                    .success(false)
                    .error("处理失败: " + e.getMessage())
                    .filename(filename)
                    .build();
        }
    }

    private TableOfContents analyzeTableOfContents(List<String> imagePaths, int totalPages) {
        List<String> base64Images = new ArrayList<>();
        for (String path : imagePaths) {
            try {
                base64Images.add(loadImageAsBase64(path));
            } catch (IOException e) {
                log.error("读取图片失败: {}", path);
            }
        }

        String prompt = "请仔细分析以下" + imagePaths.size() + "页图片，完成以下任务：\n\n" +
            "[任务1：识别每页内容类型]\n" +
            "逐页判断每张图片是什么内容（封面/版权页/目录/前言/正文等），返回格式：\n" +
            "页码|内容类型\n" +
            "示例：0|封面\n" +
            "示例：1|版权页\n" +
            "示例：2|目录\n" +
            "示例：5|正文\n\n" +
            "[任务2：提取完整章节结构]\n" +
            "从目录页中提取所有正式章节，务必包含所有章节，不要遗漏！\n" +
            "返回格式：章节编号|章节标题|起始页码\n\n" +
            "【章节编号识别规则】\n" +
            "请将以下各种格式统一转换为\"第X章\"格式：\n" +
            "- \"第1章\"、\"第一章\"、\"第壹章\" → 转换为\"第1章\"\n" +
            "- \"1. 第一章\"、\"1 第一章\"、\"1.第一章\" → 转换为\"第1章\"\n" +
            "- \"Module 1\"、\"MODULE 1\"、\"Unit 1\" → 转换为\"第1章\"\n" +
            "- \"项目一\"、\"项目1\"、\"专题一\" → 转换为\"第1章\"\n" +
            "- \"教学单元一\"、\"教学单元1\"、\"单元一\"、\"单元1\" → 转换为\"第1章\"\n" +
            "- \"一、\"、\"1.\"、\"1、\" 开头的大标题 → 转换为\"第1章\"\n" +
            "- \"第1节\"、\"1.1\" 这类小节不作为章节处理\n\n" +
            "【章节标题提取规则】\n" +
            "- 如果目录格式是\"第1章 对象导论\"，直接提取\"对象导论\"\n" +
            "- 如果目录格式是\"1. 对象导论\"，提取\"对象导论\"\n" +
            "- 如果目录格式是\"对象导论\"（没有编号），请根据上下文推断章节编号\n\n" +
            "返回示例：\n" +
            "1|对象导论|15\n" +
            "2|变量与数据类型|35\n" +
            "3|运算符与表达式|56\n" +
            "4|流程控制|78\n\n" +
            "[重要要求]\n" +
            "1. 必须提取目录中列出的所有一级章节条目（包括前言、绪论、出版说明、导论、概论等），不要遗漏\n" +
            "2. 章节页码必须是PDF中的实际页码（从1开始），只返回一个数字即可\n" +
            "3. 只输出规定格式（章节编号|章节标题|起始页码），不要输出页码范围\n" +
            "4. 只输出规定格式，不要有任何额外说明\n" +
            "5. 保留前言、序言、出版说明、绪论、导论、概论、内容提要、学习导入等前言性章节作为索引\n" +
            "6. 如果目录页包含多级标题，只提取一级大章节（章/项目/模块/单元）\n" +
            "7. 如果某章节没有明确页码，请根据前后章节的页码推断，但只写起始页码\n" +
            "8. 章节编号必须是连续的整数，从1开始（如1,2,3...，不要跳过编号）\n" +
            "9. 【任务1】中必须准确标记正文开始的那一页，格式为：页码|正文";

        String response = aiService.callAiWithImages(base64Images, prompt);

        // 保存AI原始响应到日志
        log.info("AI目录识别原始响应:\n{}", response);

        return parseTableOfContents(response, totalPages);
    }

    private TableOfContents parseTableOfContents(String response, int totalPages) {
        List<ChapterInfo> chapters = new ArrayList<>();
        int contentStartIndex = 0;

        if (response == null || response.trim().isEmpty()) {
            log.warn("目录分析返回为空，创建默认章节");
            chapters.add(new ChapterInfo(1, "第一章", 1, totalPages, "[...] 待处理"));
            return new TableOfContents(chapters, 0);
        }

        String[] lines = response.split("\n");

        // 第一步：找到正文开始的位置
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\|", 3);
            if (parts.length >= 2) {
                String first = parts[0].trim();
                String second = parts[1].trim().toLowerCase();

                // 页码索引格式：数字|内容类型
                if (first.matches("\\d+") && (second.contains("正文") || second.contains("内容"))) {
                    try {
                        contentStartIndex = Integer.parseInt(first);
                        log.info("检测到正文从第 {} 页开始", contentStartIndex + 1);
                        break;
                    } catch (NumberFormatException e) {
                        log.debug("解析页码失败: {}", line);
                    }
                }
            }
        }

        // 第二步：提取章节信息（支持多种格式）
        int lastStartPage = contentStartIndex + 1;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) continue;

            String[] parts = line.split("\\|", 3);
            if (parts.length < 2) continue;

            String first = parts[0].trim();
            String second = parts[1].trim();

            // 跳过页码索引行（格式：数字|封面/目录/正文）
            if (first.matches("\\d+") &&
                (second.toLowerCase().contains("封面") ||
                 second.toLowerCase().contains("版权") ||
                 second.toLowerCase().contains("目录") ||
                 second.toLowerCase().contains("前言") ||
                 second.toLowerCase().contains("序言") ||
                 second.toLowerCase().contains("正文"))) {
                continue;
            }

            // 尝试解析章节行（格式：章节编号|章节标题|起始页码-结束页码）
            try {
                int chapterNum;
                String title;
                int startPage;
                int endPage = 0;

                // 格式1: 章节编号|章节标题|页码范围
                if (first.matches("\\d+")) {
                    chapterNum = Integer.parseInt(first);
                    title = second;
                    if (parts.length >= 3) {
                        String pageRange = parts[2].trim();
                        // 尝试解析 "15-34" 或 "15" 格式
                        if (pageRange.contains("-")) {
                            String[] rangeParts = pageRange.split("-", 2);
                            startPage = parsePageNumber(rangeParts[0]);
                            endPage = parsePageNumber(rangeParts[1]);
                        } else {
                            startPage = parsePageNumber(pageRange);
                        }
                    } else {
                        startPage = lastStartPage;
                    }
                }
                // 格式2: 第X章|章节标题|页码范围
                else if (first.matches("第[一二三四五六七八九十\\d]+章")) {
                    chapterNum = extractChapterNumber(first);
                    title = first + " " + second;
                    if (parts.length >= 3) {
                        String pageRange = parts[2].trim();
                        if (pageRange.contains("-")) {
                            String[] rangeParts = pageRange.split("-", 2);
                            startPage = parsePageNumber(rangeParts[0]);
                            endPage = parsePageNumber(rangeParts[1]);
                        } else {
                            startPage = parsePageNumber(pageRange);
                        }
                    } else {
                        startPage = lastStartPage;
                    }
                }
                // 格式2b: 项目X|章节标题|页码范围
                else if (first.matches("项目[一二三四五六七八九十\\d]+")) {
                    chapterNum = extractProjectNumber(first);
                    title = first + " " + second;
                    if (parts.length >= 3) {
                        String pageRange = parts[2].trim();
                        if (pageRange.contains("-")) {
                            String[] rangeParts = pageRange.split("-", 2);
                            startPage = parsePageNumber(rangeParts[0]);
                            endPage = parsePageNumber(rangeParts[1]);
                        } else {
                            startPage = parsePageNumber(pageRange);
                        }
                    } else {
                        startPage = lastStartPage;
                    }
                }
                // 格式3: 其他格式，跳过
                else {
                    continue;
                }

                if (chapterNum > 0 && !title.isEmpty()) {
                    ChapterInfo chapter = new ChapterInfo(chapterNum, title, startPage, 0, "[...] 待处理");
                    if (endPage > 0) {
                        chapter.setEndPage(endPage);
                    }
                    chapters.add(chapter);
                    lastStartPage = startPage;
                    log.debug("解析到章节: {} - {} (页码: {}-{})", chapterNum, title, startPage,
                        endPage > 0 ? endPage : "待计算");
                }
            } catch (Exception e) {
                log.debug("无法解析章节行: {}", line);
            }
        }

        // 如果还是没解析到章节，尝试备用解析策略
        if (chapters.isEmpty()) {
            log.warn("标准解析未找到章节，尝试备用解析策略");
            chapters = parseChaptersBackup(lines, totalPages);
        }

        // 目录保留完整，包括绪论/概述/出版说明等前言性章节
        // 这些章节在后续生成原文/题目时会跳过，但目录索引里保留

        // 去除重复章节编号：同一编号保留起始页最大的（更可能是正式内容）
        chapters = deduplicateChapters(chapters);

        if (chapters.isEmpty()) {
            log.warn("未能识别到章节，创建默认章节");
            chapters.add(new ChapterInfo(1, "第一章", contentStartIndex + 1, totalPages, "[...] 待处理"));
        }

        // 按章节编号排序
        chapters.sort(Comparator.comparingInt(ChapterInfo::getChapterNumber));

        // 计算每个章节的结束页码（如果AI没有提供）
        for (int i = 0; i < chapters.size(); i++) {
            ChapterInfo chapter = chapters.get(i);
            if (chapter.getEndPage() <= 0) {
                int endPage = (i < chapters.size() - 1) ?
                        chapters.get(i + 1).getStartPage() - 1 : totalPages;
                chapter.setEndPage(endPage);
            }
        }

        // 正文起始页保留 AI 识别的结果，不根据章节起始页校正
        // 原因：目录分析只给前30页，有些正文第一章实际在40页以后
        // 若按最早章节起始页清图，可能误删真正的正文页面
        return new TableOfContents(chapters, contentStartIndex);
    }

    /**
     * 去除重复章节编号，保留起始页最大的章节
     */
    private List<ChapterInfo> deduplicateChapters(List<ChapterInfo> chapters) {
        Map<Integer, ChapterInfo> map = new LinkedHashMap<>();
        for (ChapterInfo chapter : chapters) {
            int num = chapter.getChapterNumber();
            ChapterInfo existing = map.get(num);
            if (existing == null || chapter.getStartPage() > existing.getStartPage()) {
                map.put(num, chapter);
            }
        }
        if (map.size() < chapters.size()) {
            log.info("去除重复章节编号: 原始 {} 个 → {} 个", chapters.size(), map.size());
        }
        return new ArrayList<>(map.values());
    }
    
    private List<ChapterInfo> parseChaptersBackup(String[] lines, int totalPages) {
        List<ChapterInfo> chapters = new ArrayList<>();
        int lastStartPage = 1;
        int chapterNum = 0;
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            
            // 跳过页码索引行（数字|封面/目录/正文 等）
            if (line.matches("\\d+\\|(封面|版权|目录|前言|序言|正文|空白)")) {
                continue;
            }
            
            // 尝试从行中提取章节信息
            try {
                String cleanLine = line.replaceAll("^[\\-\\*\\.\\s]+", ""); // 去掉开头的符号
                
                // 匹配各种章节格式
                Pattern[] patterns = {
                    // 第1章 对象导论 15
                    Pattern.compile("第([一二三四五六七八九十\\d]+)章\\s+(.+?)\\s+(\\d+)"),
                    // 1. 对象导论 15 或 1、对象导论 15
                    Pattern.compile("^(\\d+)[\\.、]\\s+(.+?)\\s+(\\d+)"),
                    // Module 1: 对象导论 15 或 Module 1 对象导论 15
                    Pattern.compile("(?i)Module\\s+(\\d+)[:\\s]+(.+?)\\s+(\\d+)"),
                    // Unit 1 对象导论 15
                    Pattern.compile("(?i)Unit\\s+(\\d+)[:\\s]+(.+?)\\s+(\\d+)"),
                    // 项目一 对象导论 15
                    Pattern.compile("项目([一二三四五六七八九十\\d]+)\\s+(.+?)\\s+(\\d+)"),
                    // 专题一 对象导论 15
                    Pattern.compile("专题([一二三四五六七八九十\\d]+)\\s+(.+?)\\s+(\\d+)"),
                    // 教学单元一 对象导论 15 或 教学单元1 对象导论 15
                    Pattern.compile("教学单元([一二三四五六七八九十\\d]+)\\s+(.+?)\\s+(\\d+)"),
                    // 单元一 对象导论 15 或 单元1 对象导论 15
                    Pattern.compile("(?<!教学)单元([一二三四五六七八九十\\d]+)\\s+(.+?)\\s+(\\d+)"),
                    // 单纯的对象导论 15（没有编号，根据上下文推断）
                    Pattern.compile("^(.+?)\\s+(\\d+)$"),
                };
                
                Matcher matcher;
                for (Pattern pattern : patterns) {
                    matcher = pattern.matcher(cleanLine);
                    if (matcher.find()) {
                        String numStr = matcher.group(1);
                        String title = matcher.group(2).trim();
                        int startPage = Integer.parseInt(matcher.group(3));
                        
                        // 转换中文数字
                        if (numStr.matches("[一二三四五六七八九十]+")) {
                            chapterNum++;
                        } else {
                            chapterNum = Integer.parseInt(numStr);
                        }
                        
                        // 清理标题
                        title = title.replaceAll("^[第\\d]", "").trim();
                        if (title.length() > 50) {
                            title = title.substring(0, 50);
                        }
                        
                        if (startPage > 0) {
                            chapters.add(new ChapterInfo(chapterNum, title, startPage, 0, "[...] 待处理"));
                            lastStartPage = startPage;
                            log.debug("备用解析找到章节: {} - {} (页码: {})", chapterNum, title, startPage);
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("备用解析跳过行: {}", line);
            }
        }
        
        return chapters;
    }

    // 非正文章节关键词（不区分大小写）
    // 注意："项目"不是排除关键词，因为有些书籍的章节就是"项目X"格式
    private static final List<String> NON_CHAPTER_KEYWORDS = Arrays.asList(
        "版权", "扉页", "前言", "序言", "序章", "编写说明", "出版说明",
        "绪论", "导论", "概论", "内容提要", "学习导入", "导入",
        "实验", "实训", "实践", "习题", "练习", "案例",
        "任务", "模块", "附录", "参考文献",
        "后记", "致谢", "编委会", "编写组", "空白页", "空白",
        "目录", "索引", "封底",
        "题型举例", "程序分析题", "程序设计题", "编程题",
        "实验指导", "上机指导", "课程设计"
    );

    private static final List<String> EARLY_OVERVIEW_KEYWORDS = Arrays.asList(
        "概述", "体系结构", "层次体系", "层次结构", "简介", "导引"
    );

    /**
     * 判断一个章节是否为非正文章节（前言、绪论、出版说明、习题等）
     * 目录识别阶段保留这些章节作为索引，但生成原文/题目时跳过
     */
    private boolean isNonChapterEntry(ChapterInfo chapter, int totalPages) {
        String title = chapter.getTitle().toLowerCase();

        // 检查是否包含排除关键词
        for (String keyword : NON_CHAPTER_KEYWORDS) {
            if (title.contains(keyword.toLowerCase())) {
                log.info("非正文章节: {} (包含关键词: {})", chapter.getTitle(), keyword);
                return true;
            }
        }

        // 检查章节编号是否合理（通常是1-99之间）
        int chapterNum = chapter.getChapterNumber();
        if (chapterNum <= 0 || chapterNum > 99) {
            log.info("非正文章节（异常编号）: {} - {}", chapterNum, chapter.getTitle());
            return true;
        }

        // 启发式判断：前 20 页内出现的“概述/体系结构/层次体系/简介”通常是前言性导论
        int startPage = chapter.getStartPage();
        if (startPage > 0 && startPage <= 20) {
            for (String keyword : EARLY_OVERVIEW_KEYWORDS) {
                if (title.contains(keyword.toLowerCase())) {
                    log.info("非正文章节（早期前言）: {} (包含关键词: {})", chapter.getTitle(), keyword);
                    return true;
                }
            }
        }

        // 起始页必须在有效范围内
        if (startPage <= 0 || startPage > totalPages) {
            log.info("非正文章节（无效起始页）: {} - {} (起始页:{} 超出范围 1-{})",
                chapterNum, chapter.getTitle(), startPage, totalPages);
            return true;
        }

        // 如果AI提供了结束页，验证结束页是否有效
        int endPage = chapter.getEndPage();
        if (endPage > 0) {
            if (endPage < startPage) {
                log.info("非正文章节（无效范围）: {} - {} ({}-{} 结束页小于起始页)",
                    chapterNum, chapter.getTitle(), startPage, endPage);
                return true;
            }
            if (endPage > totalPages + 5) {
                log.info("非正文章节（无效结束页）: {} - {} (结束页:{} 超出总页数:{})",
                    chapterNum, chapter.getTitle(), endPage, totalPages);
                return true;
            }
        }

        return false;
    }
    
    private int parsePageNumber(String text) {
        if (text == null || text.isEmpty()) return 0;
        // 提取文本中的第一个数字
        String numbers = text.replaceAll("[^0-9]", "");
        if (numbers.isEmpty()) return 0;
        try {
            return Integer.parseInt(numbers);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    
    private int extractChapterNumber(String text) {
        if (text.startsWith("第") && text.contains("章")) {
            String numStr = text.substring(1, text.indexOf("章"));
            return chineseNumberToInt(numStr);
        }
        return 0;
    }

    private int extractProjectNumber(String text) {
        if (text.startsWith("项目")) {
            String numStr = text.substring(2);
            return chineseNumberToInt(numStr);
        }
        return 0;
    }

    private int extractChapterNumberFromLine(String line) {
        // 匹配 "第X章" 格式
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("第([一二三四五六七八九十\\d]+)章");
        java.util.regex.Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return chineseNumberToInt(matcher.group(1));
        }

        // 匹配 "项目X" 格式
        pattern = java.util.regex.Pattern.compile("项目([一二三四五六七八九十\\d]+)");
        matcher = pattern.matcher(line);
        if (matcher.find()) {
            return chineseNumberToInt(matcher.group(1));
        }

        // 匹配 "Chapter X" 格式
        pattern = java.util.regex.Pattern.compile("Chapter\\s+(\\d+)", Pattern.CASE_INSENSITIVE);
        matcher = pattern.matcher(line);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }

        return 0;
    }
    
    private String extractChapterTitle(String line) {
        // 移除页码信息
        line = line.replaceAll("\\|\\s*\\d+\\s*$", "").trim();
        return line;
    }
    
    private int extractPageNumberFromLine(String line) {
        // 尝试从行尾提取数字作为页码
        String[] parts = line.split("\\|");
        if (parts.length >= 3) {
            return parsePageNumber(parts[parts.length - 1]);
        }
        return 0;
    }
    
    private int chineseNumberToInt(String chinese) {
        if (chinese.matches("\\d+")) {
            return Integer.parseInt(chinese);
        }
        
        int result = 0;
        int temp = 0;
        for (char c : chinese.toCharArray()) {
            switch (c) {
                case '一': temp = 1; break;
                case '二': temp = 2; break;
                case '三': temp = 3; break;
                case '四': temp = 4; break;
                case '五': temp = 5; break;
                case '六': temp = 6; break;
                case '七': temp = 7; break;
                case '八': temp = 8; break;
                case '九': temp = 9; break;
                case '十': 
                    if (temp == 0) temp = 1;
                    result += temp * 10;
                    temp = 0;
                    break;
                default: break;
            }
        }
        return result + temp;
    }

    private List<String> cleanupAndRenameImages(Path outputPath, List<String> allImagePaths, int contentStartIndex, List<ChapterInfo> chapters) throws IOException {
        Path tempImageDir = outputPath.resolve("tempimage");
        Path contentImageDir = outputPath.resolve("content");
        Files.createDirectories(contentImageDir);

        List<String> contentImagePaths = new ArrayList<>();

        for (int i = contentStartIndex; i < allImagePaths.size(); i++) {
            String oldPath = allImagePaths.get(i);
            // 保留原 PDF 页码作为 content 目录文件名，避免章节页码和实际图片对不上
            int originalPageNum = i + 1;
            String newName = String.format("page_%04d.png", originalPageNum);
            Path newPath = contentImageDir.resolve(newName);

            Files.copy(Paths.get(oldPath), newPath);
            contentImagePaths.add(newPath.toString());
        }

        for (String path : allImagePaths) {
            try {
                Files.deleteIfExists(Paths.get(path));
            } catch (IOException e) {
                log.warn("删除旧图片失败: {}", path);
            }
        }
        Files.deleteIfExists(tempImageDir);

        // content 文件名已保留原 PDF 页码，adjusted 页码直接等于原始页码
        for (ChapterInfo chapter : chapters) {
            chapter.setAdjustedPageRange(chapter.getStartPage(), chapter.getEndPage());
        }

        return contentImagePaths;
    }

    private String saveTableOfContentsMd(Path outputPath, String filename, List<ChapterInfo> chapters, int contentStartIndex, int totalPages) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("---\n");
        md.append("title: ").append(filename).append(" 目录\n");
        md.append("date: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        md.append("tags: [目录]\n");
        md.append("---\n\n");
        md.append("# ").append(filename).append(" 目录信息\n\n");
        md.append("- **总页数**：").append(totalPages).append("\n");
        md.append("- **正文起始页**：第 ").append(contentStartIndex + 1).append(" 页\n");
        md.append("- **章节数**：").append(chapters.size()).append("\n\n");

        md.append("## 章节列表\n\n");
        md.append("| 章节编号 | 章节标题 | PDF原始页码 | 正文相对页码 |\n");
        md.append("|---------|---------|-----------|-----------|\n");

        for (ChapterInfo chapter : chapters) {
            md.append("| ").append(chapter.getChapterNumber());
            md.append(" | ").append(chapter.getTitle());
            md.append(" | ").append(chapter.getStartPage()).append("-").append(chapter.getEndPage());
            md.append(" | ").append(chapter.getAdjustedStartPage()).append("-").append(chapter.getAdjustedEndPage()).append(" |\n");
        }

        return saveFile(outputPath, "00_目录.md", md.toString());
    }

    // 处理所有章节（带进度回调和进度.md更新）
    private void processChaptersWithCallback(Path outputPath,
                                            List<String> contentImagePaths,
                                            List<ChapterInfo> chapters,
                                            List<String> generatedFiles,
                                            int contentStartIndex,
                                            int totalPages,
                                            ProgressCallback callback) {

        int chapterCount = chapters.size();
        int progressPerChapter = 70 / Math.max(chapterCount, 1);
        int currentProgress = 30;

        // 初始化进度.md（开始处理前先生成）
        updateProgress(outputPath, chapters, 0, chapterCount);

        for (int i = 0; i < chapters.size(); i++) {
            ChapterInfo chapter = chapters.get(i);
            long chapterStartTime = System.currentTimeMillis();

            // 非正文章节（绪论/出版说明/导论/概述/前言等）保留在目录中，但不生成原文和题目
            if (isNonChapterEntry(chapter, totalPages)) {
                log.info("章节 [{}] 为非正文章节，保留目录索引但跳过原文/题目生成", chapter.getTitle());
                chapter.setStatus("[-] 跳过");
                updateProgress(outputPath, chapters, i + 1, chapterCount);
                currentProgress += progressPerChapter;
                continue;
            }

            if (callback != null) {
                String displayLabel = chapter.getTitle().startsWith("项目") ? "项目" + chapter.getChapterNumber() : "第" + chapter.getChapterNumber() + "章";
                callback.onProgress(currentProgress, "处理章节", "正在处理" + displayLabel + ": " + chapter.getTitle());
            }

            log.info("\n--- 处理章节 {}/{}: {} (正文页码 {}-{}) ---",
                    i + 1, chapters.size(), chapter.getTitle(), chapter.getAdjustedStartPage(), chapter.getAdjustedEndPage());

            try {
                List<String> chapterImagePaths = getChapterImagePaths(contentImagePaths, chapter);

                if (chapterImagePaths.isEmpty()) {
                    log.warn("章节 [{}] 没有对应图片，跳过", chapter.getTitle());
                    chapter.setStatus("[X] 无图片");
                    updateProgress(outputPath, chapters, i, chapterCount);
                    continue;
                }

                ChapterResult result = generateChapterContent(chapterImagePaths, chapter, outputPath);

                // 保存原文
                String textFile = saveChapterText(outputPath, chapter, result.text, chapterImagePaths, result.layoutAnalyzerUsed);
                generatedFiles.add(textFile);

                // 根据原文生成大纲（分块传输）
                log.info("根据原文生成大纲...");
                String outline = generateOutlineFromText(result.text, chapter);
                String outlineFile = saveFile(outputPath, chapter.getOutlineFileName(), outline);
                generatedFiles.add(outlineFile);

                // 题目生成在第三阶段（考试大纲任务）处理

                chapter.setStatus("[V] 完成");

                // 更新进度.md
                updateProgress(outputPath, chapters, i + 1, chapterCount);

                long chapterTime = System.currentTimeMillis() - chapterStartTime;
                log.info("--- 章节 {}/{} 处理完成，耗时: {}ms ---", i + 1, chapters.size(), chapterTime);

            } catch (Exception e) {
                log.error("处理章节 [{}] 失败: {}", chapter.getTitle(), e.getMessage(), e);
                chapter.setStatus("[X] 失败");
                updateProgress(outputPath, chapters, i + 1, chapterCount);
            }

            currentProgress += progressPerChapter;
        }

        log.info("所有章节处理完成！");
    }

    // 处理单个章节的方法（无进度回调）
    private void processChapter(Path outputPath,
                                List<String> contentImagePaths,
                                ChapterInfo chapter,
                                List<String> generatedFiles,
                                int contentStartIndex) {
        long chapterStartTime = System.currentTimeMillis();

        log.info("\n--- 处理章节: {} (正文页码 {}-{}) ---",
                chapter.getTitle(), chapter.getAdjustedStartPage(), chapter.getAdjustedEndPage());

        try {
            List<String> chapterImagePaths = getChapterImagePaths(contentImagePaths, chapter);

            if (chapterImagePaths.isEmpty()) {
                log.warn("章节 [{}] 没有对应图片，跳过", chapter.getTitle());
                chapter.setStatus("[X] 无图片");
                return;
            }

            ChapterResult result = generateChapterContent(chapterImagePaths, chapter, outputPath);

            // 保存大纲
            String outlineFile = saveFile(outputPath, chapter.getOutlineFileName(), result.outline);
            generatedFiles.add(outlineFile);

            // 保存原文
            String textFile = saveChapterText(outputPath, chapter, result.text, chapterImagePaths, result.layoutAnalyzerUsed);
            generatedFiles.add(textFile);

            chapter.setKnowledgePointCount(countKnowledgePoints(result.outline));
            chapter.setStatus("[V] 完成");

            long chapterTime = System.currentTimeMillis() - chapterStartTime;
            log.info("--- 章节 {} 处理完成，耗时: {}ms ---", chapter.getTitle(), chapterTime);

        } catch (Exception e) {
            log.error("处理章节 [{}] 失败: {}", chapter.getTitle(), e.getMessage(), e);
            chapter.setStatus("[X] 失败");
        }
    }

    private void processChapters(Path outputPath,
                                List<String> contentImagePaths,
                                List<ChapterInfo> chapters,
                                List<String> generatedFiles,
                                int contentStartIndex) {

        log.info("开始逐章处理 {} 个章节...", chapters.size());

        for (int i = 0; i < chapters.size(); i++) {
            ChapterInfo chapter = chapters.get(i);
            long chapterStartTime = System.currentTimeMillis();

            log.info("\n--- 处理章节 {}/{}: {} (正文页码 {}-{}) ---",
                    i + 1, chapters.size(), chapter.getTitle(), chapter.getAdjustedStartPage(), chapter.getAdjustedEndPage());

            try {
                List<String> chapterImagePaths = getChapterImagePaths(contentImagePaths, chapter);

                if (chapterImagePaths.isEmpty()) {
                    log.warn("章节 [{}] 没有对应图片，跳过", chapter.getTitle());
                    chapter.setStatus("[X] 无图片");
                    updateProgress(outputPath, chapters, i, chapters.size());
                    continue;
                }

                ChapterResult result = generateChapterContent(chapterImagePaths, chapter, outputPath);

                // 只保存原文（第一阶段：只要原文，不要大纲）
                String textFile = saveChapterText(outputPath, chapter, result.text, chapterImagePaths, result.layoutAnalyzerUsed);
                generatedFiles.add(textFile);

                // 注意：题目生成在第三阶段（考试大纲任务）处理

                chapter.setStatus("[V] 完成");

                updateProgress(outputPath, chapters, i + 1, chapters.size());

                long chapterTime = System.currentTimeMillis() - chapterStartTime;
                log.info("--- 章节 {}/{} 处理完成，耗时: {}ms ---", i + 1, chapters.size(), chapterTime);

            } catch (Exception e) {
                log.error("处理章节 [{}] 失败: {}", chapter.getTitle(), e.getMessage(), e);
                chapter.setStatus("[X] 失败");
                updateProgress(outputPath, chapters, i + 1, chapters.size());
            }
        }

        log.info("所有章节处理完成！");
    }

    // 返回结果：包含原文和生成的原文文件路径
    private static class ChapterResult {
        String text;
        String textFileName;
        String outline;
        String outlineFileName;
        boolean layoutAnalyzerUsed;
        ChapterResult(String text, String outline) {
            this(text, outline, false);
        }
        ChapterResult(String text, String outline, boolean layoutAnalyzerUsed) {
            this.text = text;
            this.outline = outline;
            this.layoutAnalyzerUsed = layoutAnalyzerUsed;
        }
    }

    private ChapterResult generateChapterContent(List<String> imagePaths, ChapterInfo chapter, Path outputPath) {
        // 如果启用版面分析，调用 Python 脚本：提取文字 + 插图按原位置插入 Markdown
        boolean layoutEnabled = isLayoutAnalyzerEnabled();
        log.info("[book-process] 章节 [{}] 版面分析开关状态: {}", chapter.getTitle(), layoutEnabled);

        if (layoutEnabled) {
            try {
                log.info("[book-process] 章节 [{}] 启用版面分析，调用 Python 脚本...", chapter.getTitle());
                String layoutMarkdown = pdfLayoutAnalyzerService.analyzeChapterImages(
                        imagePaths, outputPath, chapter.getTitle());

                if (layoutMarkdown != null && !layoutMarkdown.trim().isEmpty()) {
                    log.info("[book-process] 章节 [{}] 版面分析成功，输出长度 {}", chapter.getTitle(), layoutMarkdown.length());
                    return new ChapterResult(layoutMarkdown, "", true);
                }
                log.warn("[book-process] 章节 [{}] 版面分析未返回结果（超时或空输出），降级为纯 OCR 文字提取", chapter.getTitle());
                // 继续执行下方的纯 OCR 分支
            } catch (Exception e) {
                log.error("[book-process] 章节 [{}] 版面分析失败: {}，降级为纯 OCR 文字提取", chapter.getTitle(), e.getMessage(), e);
                // 继续执行下方的纯 OCR 分支
            }
        }

        // 分批加载图片
        List<String> base64Images = new ArrayList<>();
        for (String path : imagePaths) {
            try {
                base64Images.add(loadImageAsBase64(path));
            } catch (IOException e) {
                log.error("读取图片失败: {}", path);
            }
        }

        int totalImages = base64Images.size();
        int batchSize = 10; // 每批10张图片
        StringBuilder combinedText = new StringBuilder();

        // 分批处理图片，提取原文
        for (int i = 0; i < totalImages; i += batchSize) {
            int end = Math.min(i + batchSize, totalImages);
            List<String> batch = base64Images.subList(i, end);

            String batchPrompt = "请仔细阅读以下图片，将图片中的所有文字内容完整提取出来，保持原有结构和格式。\n\n" +
                "章节标题：" + chapter.getTitle() + "\n" +
                "本页范围：第" + (chapter.getAdjustedStartPage() + i) + "页 - 第" + (chapter.getAdjustedStartPage() + end - 1) + "页\n" +
                "(共 " + totalImages + " 页中的第 " + (i + 1) + "-" + end + " 页)\n\n" +
                "[重要要求]\n" +
                "1. 完整提取所有文字，不要遗漏\n" +
                "2. 保持原有段落结构和格式\n" +
                "3. 不要添加任何解释或总结\n\n" +
                "[输出格式]\n" +
                "直接输出提取的文字内容即可";

            log.info("提取原文：第{}/{}批，传 {} 张图片给AI...", (i / batchSize) + 1, (totalImages + batchSize - 1) / batchSize, batch.size());
            String batchResult = aiService.callAiWithImages(batch, batchPrompt);

            if (batchResult != null && !batchResult.trim().isEmpty() && !batchResult.contains("AI处理结果为空")) {
                combinedText.append(batchResult).append("\n\n");
            }
        }

        String textResult = combinedText.toString().trim();
        if (textResult.isEmpty()) {
            textResult = "[原文提取失败]AI返回为空";
        }

        return new ChapterResult(textResult, "", false);
    }

    private boolean isLayoutAnalyzerEnabled() {
        try {
            String value = configService.getConfig(KEY_LAYOUT_ANALYZER_ENABLED);
            if (value == null || value.trim().isEmpty()) {
                return true; // 默认开启
            }
            return Boolean.parseBoolean(value);
        } catch (Exception e) {
            log.debug("读取版面分析开关失败，默认开启: {}", e.getMessage());
            return true;
        }
    }

    // 根据原文生成大纲（分块传输）
    private String generateOutlineFromText(String chapterText, ChapterInfo chapter) {
        int chunkSize = 4000; // 每块4000字符
        int totalLength = chapterText.length();

        if (totalLength <= chunkSize) {
            // 原文较短，直接生成大纲
            return callAiForOutline(chapterText, chapter);
        }

        // 分块生成大纲
        StringBuilder combinedOutline = new StringBuilder();
        int chunkCount = (totalLength + chunkSize - 1) / chunkSize;

        log.info("章节原文较长({}字符)，分为{}块生成大纲", totalLength, chunkCount);

        for (int i = 0; i < chunkCount; i++) {
            int start = i * chunkSize;
            int end = Math.min(start + chunkSize, totalLength);
            String chunk = chapterText.substring(start, end);

            log.info("生成大纲：第{}/{}块 ({}字符)", i + 1, chunkCount, chunk.length());
            String chunkOutline = callAiForOutline(chunk, chapter);

            if (chunkOutline != null && !chunkOutline.isEmpty()) {
                combinedOutline.append(chunkOutline).append("\n\n");
            }
        }

        String result = combinedOutline.toString().trim();
        return result.isEmpty() ? "[大纲生成失败]" : result;
    }

    // 调用AI生成大纲
    private String callAiForOutline(String textChunk, ChapterInfo chapter) {
        String prompt = "请仔细阅读以下教材原文，生成该章节的知识大纲。\n\n" +
            "章节标题：" + chapter.getTitle() + "\n\n" +
            "【教材原文】\n" + textChunk + "\n\n" +
            "[大纲要求]\n" +
            "1. 基于原文内容，提取核心知识点\n" +
            "2. 按层次组织（一级、二级知识点）\n" +
            "3. 每条知识点要简洁、准确\n" +
            "4. 知识点要能在原文中找到依据\n\n" +
            "[输出格式]\n" +
            "一、核心概念\n" +
            "1. 概念1：定义\n" +
            "2. 概念2：定义\n\n" +
            "二、重要知识点\n" +
            "1. 知识点1：说明";

        return aiService.callAiTextOnly(prompt);
    }

    private String[] splitOutlineResult(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new String[]{"[原文提取失败]", ""};
        }
        String[] parts = text.split("\\[知识大纲\\]|\\[大纲\\]|知识大纲", 2);
        if (parts.length >= 2) {
            String rawText = parts[0].replaceAll("(?i)^.*?\\[原文\\]\\s*", "").trim();
            String outline = parts[1].trim();
            return new String[]{rawText, outline};
        }
        return new String[]{text, "[大纲提取失败]"};
    }

    private int countKnowledgePoints(String outline) {
        if (outline == null) return 0;
        int count = 0;
        for (String line : outline.split("\n")) {
            if (line.matches("^\\s*[一二三四五六七八九十\\d]+[、.．].*") ||
                    line.matches("^\\s*\\d+\\..*")) {
                count++;
            }
        }
        return Math.max(count, 1);
    }

    private int calculateQuestionCount(String outlineResult) {
        if (outlineResult == null || outlineResult.isEmpty() || outlineResult.contains("[大纲生成失败]")) {
            return 10;
        }

        int outlineLength = outlineResult.length();
        int questionCount;

        if (outlineLength < 500) {
            questionCount = 5;
        } else if (outlineLength < 1000) {
            questionCount = 8;
        } else if (outlineLength < 2000) {
            questionCount = 15;
        } else if (outlineLength < 4000) {
            questionCount = 25;
        } else if (outlineLength < 6000) {
            questionCount = 40;
        } else if (outlineLength < 8000) {
            questionCount = 60;
        } else if (outlineLength < 10000) {
            questionCount = 80;
        } else {
            questionCount = 100;
        }

        return Math.min(questionCount, 100);
    }

    private List<String> getChapterImagePaths(List<String> allImagePaths, ChapterInfo chapter) {
        List<String> paths = new ArrayList<>();
        for (int page = chapter.getAdjustedStartPage(); page <= chapter.getAdjustedEndPage(); page++) {
            if (page >= 1 && page <= allImagePaths.size()) {
                paths.add(allImagePaths.get(page - 1));
            }
        }
        return paths;
    }

    private void updateProgress(Path outputPath, List<ChapterInfo> chapters, int current, int total) {
        try {
            String content = generateProgressContent(chapters, current, total);
            Files.writeString(outputPath.resolve("处理进度.md"), content, StandardCharsets.UTF_8);
            log.info("进度: {}/{} ({}%)", current, total, Math.round(current * 100.0 / total) + "%");
        } catch (IOException e) {
            log.error("更新进度失败: {}", e.getMessage());
        }
    }

    private String generateProgressContent(List<ChapterInfo> chapters, int current, int total) {
        return generateProgressContent(chapters, current, total, null, null);
    }

    private String generateProgressContent(List<ChapterInfo> chapters, int current, int total,
                                          String phase2Status, String phase3Status) {
        StringBuilder md = new StringBuilder();
        md.append("# 处理进度\n\n");

        // 第一阶段：书籍任务
        md.append("## 第一阶段：书籍任务\n\n");
        if (current >= total) {
            md.append("**状态**：✅ 已完成\n\n");
        } else {
            md.append("**阶段进度**：").append(current).append("/").append(total).append(" (")
               .append(Math.round(current * 100.0 / total)).append("%)\n\n");
        }
        md.append("| 状态 | 章节 | 页码 |\n");
        md.append("|------|------|------|\n");
        for (ChapterInfo chapter : chapters) {
            md.append("| ").append(chapter.getStatus());
            md.append(" | ").append(chapter.getTitle());
            md.append(" | 第").append(chapter.getAdjustedStartPage()).append("-").append(chapter.getAdjustedEndPage()).append("页 |\n");
        }
        md.append("\n");

        // 第二阶段：考试大纲任务
        md.append("## 第二阶段：考试大纲任务\n\n");
        if (phase2Status != null) {
            md.append("**状态**：").append(phase2Status).append("\n\n");
        } else {
            md.append("> 待处理（上传考试大纲后自动开始）\n\n");
        }

        // 第三阶段：生成题目
        md.append("## 第三阶段：生成题目\n\n");
        if (phase3Status != null) {
            md.append("**状态**：").append(phase3Status).append("\n\n");
        } else {
            md.append("> 待处理（考试大纲解析完成后自动开始）\n\n");
        }

        return md.toString();
    }

    // 更新进度文件（供外部调用，更新第二、三阶段状态）
    public void updateProgressWithPhases(String outputPath, List<ChapterInfo> chapters, int current, int total,
                                          String phase2Status, String phase3Status) {
        try {
            Path path = Paths.get(outputPath);
            String content = generateProgressContent(chapters, current, total, phase2Status, phase3Status);
            Files.writeString(path.resolve("处理进度.md"), content, StandardCharsets.UTF_8);
            log.info("进度已更新：阶段1({}/{}), 阶段2:{}, 阶段3:{}", current, total, phase2Status, phase3Status);
        } catch (IOException e) {
            log.error("更新进度失败: {}", e.getMessage());
        }
    }

    private String saveChapterText(Path outputPath, ChapterInfo chapter, String text,
                                   List<String> imagePaths,
                                   boolean layoutAnalyzerUsed) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("---\n");
        md.append("title: ").append(chapter.getTitle()).append("\n");
        md.append("date: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        md.append("tags: [原文]\n");
        md.append("---\n\n");
        md.append("# ").append(chapter.getTitle()).append("\n\n");
        md.append("> 页码：").append(chapter.getStartPage()).append("-").append(chapter.getEndPage()).append(" (PDF原始页码)\n\n");
        md.append("> 正文页码：").append(chapter.getAdjustedStartPage()).append("-").append(chapter.getAdjustedEndPage()).append("\n\n");
        md.append("---\n\n");
        md.append(text).append("\n\n");

        // 使用版面分析时，插图已按原位置插入正文中，不再在末尾追加图片列表
        if (!layoutAnalyzerUsed) {
            if (!imagePaths.isEmpty()) {
                md.append("## 本章图片\n\n");
                for (String path : imagePaths) {
                    String name = Paths.get(path).getFileName().toString();
                    md.append("![[content/").append(name).append("]]\n\n");
                }
            }
        }

        return saveFile(outputPath, chapter.getTextFileName(), md.toString());
    }

    private String generateSummaryMd(Path outputPath, String filename, List<ChapterInfo> chapters,
                                    int totalPages) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("---\n");
        md.append("title: ").append(filename).append("\n");
        md.append("date: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        md.append("tags: [汇总]\n");
        md.append("---\n\n");
        md.append("# ").append(filename).append("\n\n");
        md.append("- **正文页数**：").append(totalPages).append("\n");
        md.append("- **章节数**：").append(chapters.size()).append("\n\n");

        // 相关文件链接
        md.append("## 相关文件\n\n");
        md.append("- [[00_目录.md|目录]]\n");
        md.append("- [[处理进度.md|处理进度]]\n\n");

        md.append("## 章节列表\n\n");
        md.append("| 章节 | 原始页码 | 正文页码 | 原文 | 大纲 |\n");
        md.append("|------|---------|---------|------|------|\n");

        for (ChapterInfo chapter : chapters) {
            md.append("| ").append(chapter.getTitle());
            md.append(" | ").append(chapter.getStartPage()).append("-").append(chapter.getEndPage());
            md.append(" | ").append(chapter.getAdjustedStartPage()).append("-").append(chapter.getAdjustedEndPage());
            md.append(" | [[").append(chapter.getTextFileName()).append("|原文]]");
            md.append(" | [[").append(chapter.getOutlineFileName()).append("|大纲]] |\n");
        }

        return saveFile(outputPath, "00_汇总.md", md.toString());
    }

    private String loadImageAsBase64(String imagePath) throws IOException {
        byte[] imageBytes = Files.readAllBytes(Paths.get(imagePath));
        return Base64.getEncoder().encodeToString(imageBytes);
    }

    private Path createOutputDirectory(String filename, String customPath) throws IOException {
        Path outputPath;
        if (customPath != null && !customPath.isEmpty()) {
            outputPath = Paths.get(customPath);
        } else {
            String baseName = filename.substring(0, filename.lastIndexOf('.'));
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String dirName = sanitizeFilename(baseName) + "_" + timestamp;
            outputPath = Paths.get(OUTPUT_DIR, dirName);
        }
        Files.createDirectories(outputPath);
        log.info("创建输出目录: {}", outputPath);
        return outputPath;
    }

    private String saveFile(Path directory, String filename, String content) throws IOException {
        Path filePath = directory.resolve(filename);
        Files.writeString(filePath, content, StandardCharsets.UTF_8);
        log.info("保存: {}", filename);
        return filePath.toString();
    }

    private String sanitizeFilename(String filename) {
        return filename.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private List<PdfAnalysisResponse.ChapterInfo> convertToResponseChapters(List<ChapterInfo> chapters) {
        List<PdfAnalysisResponse.ChapterInfo> result = new ArrayList<>();
        for (ChapterInfo chapter : chapters) {
            result.add(PdfAnalysisResponse.ChapterInfo.builder()
                    .chapterNumber(chapter.getChapterNumber())
                    .title(chapter.getTitle())
                    .pageRange(chapter.getStartPage() + "-" + chapter.getEndPage())
                    .knowledgePointCount(chapter.getKnowledgePointCount())
                    .build());
        }
        return result;
    }

    private static class TableOfContents {
        private final List<ChapterInfo> chapters;
        private final int contentStartIndex;

        TableOfContents(List<ChapterInfo> chapters, int contentStartIndex) {
            this.chapters = chapters;
            this.contentStartIndex = contentStartIndex;
        }

        List<ChapterInfo> getChapters() { return chapters; }
        int getContentStartIndex() { return contentStartIndex; }
    }

    private static class ChapterInfo {
        private final int chapterNumber;
        private final String title;
        private final int startPage;
        private int endPage;
        private int adjustedStartPage;
        private int adjustedEndPage;
        private int knowledgePointCount;
        private String status;

        ChapterInfo(int chapterNumber, String title, int startPage, int knowledgePointCount, String status) {
            this.chapterNumber = chapterNumber;
            this.title = title;
            this.startPage = startPage;
            this.adjustedStartPage = startPage;
            this.adjustedEndPage = startPage;
            this.knowledgePointCount = knowledgePointCount;
            this.status = status;
        }

        int getChapterNumber() { return chapterNumber; }
        String getTitle() { return title; }
        int getStartPage() { return startPage; }
        int getEndPage() { return endPage; }
        int getAdjustedStartPage() { return adjustedStartPage; }
        int getAdjustedEndPage() { return adjustedEndPage; }
        int getKnowledgePointCount() { return knowledgePointCount; }
        String getStatus() { return status; }
        void setEndPage(int endPage) { this.endPage = endPage; }
        void setAdjustedPageRange(int start, int end) { this.adjustedStartPage = start; this.adjustedEndPage = end; }
        void setKnowledgePointCount(int count) { this.knowledgePointCount = count; }
        void setStatus(String status) { this.status = status; }

        String getTextFileName() {
            String prefix = title.startsWith("项目") ? "项目" + chapterNumber + "_" : "书籍第" + chapterNumber + "章_";
            return prefix + sanitize(title) + "_原文.md";
        }
        String getOutlineFileName() {
            String prefix = title.startsWith("项目") ? "项目" + chapterNumber + "_" : "书籍第" + chapterNumber + "章_";
            return prefix + sanitize(title) + "_大纲.md";
        }
        String getQuestionsFileName() {
            String prefix = title.startsWith("项目") ? "题目项目" + chapterNumber + "_" : "题目第" + chapterNumber + "章_";
            return prefix + sanitize(title) + ".md";
        }

        private String sanitize(String s) {
            return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        }
    }
}
