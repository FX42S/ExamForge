package com.ocr.app.service;

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

@Slf4j
@Service
@RequiredArgsConstructor
public class FastQuestionService {

    private final AiService aiService;
    private static final String OUTPUT_DIR = "output";

    /**
     * 快速生成题目：PDF直接转文字（不分章节），AI直接生成500道题目
     */
    public String generateQuestionsFromPdf(byte[] fileData, String filename) {
        long startTime = System.currentTimeMillis();
        try {
            log.info("========================================");
            log.info("【快速题目生成】开始处理: {}", filename);
            log.info("========================================");

            // 1. 提取PDF文本（直接全文提取，不分章节）
            log.info("[步骤1/4] PDF转文字（全文提取，不分章节）...");
            long stepStart = System.currentTimeMillis();
            String rawText = extractTextFromPdf(fileData);
            if (rawText == null || rawText.trim().isEmpty()) {
                log.error("【错误】无法提取PDF文本: {}", filename);
                throw new RuntimeException("无法提取PDF文本");
            }
            log.info("[步骤1/4] PDF文字提取完成，长度: {} 字符，耗时: {}ms",
                    rawText.length(), System.currentTimeMillis() - stepStart);

            // 2. 提取"课程内容与考核要求"部分
            log.info("[步骤2/4] 提取'课程内容与考核要求'部分...");
            stepStart = System.currentTimeMillis();
            String courseContent = extractCourseContent(rawText);
            if (courseContent == null || courseContent.trim().isEmpty()) {
                log.warn("【警告】未找到'课程内容与考核要求'部分，将使用全文");
                courseContent = rawText;
            }
            log.info("[步骤2/4] 提取完成，长度: {} 字符，耗时: {}ms",
                    courseContent.length(), System.currentTimeMillis() - stepStart);

            // 3. 创建输出目录
            String baseName = filename.replaceAll("\\.pdf$", "").replaceAll("[^\\w\\u4e00-\\u9fa5]", "_");
            Path outputPath = Paths.get(OUTPUT_DIR, baseName);
            Files.createDirectories(outputPath);
            log.info("[步骤3/4] 输出目录创建完成: {}", outputPath);

            // 4. AI直接生成500道题目（基于课程内容）
            log.info("[步骤4/4] AI生成500道题目（基于课程内容）...");
            stepStart = System.currentTimeMillis();
            String questions = generate500Questions(courseContent, filename);
            log.info("[步骤4/4] 题目生成完成，耗时: {}ms", System.currentTimeMillis() - stepStart);

            // 4. 保存题目文件（文件名就是PDF文件名）
            String outputFileName = baseName + ".md";
            Path outputFile = outputPath.resolve(outputFileName);
            Files.writeString(outputFile, questions);

            long totalTime = System.currentTimeMillis() - startTime;
            log.info("========================================");
            log.info("【快速题目生成】处理完成！");
            log.info("文件名: {}", filename);
            log.info("输出文件: {}", outputFile);
            log.info("总耗时: {}ms (约 {} 秒)", totalTime, totalTime / 1000);
            log.info("========================================");

            // 创建完成标记文件
            Path finishFile = outputPath.resolve("finish.md");
            Files.writeString(finishFile, "");
            log.info("已创建完成标记文件: {}", finishFile);

            return outputFile.toString();

        } catch (Exception e) {
            log.error("【错误】快速题目生成失败: {}, 异常: {}", filename, e.getMessage(), e);
            throw new RuntimeException("生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从PDF提取文本（全文提取，不分章节）
     */
    private String extractTextFromPdf(byte[] fileData) {
        try (PDDocument document = Loader.loadPDF(new ByteArrayInputStream(fileData).readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            log.info("PDF提取完成，总页数: {}, 文本长度: {} 字符", document.getNumberOfPages(), text.length());
            return text;
        } catch (IOException e) {
            log.error("PDF文本提取失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 提取"课程内容与考核要求"部分
     * 从考试大纲中只提取核心内容，去掉前言、说明、评分方式等垃圾数据
     */
    private String extractCourseContent(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) {
            return null;
        }

        String text = rawText;

        // 尝试多种可能的标题格式
        String[] startMarkers = {
            "课程内容与考核要求",
            "课程内容和考核要求",
            "课程内容与考核目标",
            "考核内容与要求",
            "考试内容与要求",
            "课程考核内容",
            "三、课程内容与考核要求",
            "三、课程内容和考核要求",
            "（三）课程内容与考核要求"
        };

        String[] endMarkers = {
            "关于大纲的说明",
            "大纲说明",
            "有关说明与实施要求",
            "说明与实施要求",
            "附录",
            "题型举例",
            "参考书目",
            "参考文献",
            "四、",
            "（四）"
        };

        int startIndex = -1;
        String foundStartMarker = null;

        // 找到起始位置
        for (String marker : startMarkers) {
            int idx = text.indexOf(marker);
            if (idx != -1) {
                if (startIndex == -1 || idx < startIndex) {
                    startIndex = idx;
                    foundStartMarker = marker;
                }
            }
        }

        if (startIndex == -1) {
            log.warn("未找到'课程内容与考核要求'起始标记");
            return null;
        }

        log.info("找到起始标记: '{}'，位置: {}", foundStartMarker, startIndex);

        // 从起始标记后开始截取
        int contentStart = startIndex + foundStartMarker.length();

        // 找到结束位置
        int endIndex = text.length();
        for (String marker : endMarkers) {
            int idx = text.indexOf(marker, contentStart);
            if (idx != -1 && idx < endIndex) {
                endIndex = idx;
                log.info("找到结束标记: '{}'，位置: {}", marker, idx);
            }
        }

        String extracted = text.substring(contentStart, endIndex).trim();

        // 进一步清理：去掉常见的垃圾内容
        extracted = cleanGarbageContent(extracted);

        log.info("提取'课程内容与考核要求'完成，原始长度: {}，提取后长度: {}", rawText.length(), extracted.length());

        return extracted;
    }

    /**
     * 清理垃圾内容
     */
    private String cleanGarbageContent(String text) {
        if (text == null) return null;

        String[] garbagePatterns = {
            "本课程考试评分采用",
            "考试评分采用",
            "课程采用",
            "考核方式",
            "考试形式",
            "考试时间",
            "考试总分",
            "评分标准",
            "成绩评定",
            "本大纲",
            "大纲由",
            "大纲在",
            "编写说明",
            "课程性质与设置目的",
            "课程性质",
            "设置目的",
            "课程简介",
            "课程目标"
        };

        String result = text;
        for (String pattern : garbagePatterns) {
            // 找到垃圾内容的位置，从该位置截断（假设垃圾内容在末尾）
            int idx = result.indexOf(pattern);
            if (idx != -1) {
                // 检查是否是段落开头（前面是换行或空白）
                boolean isParagraphStart = false;
                if (idx == 0) {
                    isParagraphStart = true;
                } else {
                    char prevChar = result.charAt(idx - 1);
                    if (prevChar == '\n' || prevChar == '\r' || prevChar == ' ' || prevChar == '　') {
                        isParagraphStart = true;
                    }
                }

                if (isParagraphStart) {
                    log.info("清理垃圾内容: '{}'，位置: {}", pattern, idx);
                    result = result.substring(0, idx).trim();
                }
            }
        }

        return result;
    }

    /**
     * 生成500道题目（基于全文内容，不分章节）
     * 修复：确保题目编号连续，总共500道
     */
    private String generate500Questions(String fullText, String filename) {
        // 如果文本太长，分段处理
        int chunkSize = 4000;
        int totalLength = fullText.length();

        if (totalLength <= chunkSize) {
            // 文本较短，直接生成500题
            log.info("文本较短({}字符)，直接生成500题", totalLength);
            return callAiFor500Questions(fullText, 500, 1, filename);
        }

        // 文本较长，分段生成题目
        StringBuilder allQuestions = new StringBuilder();
        int chunkCount = (totalLength + chunkSize - 1) / chunkSize;
        int questionsPerChunk = 500 / chunkCount;

        log.info("文本较长({}字符)，分为{}段生成题目，每段约{}题", totalLength, chunkCount, questionsPerChunk);

        int currentQuestionNumber = 1; // 题目编号从1开始，确保连续

        for (int i = 0; i < chunkCount; i++) {
            int start = i * chunkSize;
            int end = Math.min(start + chunkSize, totalLength);
            String chunk = fullText.substring(start, end);

            int chunkQuestions = questionsPerChunk;
            if (i == chunkCount - 1) {
                chunkQuestions = 500 - (questionsPerChunk * (chunkCount - 1));
            }

            log.info("生成第{}/{}段题目 ({}字符，{}题，起始编号: {})", i + 1, chunkCount, chunk.length(), chunkQuestions, currentQuestionNumber);
            String chunkResult = callAiFor500Questions(chunk, chunkQuestions, currentQuestionNumber, filename);

            if (chunkResult != null && !chunkResult.isEmpty()) {
                allQuestions.append(chunkResult).append("\n\n");
            }

            currentQuestionNumber += chunkQuestions; // 更新下一批的起始编号
        }

        String result = allQuestions.toString().trim();
        return result.isEmpty() ? "[题目生成失败]" : result;
    }

    /**
     * 调用AI生成题目（与书籍处理相同的题目格式）
     * 修复：传入起始编号，确保题目编号连续
     */
    private String callAiFor500Questions(String textChunk, int questionCount, int startNumber, String filename) {
        int endNumber = startNumber + questionCount - 1;

        String prompt = "你是一位专业的考试命题专家。请根据以下PDF文档内容，生成 " + questionCount + " 道考试题目。\n\n" +
                "【PDF文档内容】\n" + textChunk + "\n\n" +
                "【重要要求】\n" +
                "1. 题目编号必须从第 " + startNumber + " 题开始，到第 " + endNumber + " 题结束\n" +
                "2. 严禁生成与考试内容无关的题目（如评分方式、考试形式、课程介绍、编写说明等）\n" +
                "3. 只生成真正的考试题目，不要生成任何非题目内容\n\n" +
                "【命题原则】\n" +
                "1. 严格围绕文档中的知识点出题\n" +
                "2. 题目必须基于文档内容，不能编造\n" +
                "3. 覆盖文档中的重要知识点\n" +
                "4. 题目难度适中，符合考试要求\n\n" +
                "【题型要求】\n" +
                "必须包含以下五种题型：\n" +
                "- 单选题（约占总题数40%）\n" +
                "- 多选题（约占总题数20%）\n" +
                "- 判断题（约占总题数15%）\n" +
                "- 填空题（约占总题数15%）\n" +
                "- 简答题（约占总题数10%）\n\n" +
                "【任务要求】\n" +
                "1. 共生成 " + questionCount + " 道题目，编号从 " + startNumber + " 到 " + endNumber + "\n" +
                "2. 每道题必须标注：题型、难度等级（简单/中等/困难）、答案、知识点\n" +
                "3. 单选题提供4个选项，多选题提供4-5个选项\n" +
                "4. 每道题要注明对应文档的哪个知识点\n\n" +
                "【输出格式】\n" +
                "============================================================================\n" +
                "[单选题]第" + startNumber + "题（难度：简单）\n" +
                "题目：XXX\n" +
                "A. XXX\n" +
                "B. XXX\n" +
                "C. XXX\n" +
                "D. XXX\n" +
                "答案：A\n" +
                "知识点：XXX\n" +
                "解析：XXX\n" +
                "============================================================================";

        return aiService.callAiTextOnly(prompt);
    }
}
