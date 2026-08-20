package com.ocr.app.service;

import com.ocr.app.dto.ExamOutlineChapter;
import com.ocr.app.dto.ExamOutlineResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExamOutlineService {

    private final AiService aiService;
    private static final String OUTPUT_DIR = "output";

    /**
     * 解析考试大纲PDF（保存到指定书籍目录）
     */
    public ExamOutlineResult parseExamOutlineToBook(byte[] fileData, String filename, String bookOutputPath) {
        try {
            log.info("开始解析考试大纲: {} -> {}", filename, bookOutputPath);

            // 提取PDF文本
            String rawText = extractTextFromPdf(fileData);
            if (rawText == null || rawText.trim().isEmpty()) {
                return ExamOutlineResult.builder()
                        .success(false)
                        .filename(filename)
                        .error("无法提取PDF文本")
                        .build();
            }

            // 让AI清理考试大纲，去掉废话，只保留核心考点
            String cleanedText = cleanExamOutline(rawText);
            log.info("考试大纲AI清理完成，原始长度: {} -> 清理后: {}", rawText.length(), cleanedText.length());

            // 保存清理后的考试大纲到书籍目录
            String outlineFile = bookOutputPath + "/考试大纲_原文.txt";
            Files.writeString(Paths.get(outlineFile), cleanedText);
            log.info("考试大纲已保存: {}", outlineFile);

            // 解析章节结构（用于生题时匹配章节）
            List<ExamOutlineChapter> chapters = parseChapters(cleanedText);

            log.info("考试大纲解析完成: {} 个章节", chapters.size());

            return ExamOutlineResult.builder()
                    .success(true)
                    .filename(filename)
                    .chapters(chapters)
                    .outputPath(bookOutputPath)
                    .fullText(cleanedText)
                    .build();

        } catch (Exception e) {
            log.error("解析考试大纲失败", e);
            return ExamOutlineResult.builder()
                    .success(false)
                    .filename(filename)
                    .error("解析失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 让AI清理考试大纲，去掉废话，只保留核心考点
     */
    private String cleanExamOutline(String rawText) {
        try {
            // 截取避免过长
            String input = rawText;
            if (input.length() > 8000) {
                input = input.substring(0, 8000);
            }

            String prompt = "你是一个考试大纲编辑。请仔细阅读以下考试大纲内容，做以下两件事：\n\n" +
                    "1. 删除所有与考试无关的内容，包括：\n" +
                    "   - 前言、说明、编写说明、出版说明\n" +
                    "   - 版权信息、出版社信息\n" +
                    "   - 附录、参考文献、后记\n" +
                    "   - 空白行、重复的空行\n\n" +
                    "2. 保留并整理核心考试内容，包括：\n" +
                    "   - 各章节标题和序号\n" +
                    "   - 每个章节下的知识点要点\n" +
                    "   - 考试要求、掌握程度等\n\n" +
                    "[考试大纲原文]\n" + input + "\n\n" +
                    "[输出要求]\n" +
                    "只输出清理后的考试大纲内容，保持原有章节结构，不要添加任何解释或总结。";

            String cleaned = aiService.callAiTextOnly(prompt);
            if (cleaned == null || cleaned.trim().isEmpty()) {
                return rawText; // AI失败时返回原文
            }
            return cleaned;
        } catch (Exception e) {
            log.error("AI清理考试大纲失败，返回原文", e);
            return rawText;
        }
    }

    /**
     * 解析考试大纲PDF（旧方法，保留兼容性）
     */
    public ExamOutlineResult parseExamOutline(byte[] fileData, String filename) {
        try {
            log.info("开始解析考试大纲: {}", filename);

            // 提取PDF文本
            String fullText = extractTextFromPdf(fileData);
            if (fullText == null || fullText.trim().isEmpty()) {
                return ExamOutlineResult.builder()
                        .success(false)
                        .filename(filename)
                        .error("无法提取PDF文本")
                        .build();
            }

            // 创建输出目录
            String outputPath = createOutputDirectory(filename);

            // 保存完整文本（一份，不分章节）
            saveTextFile(outputPath, "考试大纲_原文.txt", fullText);

            // 解析章节结构（用于生题时匹配章节）
            List<ExamOutlineChapter> chapters = parseChapters(fullText);

            log.info("考试大纲解析完成: {} 个章节，已保存为考试大纲_原文.txt", chapters.size());

            return ExamOutlineResult.builder()
                    .success(true)
                    .filename(filename)
                    .chapters(chapters)
                    .outputPath(outputPath)
                    .fullText(fullText)
                    .build();

        } catch (Exception e) {
            log.error("解析考试大纲失败", e);
            return ExamOutlineResult.builder()
                    .success(false)
                    .filename(filename)
                    .error("解析失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 从PDF提取文本
     */
    private String extractTextFromPdf(byte[] fileData) {
        try (PDDocument document = Loader.loadPDF(fileData)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        } catch (IOException e) {
            log.error("提取PDF文本失败", e);
            return null;
        }
    }

    /**
     * 解析章节结构
     * 支持格式：第一章 xxx、第1章 xxx、项目一 xxx、项目1 xxx、一、xxx 等
     */
    private List<ExamOutlineChapter> parseChapters(String text) {
        List<ExamOutlineChapter> chapters = new ArrayList<>();

        // 匹配模式（支持"第X章"和"项目X"格式）
        String[] patterns = {
            "第[一二三四五六七八九十百千\\d]+章[\\s\\t]*(.+)",
            "第[\\d]+章[\\s\\t]*(.+)",
            "项目[一二三四五六七八九十百千\\d]+[\\s\\t]*(.+)",
            "项目[\\d]+[\\s\\t]*(.+)",
            "[一二三四五六七八九十][、\\.\\s\\t]+(.+)",
            "[\\d]+[、\\.\\s\\t]+(.+)"
        };

        String[] lines = text.split("\\n");
        int currentChapter = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            for (String patternStr : patterns) {
                Pattern pattern = Pattern.compile(patternStr);
                Matcher matcher = pattern.matcher(line);

                if (matcher.find()) {
                    currentChapter++;
                    String title = matcher.group(1).trim();

                    // 提取章节内容（到下一个章节或结束）
                    StringBuilder content = new StringBuilder();
                    for (int j = i + 1; j < lines.length; j++) {
                        String nextLine = lines[j].trim();
                        if (nextLine.isEmpty()) continue;

                        // 检查是否是新章节
                        boolean isNewChapter = false;
                        for (String p : patterns) {
                            if (Pattern.compile(p).matcher(nextLine).find()) {
                                isNewChapter = true;
                                break;
                            }
                        }

                        if (isNewChapter) break;
                        content.append(nextLine).append("\n");
                    }

                    chapters.add(ExamOutlineChapter.builder()
                            .chapterNumber(currentChapter)
                            .title(title)
                            .content(content.toString().trim())
                            .build());

                    break;
                }
            }
        }

        // 如果没有解析到章节，使用AI辅助解析
        if (chapters.isEmpty()) {
            log.info("未找到章节结构，使用AI辅助解析");
            chapters = parseWithAI(text);
        }

        return chapters;
    }

    /**
     * 使用AI辅助解析章节
     */
    private List<ExamOutlineChapter> parseWithAI(String text) {
        List<ExamOutlineChapter> chapters = new ArrayList<>();

        try {
            String prompt = "这是一份考试大纲的文本内容，请帮我提取章节结构。\n\n" +
                    "要求：\n" +
                    "1. 找出所有章节标题（格式通常是：第一章 xxx、第1章 xxx、项目一 xxx、项目1 xxx、一、xxx 等）\n" +
                    "2. 提取每个章节的内容概要\n" +
                    "3. 返回格式：章节编号|章节标题|章节内容概要\n\n" +
                    "文本内容：\n" + text.substring(0, Math.min(text.length(), 5000)) + "\n\n" +
                    "请只返回格式化的章节列表，不要其他说明。";

            String response = aiService.callAiTextOnly(prompt);

            // 解析AI返回的结果
            String[] lines = response.split("\\n");
            for (String line : lines) {
                String[] parts = line.split("\\|", 3);
                if (parts.length >= 2) {
                    try {
                        int num = Integer.parseInt(parts[0].trim());
                        chapters.add(ExamOutlineChapter.builder()
                                .chapterNumber(num)
                                .title(parts[1].trim())
                                .content(parts.length > 2 ? parts[2].trim() : "")
                                .build());
                    } catch (NumberFormatException e) {
                        // 忽略解析失败的行
                    }
                }
            }
        } catch (Exception e) {
            log.error("AI辅助解析失败", e);
        }

        return chapters;
    }

    /**
     * 根据考试大纲生成题目
     */
    public String generateQuestionsFromOutline(String bookChapterText, ExamOutlineChapter examChapter, int questionCount) {
        try {
            // 截取原文，避免过长
            String truncatedText = bookChapterText;
            if (truncatedText.length() > 8000) {
                truncatedText = truncatedText.substring(0, 8000) + "\n...[原文过长，已截取前8000字]";
            }

            String prompt = "你是一位专业的考试命题专家。请根据以下教材原文和考试大纲要求，生成考试题目。\n\n" +
                    "【考试大纲要求】\n" +
                    "章节：" + examChapter.getTitle() + "\n" +
                    "内容要求：" + examChapter.getContent() + "\n\n" +
                    "【教材原文】\n" +
                    truncatedText + "\n\n" +
                    "【命题原则】\n" +
                    "1. 严格围绕考试大纲的要求出题，大纲要求什么就考什么\n" +
                    "2. 所有题目必须能在教材原文中找到明确依据，禁止编造\n" +
                    "3. 重点考察大纲中强调的核心概念、关键知识点\n" +
                    "4. 题目难度要适中，符合考试大纲的考核要求\n\n" +
                    "【题型要求】\n" +
                    "必须包含以下五种题型：\n" +
                    "- 单选题（约占总题数40%）\n" +
                    "- 多选题（约占总题数20%）\n" +
                    "- 判断题（约占总题数15%）\n" +
                    "- 填空题（约占总题数15%）\n" +
                    "- 简答题（约占总题数10%）\n\n" +
                    "【任务要求】\n" +
                    "1. 共生成 " + questionCount + " 道题目\n" +
                    "2. 每道题必须标注：题型、难度等级（简单/中等/困难）、答案、知识点、对应大纲要求\n" +
                    "3. 单选题提供4个选项，多选题提供4-5个选项\n\n" +
                    "【输出格式示例】\n" +
                    "============================================================================\n" +
                    "[单选题]第1题（难度：简单）\n" +
                    "题目：XXX\n" +
                    "A. XXX\n" +
                    "B. XXX\n" +
                    "C. XXX\n" +
                    "D. XXX\n" +
                    "答案：A\n" +
                    "知识点：XXX\n" +
                    "对应大纲：XXX\n" +
                    "解析：XXX\n" +
                    "============================================================================\n" +
                    "[多选题]第2题（难度：中等）\n" +
                    "题目：XXX\n" +
                    "A. XXX\n" +
                    "B. XXX\n" +
                    "C. XXX\n" +
                    "D. XXX\n" +
                    "答案：AB\n" +
                    "知识点：XXX\n" +
                    "对应大纲：XXX\n" +
                    "解析：XXX\n" +
                    "============================================================================";

            return aiService.callAiTextOnly(prompt);

        } catch (Exception e) {
            log.error("生成考试题目失败", e);
            return "生成题目失败: " + e.getMessage();
        }
    }

    /**
     * 根据教材原文、章节大纲和考试大纲生成题目
     * 每章：书籍原文 + 章节大纲 + 考试大纲 -> 生成该章题目
     * 考试大纲用于控制范围，题目根据章节大纲从原文中生成
     */
    public String generateQuestionsFromBookAndOutline(String bookChapterText, String chapterOutline,
                                                       String fullExamOutline, ExamOutlineChapter examChapter,
                                                       int questionCount) {
        try {
            // 题目数量限制在 5-100 之间
            questionCount = Math.max(5, Math.min(100, questionCount));

            // 截取原文，避免过长（分段处理时外部已经分段，这里做最后保护）
            String truncatedText = bookChapterText;
            if (truncatedText.length() > 8000) {
                truncatedText = truncatedText.substring(0, 8000) + "\n...[原文过长，已截取前8000字]";
            }

            // 截取章节大纲
            String truncatedChapterOutline = chapterOutline;
            if (truncatedChapterOutline != null && truncatedChapterOutline.length() > 3000) {
                truncatedChapterOutline = truncatedChapterOutline.substring(0, 3000) + "\n...[章节大纲过长，已截取]";
            }

            // 截取考试大纲，避免过长
            String truncatedOutline = fullExamOutline;
            if (truncatedOutline.length() > 3000) {
                truncatedOutline = truncatedOutline.substring(0, 3000) + "\n...[考试大纲过长，已截取]";
            }

            String prompt = "你是一位专业的考试命题专家。请根据以下材料生成考试题目。\n\n" +
                    "【考试大纲】（控制出题范围，粗略参考）\n" +
                    truncatedOutline + "\n\n" +
                    "【章节知识大纲】（核心依据，题目必须围绕这些知识点）\n" +
                    (truncatedChapterOutline != null ? truncatedChapterOutline : "[无章节大纲]") + "\n\n" +
                    "【教材原文】（出题素材，所有题目必须能在原文中找到依据）\n" +
                    truncatedText + "\n\n" +
                    "【当前章节考试大纲要求】\n" +
                    "章节：" + examChapter.getTitle() + "\n" +
                    "内容要求：" + examChapter.getContent() + "\n\n" +
                    "【命题原则】\n" +
                    "1. 考试大纲只是粗略控制范围，具体题目必须严格根据【章节知识大纲】和【教材原文】来生成\n" +
                    "2. 所有题目必须能在教材原文中找到明确依据，禁止编造任何内容\n" +
                    "3. 优先考察章节大纲中列出的核心概念和重要知识点\n" +
                    "4. 题目难度要适中，符合考试要求\n" +
                    "5. 严禁生成原文中没有的内容\n\n" +
                    "【题型要求】\n" +
                    "必须包含以下五种题型：\n" +
                    "- 单选题（约占总题数40%）\n" +
                    "- 多选题（约占总题数20%）\n" +
                    "- 判断题（约占总题数15%）\n" +
                    "- 填空题（约占总题数15%）\n" +
                    "- 简答题（约占总题数10%）\n\n" +
                    "【任务要求】\n" +
                    "1. 共生成 " + questionCount + " 道题目，不要多也不要少\n" +
                    "2. 每道题必须标注：题型、难度等级（简单/中等/困难）、答案、知识点\n" +
                    "3. 单选题提供4个选项，多选题提供4-5个选项\n" +
                    "4. 每道题要注明对应原文的哪个知识点\n\n" +
                    "【输出格式】\n" +
                    "============================================================================\n" +
                    "[单选题]第1题（难度：简单）\n" +
                    "题目：XXX\n" +
                    "A. XXX\n" +
                    "B. XXX\n" +
                    "C. XXX\n" +
                    "D. XXX\n" +
                    "答案：A\n" +
                    "知识点：XXX\n" +
                    "解析：XXX\n" +
                    "============================================================================\n" +
                    "[多选题]第2题（难度：中等）\n" +
                    "题目：XXX\n" +
                    "A. XXX\n" +
                    "B. XXX\n" +
                    "C. XXX\n" +
                    "D. XXX\n" +
                    "答案：AB\n" +
                    "知识点：XXX\n" +
                    "解析：XXX\n" +
                    "============================================================================";

            return aiService.callAiTextOnly(prompt);

        } catch (Exception e) {
            log.error("生成考试题目失败", e);
            return "生成题目失败: " + e.getMessage();
        }
    }

    /**
     * 仅根据教材原文和章节大纲生成题目（不依赖考试大纲）
     */
    public String generateQuestionsFromBookOnly(String bookChapterText, String chapterOutline,
                                                 String chapterTitle, int questionCount) {
        try {
            // 题目数量限制在 5-100 之间
            questionCount = Math.max(5, Math.min(100, questionCount));

            // 截取原文，避免过长
            String truncatedText = bookChapterText;
            if (truncatedText.length() > 8000) {
                truncatedText = truncatedText.substring(0, 8000) + "\n...[原文过长，已截取前8000字]";
            }

            // 截取章节大纲
            String truncatedChapterOutline = chapterOutline;
            if (truncatedChapterOutline != null && truncatedChapterOutline.length() > 3000) {
                truncatedChapterOutline = truncatedChapterOutline.substring(0, 3000) + "\n...[章节大纲过长，已截取]";
            }

            String prompt = "你是一位专业的考试命题专家。请根据以下教材原文和章节知识大纲生成考试题目。\n\n" +
                    "【章节标题】\n" + chapterTitle + "\n\n" +
                    "【章节知识大纲】（核心依据，题目必须围绕这些知识点）\n" +
                    (truncatedChapterOutline != null ? truncatedChapterOutline : "[无章节大纲]") + "\n\n" +
                    "【教材原文】（出题素材，所有题目必须能在原文中找到依据）\n" +
                    truncatedText + "\n\n" +
                    "【命题原则】\n" +
                    "1. 具体题目必须严格根据【章节知识大纲】和【教材原文】来生成\n" +
                    "2. 所有题目必须能在教材原文中找到明确依据，禁止编造任何内容\n" +
                    "3. 优先考察章节大纲中列出的核心概念和重要知识点\n" +
                    "4. 题目难度要适中，符合教学要求\n" +
                    "5. 严禁生成原文中没有的内容\n\n" +
                    "【题型要求】\n" +
                    "必须包含以下五种题型：\n" +
                    "- 单选题（约占总题数40%）\n" +
                    "- 多选题（约占总题数20%）\n" +
                    "- 判断题（约占总题数15%）\n" +
                    "- 填空题（约占总题数15%）\n" +
                    "- 简答题（约占总题数10%）\n\n" +
                    "【任务要求】\n" +
                    "1. 共生成 " + questionCount + " 道题目，不要多也不要少\n" +
                    "2. 每道题必须标注：题型、难度等级（简单/中等/困难）、答案、知识点\n" +
                    "3. 单选题提供4个选项，多选题提供4-5个选项\n" +
                    "4. 每道题要注明对应原文的哪个知识点\n\n" +
                    "【输出格式】\n" +
                    "============================================================================\n" +
                    "[单选题]第1题（难度：简单）\n" +
                    "题目：XXX\n" +
                    "A. XXX\n" +
                    "B. XXX\n" +
                    "C. XXX\n" +
                    "D. XXX\n" +
                    "答案：A\n" +
                    "知识点：XXX\n" +
                    "解析：XXX\n" +
                    "============================================================================\n" +
                    "[多选题]第2题（难度：中等）\n" +
                    "题目：XXX\n" +
                    "A. XXX\n" +
                    "B. XXX\n" +
                    "C. XXX\n" +
                    "D. XXX\n" +
                    "答案：AB\n" +
                    "知识点：XXX\n" +
                    "解析：XXX\n" +
                    "============================================================================";

            return aiService.callAiTextOnly(prompt);

        } catch (Exception e) {
            log.error("生成考试题目失败", e);
            return "生成题目失败: " + e.getMessage();
        }
    }

    /**
     * 保存文本文件
     */
    private void saveTextFile(String outputPath, String filename, String content) throws IOException {
        Path filePath = Paths.get(outputPath, filename);
        Files.writeString(filePath, content);
        log.info("保存文件: {}", filePath);
    }

    /**
     * 创建输出目录
     */
    private String createOutputDirectory(String filename) throws IOException {
        String baseName = filename;
        if (filename.contains(".")) {
            baseName = filename.substring(0, filename.lastIndexOf('.'));
        }
        baseName = baseName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String dirName = baseName + "_" + timestamp;

        Path outputPath = Paths.get(OUTPUT_DIR, dirName);
        Files.createDirectories(outputPath);
        log.info("创建输出目录: {}", outputPath);
        return outputPath.toString();
    }
}
