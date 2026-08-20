package com.ocr.app.service;

import com.ocr.app.dto.ExamOutlineChapter;
import com.ocr.app.dto.ExamOutlineResult;
import com.ocr.app.dto.TaskInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class TaskQueueService {

    // 最大并发处理数
    private static final int MAX_CONCURRENT = 1;
    // 待处理队列
    private final Queue<TaskInfo> taskQueue = new ConcurrentLinkedQueue<>();
    // 处理中的任务
    private final Map<String, TaskInfo> processingTasks = new ConcurrentHashMap<>();
    // 已完成的任务（保留最近20条）
    private final LinkedList<TaskInfo> completedTasks = new LinkedList<>();
    // 任务监听器列表
    private final List<TaskListener> listeners = new CopyOnWriteArrayList<>();
    // 存储文件数据（临时）
    private final Map<String, byte[]> taskFileData = new ConcurrentHashMap<>();
    // 存储考试大纲解析结果
    private final Map<String, ExamOutlineResult> examOutlines = new ConcurrentHashMap<>();
    // 存储书籍任务结果（用于关联）
    private final Map<String, String> bookTaskPaths = new ConcurrentHashMap<>();

    // 服务引用
    private PdfBookAnalysisService pdfBookAnalysisService;
    private ExamOutlineService examOutlineService;

    // 任务监听器接口
    public interface TaskListener {
        void onTaskUpdate(TaskInfo task);
    }

    // 添加监听器
    public void addListener(TaskListener listener) {
        listeners.add(listener);
    }

    // 移除监听器
    public void removeListener(TaskListener listener) {
        listeners.remove(listener);
    }

    // 通知所有监听器
    private void notifyListeners(TaskInfo task) {
        for (TaskListener listener : listeners) {
            try {
                listener.onTaskUpdate(task);
            } catch (Exception e) {
                log.error("通知监听器失败", e);
            }
        }
    }

    // 提交书籍分析任务
    public TaskInfo submitBookTask(String fileName, byte[] fileData) {
        return submitTask(fileName, fileData, "book", null);
    }

    // 提交仅书籍任务（无需考试大纲，自动生成题目）
    public TaskInfo submitBookOnlyTask(String fileName, byte[] fileData) {
        return submitTask(fileName, fileData, "book-only", null);
    }

    // 提交考试大纲解析任务
    public TaskInfo submitOutlineTask(String fileName, byte[] fileData, String relatedBookTaskId) {
        return submitTask(fileName, fileData, "outline", relatedBookTaskId);
    }

    // 提交任务（通用）
    private TaskInfo submitTask(String fileName, byte[] fileData, String taskType, String relatedTaskId) {
        String taskId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        TaskInfo task = TaskInfo.builder()
                .taskId(taskId)
                .fileName(fileName)
                .taskType(taskType)
                .status("pending")
                .progress(0)
                .currentStep("等待中")
                .message("任务已提交，等待处理")
                .createTime(now)
                .updateTime(now)
                .relatedTaskId(relatedTaskId)
                .build();

        taskQueue.offer(task);
        taskFileData.put(taskId, fileData);
        log.info("任务已提交: {} - {} - 类型: {}", taskId, fileName, taskType);

        // 通知监听器
        notifyListeners(task);

        return task;
    }

    // 获取所有任务状态
    public List<TaskInfo> getAllTasks() {
        List<TaskInfo> allTasks = new ArrayList<>();

        // 待处理任务
        allTasks.addAll(taskQueue);

        // 处理中任务
        allTasks.addAll(processingTasks.values());

        // 已完成任务（倒序，最新的在前）
        List<TaskInfo> completed = new ArrayList<>(completedTasks);
        Collections.reverse(completed);
        allTasks.addAll(completed);

        return allTasks;
    }

    // 获取待处理任务数量
    public int getPendingCount() {
        return taskQueue.size();
    }

    // 获取处理中任务数量
    public int getProcessingCount() {
        return processingTasks.size();
    }

    // 删除任务
    public boolean removeTask(String taskId) {
        // 从队列中移除
        Iterator<TaskInfo> iterator = taskQueue.iterator();
        while (iterator.hasNext()) {
            TaskInfo task = iterator.next();
            if (task.getTaskId().equals(taskId)) {
                iterator.remove();
                taskFileData.remove(taskId);
                log.info("任务已删除: {}", taskId);
                return true;
            }
        }
        return false;
    }

    // 清空已完成任务
    public void clearCompletedTasks() {
        completedTasks.clear();
        log.info("已完成任务列表已清空");
    }

    // 启动队列处理器
    public void startProcessing(PdfBookAnalysisService pdfService, ExamOutlineService outlineService) {
        this.pdfBookAnalysisService = pdfService;
        this.examOutlineService = outlineService;

        Thread processor = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 检查是否可以处理新任务
                    if (processingTasks.size() < MAX_CONCURRENT && !taskQueue.isEmpty()) {
                        TaskInfo task = taskQueue.poll();
                        if (task != null) {
                            processTask(task);
                        }
                    }
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "TaskQueueProcessor");
        processor.setDaemon(true);
        processor.start();
    }

    // 处理任务
    private void processTask(TaskInfo task) {
        String taskId = task.getTaskId();
        log.info("开始处理任务: {} - {} - 类型: {}", taskId, task.getFileName(), task.getTaskType());

        processingTasks.put(taskId, task);
        updateTaskStatus(task, "processing", 0, "准备中", "正在初始化...");

        try {
            byte[] fileData = taskFileData.get(taskId);
            if (fileData == null) {
                throw new IllegalStateException("找不到文件数据");
            }

            if ("book".equals(task.getTaskType())) {
                // 处理书籍分析任务
                processBookTask(task, fileData);
            } else if ("book-only".equals(task.getTaskType())) {
                // 处理仅书籍任务（自动生成题目，无需考纲）
                processBookOnlyTask(task, fileData);
            } else if ("outline".equals(task.getTaskType())) {
                // 处理考试大纲任务
                processOutlineTask(task, fileData);
            } else {
                throw new IllegalStateException("未知的任务类型: " + task.getTaskType());
            }

        } catch (Exception e) {
            log.error("任务处理失败: {}", taskId, e);
            task.setStatus("failed");
            task.setErrorMessage(e.getMessage());
        } finally {
            task.setUpdateTime(System.currentTimeMillis());

            // 从处理中移除
            processingTasks.remove(taskId);

            // 清理文件数据
            taskFileData.remove(taskId);

            // 添加到已完成列表
            synchronized (completedTasks) {
                completedTasks.add(task);
                // 只保留最近20条
                while (completedTasks.size() > 20) {
                    completedTasks.removeFirst();
                }
            }

            // 通知监听器
            notifyListeners(task);
        }
    }

    // 处理书籍任务
    private void processBookTask(TaskInfo task, byte[] fileData) {
        String outputBasePath = getOutputPath(task.getFileName());

        PdfBookAnalysisService.ProgressCallback callback = (progress, step, message) -> {
            updateTaskStatus(task, "processing", progress, step, message);
        };

        var result = pdfBookAnalysisService.analyzeBook(fileData, task.getFileName(), outputBasePath, callback);

        if (result.isSuccess()) {
            task.setChaptersCount(result.getChapters().size());
            task.setOutputPath(result.getOutputPath());
            task.setStatus("completed");
            task.setProgress(100);
            task.setCurrentStep("完成");
            task.setMessage("处理完成");

            // 保存书籍任务路径，供考试大纲关联使用
            bookTaskPaths.put(task.getTaskId(), result.getOutputPath());
        } else {
            task.setStatus("failed");
            task.setErrorMessage(result.getError());
        }
    }

    // 处理仅书籍任务（无需考试大纲，根据原文和大纲自动生成题目）
    private void processBookOnlyTask(TaskInfo task, byte[] fileData) {
        String outputBasePath = getOutputPath(task.getFileName());

        // 第一阶段：书籍分析（生成原文和大纲）
        PdfBookAnalysisService.ProgressCallback callback = (progress, step, message) -> {
            updateTaskStatus(task, "processing", progress, step, message);
        };

        var result = pdfBookAnalysisService.analyzeBook(fileData, task.getFileName(), outputBasePath, callback);

        if (!result.isSuccess()) {
            task.setStatus("failed");
            task.setErrorMessage(result.getError());
            return;
        }

        task.setChaptersCount(result.getChapters().size());
        task.setOutputPath(result.getOutputPath());
        String bookOutputPath = result.getOutputPath();
        bookTaskPaths.put(task.getTaskId(), bookOutputPath);

        // 更新进度文件（第二阶段跳过，因为没有考纲）
        updateBookProgressPhase2Skipped(bookOutputPath);

        // 第三阶段：根据原文和大纲自动生成题目（不依赖考纲）
        generateQuestionsFromBookOnly(task, bookOutputPath);
    }

    // 处理考试大纲任务
    private void processOutlineTask(TaskInfo task, byte[] fileData) {
        updateTaskStatus(task, "processing", 10, "解析大纲", "正在解析考试大纲...");

        // 如果有关联的书籍任务，直接保存到书籍目录
        String bookPath = null;
        if (task.getRelatedTaskId() != null) {
            bookPath = bookTaskPaths.get(task.getRelatedTaskId());
        }

        ExamOutlineResult outlineResult;
        if (bookPath != null) {
            // 直接保存到书籍目录 + AI清理
            outlineResult = examOutlineService.parseExamOutlineToBook(fileData, task.getFileName(), bookPath);
        } else {
            // 保存到单独目录（旧逻辑）
            outlineResult = examOutlineService.parseExamOutline(fileData, task.getFileName());
        }

        if (!outlineResult.isSuccess()) {
            task.setStatus("failed");
            task.setErrorMessage(outlineResult.getError());
            return;
        }

        task.setChaptersCount(outlineResult.getChapters().size());
        task.setOutputPath(outlineResult.getOutputPath());

        // 保存考试大纲结果
        examOutlines.put(task.getTaskId(), outlineResult);

        // 更新书籍目录的进度文件（第二阶段：考试大纲已保存到书籍目录）
        if (bookPath != null) {
            updateBookProgressPhase2(bookPath, outlineResult.getChapters().size());
        }

        updateTaskStatus(task, "processing", 50, "解析完成", "考试大纲解析完成，准备生成题目...");

        // 如果有关联的书籍任务，生成基于考试大纲的题目
        if (task.getRelatedTaskId() != null) {
            // 等待关联的书籍任务完成（最多等30分钟）
            String bookTaskId = task.getRelatedTaskId();
            int waitCount = 0;
            while (!bookTaskPaths.containsKey(bookTaskId) && waitCount < 1800) {
                updateTaskStatus(task, "processing", 55, "等待书籍", "等待关联的教材分析完成...");
                log.info("考试大纲任务等待书籍任务完成: {} -> {}", task.getTaskId(), bookTaskId);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                waitCount++;
            }

            if (bookTaskPaths.containsKey(bookTaskId)) {
                generateExamQuestions(task, outlineResult, bookTaskId);
            } else {
                log.warn("等待书籍任务超时: {}", bookTaskId);
                updateTaskStatus(task, "completed", 100, "完成", "考试大纲解析完成（书籍处理超时）");
            }
        } else {
            updateTaskStatus(task, "completed", 100, "完成", "考试大纲解析完成（未关联书籍）");
        }
    }

    // 生成基于考试大纲的题目（第三阶段）
    // 每章：书籍原文 + 考试大纲该章节 -> 生成 题目第一章.md
    private void generateExamQuestions(TaskInfo task, ExamOutlineResult outlineResult, String bookTaskId) {
        try {
            updateTaskStatus(task, "processing", 60, "生成题目", "正在根据考试大纲生成题目...");

            String bookOutputPath = bookTaskPaths.get(bookTaskId);
            if (bookOutputPath == null) {
                log.warn("找不到关联的书籍任务路径: {}", bookTaskId);
                updateTaskStatus(task, "completed", 100, "完成", "考试大纲解析完成（未找到关联书籍）");
                return;
            }

            // 读取书籍各章节原文
            Map<String, Path> bookChapterFiles = readBookChapterFiles(bookOutputPath);
            log.info("读取到 {} 个书籍章节文件", bookChapterFiles.size());

            // 读取完整考试大纲原文
            String fullExamOutline = outlineResult.getFullText();
            if (fullExamOutline == null || fullExamOutline.trim().isEmpty()) {
                log.warn("考试大纲原文为空");
                fullExamOutline = "[考试大纲原文为空]";
            }

            // 为每个考试大纲章节生成题目
            List<ExamOutlineChapter> outlineChapters = outlineResult.getChapters();
            int totalChapters = outlineChapters.size();
            int successCount = 0;

            StringBuilder summary = new StringBuilder();
            summary.append("---\n");
            summary.append("title: ").append(task.getFileName()).append(" 题目汇总\n");
            summary.append("date: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
            summary.append("tags: [题目汇总]\n");
            summary.append("---\n\n");
            summary.append("# ").append(task.getFileName()).append(" 题目汇总\n\n");
            summary.append("> 基于教材原文和考试大纲生成\n\n");
            summary.append("## 题目列表\n\n");
            summary.append("| 章节 | 题目文件 |\n");
            summary.append("|------|----------|\n");

            for (int i = 0; i < outlineChapters.size(); i++) {
                ExamOutlineChapter outlineChapter = outlineChapters.get(i);
                int progress = 60 + ((i + 1) * 35 / totalChapters);
                updateTaskStatus(task, "processing", progress, "生成题目",
                        String.format("正在生成第%d章题目 (%d/%d)", outlineChapter.getChapterNumber(), i + 1, totalChapters));

                // 查找对应的书籍章节原文文件
                Path bookFile = findMatchingBookFile(bookChapterFiles, outlineChapter);

                if (bookFile == null) {
                    log.warn("未找到对应书籍章节原文: {}", outlineChapter.getTitle());
                    continue;
                }

                // 读取书籍原文
                String bookText = extractTextFromMarkdown(Files.readString(bookFile));
                if (bookText.trim().isEmpty()) {
                    log.warn("书籍章节原文为空: {}", bookFile.getFileName());
                    continue;
                }

                // 读取对应章节大纲文件
                String chapterOutline = readChapterOutline(bookOutputPath, outlineChapter);

                // 计算题目数量（根据原文长度）
                int questionCount = calculateQuestionCount(bookText, outlineChapter);
                log.info("章节 '{}' 原文长度: {}，计算题目数: {}", outlineChapter.getTitle(), bookText.length(), questionCount);

                // 分段处理书籍原文，每段最多4000字符
                String questions = generateQuestionsInChunks(bookText, chapterOutline, fullExamOutline, outlineChapter, questionCount);

                // 保存为 题目第一章.md
                String questionFileName = "题目第" + outlineChapter.getChapterNumber() + "章.md";
                StringBuilder md = new StringBuilder();
                md.append("---\n");
                md.append("title: 题目第").append(outlineChapter.getChapterNumber()).append("章\n");
                md.append("date: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
                md.append("tags: [题目]\n");
                md.append("---\n\n");
                md.append("# 题目第").append(outlineChapter.getChapterNumber()).append("章 - ")
                        .append(outlineChapter.getTitle()).append("\n\n");
                md.append("## 考试大纲要求\n\n").append(outlineChapter.getContent()).append("\n\n");
                md.append("---\n\n");
                md.append(questions).append("\n");

                String questionFilePath = bookOutputPath + "/" + questionFileName;
                Files.writeString(Paths.get(questionFilePath), md.toString());
                log.info("题目已保存: {}", questionFilePath);

                summary.append("| 考试大纲第").append(outlineChapter.getChapterNumber()).append("章 ")
                        .append(outlineChapter.getTitle())
                        .append(" | [[").append(questionFileName).append("|题目]] |\n");

                successCount++;
            }

            // 保存题目汇总文件
            Files.writeString(Paths.get(bookOutputPath, "00_题目汇总.md"), summary.toString());
            log.info("题目汇总已保存，共生成 {} 章题目", successCount);

            // 更新书籍目录中的汇总.md，加上题目汇总链接
            updateBookSummaryWithQuestions(bookOutputPath, successCount);

            // 更新进度文件（第三阶段完成）
            updateBookProgressPhase3Complete(bookOutputPath, successCount);

            task.setStatus("completed");
            task.setProgress(100);
            task.setCurrentStep("完成");
            task.setMessage("已生成 " + successCount + " 章题目，保存到书籍目录");

        } catch (Exception e) {
            log.error("生成考试题目失败", e);
            task.setStatus("completed");
            task.setProgress(100);
            task.setCurrentStep("完成");
            task.setMessage("考试大纲解析完成，题目生成部分失败: " + e.getMessage());
        }
    }

    // 读取书籍章节文件（路径映射）
    private Map<String, Path> readBookChapterFiles(String bookOutputPath) {
        Map<String, Path> chapters = new LinkedHashMap<>();
        Path path = Paths.get(bookOutputPath);

        try (Stream<Path> paths = Files.list(path)) {
            List<Path> textFiles = paths
                    .filter(p -> p.getFileName().toString().endsWith("_原文.md"))
                    .sorted()
                    .collect(Collectors.toList());

            for (Path textFile : textFiles) {
                String fileName = textFile.getFileName().toString();
                String chapterKey = extractChapterKey(fileName);
                chapters.put(chapterKey, textFile);
                log.debug("书籍章节文件: {} -> {}", fileName, chapterKey);
            }
        } catch (IOException e) {
            log.error("读取书籍章节文件失败: {}", bookOutputPath, e);
        }

        return chapters;
    }

    // 从文件名提取章节标识（支持"第X章"和"项目X"格式）
    private String extractChapterKey(String fileName) {
        String name = fileName.replace("_原文.md", "");
        // 匹配 "第X章" 格式
        if (name.matches(".*第\\d+章.*")) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("第(\\d+)章");
            java.util.regex.Matcher matcher = pattern.matcher(name);
            if (matcher.find()) {
                return "第" + matcher.group(1) + "章";
            }
        }
        // 匹配 "项目X" 格式
        if (name.matches(".*项目\\d+.*")) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("项目(\\d+)");
            java.util.regex.Matcher matcher = pattern.matcher(name);
            if (matcher.find()) {
                return "项目" + matcher.group(1);
            }
        }
        return name;
    }

    // 查找匹配的书籍章节文件（支持"第X章"和"项目X"格式）
    private Path findMatchingBookFile(Map<String, Path> bookFiles, ExamOutlineChapter outlineChapter) {
        int outlineNum = outlineChapter.getChapterNumber();

        // 策略1：按章节编号匹配（"第X章"格式）
        String chapterKey = "第" + outlineNum + "章";
        if (bookFiles.containsKey(chapterKey)) {
            return bookFiles.get(chapterKey);
        }

        // 策略1b：按项目编号匹配（"项目X"格式）
        String projectKey = "项目" + outlineNum;
        if (bookFiles.containsKey(projectKey)) {
            return bookFiles.get(projectKey);
        }

        // 策略2：按标题相似度匹配
        for (Map.Entry<String, Path> entry : bookFiles.entrySet()) {
            if (isTitleSimilar(outlineChapter.getTitle(), entry.getKey())) {
                return entry.getValue();
            }
        }

        // 策略3：如果只有一个章节，直接返回
        if (bookFiles.size() == 1) {
            return bookFiles.values().iterator().next();
        }

        return null;
    }

    // 判断标题是否相似
    private boolean isTitleSimilar(String title1, String title2) {
        String t1 = title1.toLowerCase().replaceAll("[^\\u4e00-\\u9fa5a-z0-9]", "");
        String t2 = title2.toLowerCase().replaceAll("[^\\u4e00-\\u9fa5a-z0-9]", "");

        // 提取关键词进行比较
        if (t1.length() > 2 && t2.contains(t1.substring(0, Math.min(4, t1.length())))) {
            return true;
        }
        if (t2.length() > 2 && t1.contains(t2.substring(0, Math.min(4, t2.length())))) {
            return true;
        }

        return false;
    }

    // 从Markdown中提取正文内容
    private String extractTextFromMarkdown(String markdown) {
        // 移除YAML front matter
        String text = markdown;
        if (text.startsWith("---")) {
            int end = text.indexOf("---", 3);
            if (end > 0) {
                text = text.substring(end + 3);
            }
        }

        // 移除Markdown标题标记
        text = text.replaceAll("^#+\\s+", "");
        // 移除引用标记
        text = text.replaceAll("^>\\s+", "");
        // 移除图片链接
        text = text.replaceAll("!\\[.*?\\]\\(.*?\\)", "");
        text = text.replaceAll("!\\[\\[.*?\\]\\]", "");

        return text.trim();
    }

    // 更新书籍汇总文件，加上题目汇总链接
    private void updateBookSummaryWithQuestions(String bookOutputPath, int questionCount) {
        try {
            Path summaryFile = Paths.get(bookOutputPath, "00_汇总.md");
            if (Files.exists(summaryFile)) {
                String content = Files.readString(summaryFile);
                // 在相关文件部分添加题目汇总链接
                if (!content.contains("00_题目汇总.md")) {
                    String updated = content.replace(
                            "- [[00_目录.md|目录]]\n",
                            "- [[00_目录.md|目录]]\n" +
                            "- [[00_题目汇总.md|题目汇总]]（" + questionCount + " 章）\n"
                    );
                    Files.writeString(summaryFile, updated);
                    log.info("已更新书籍汇总文件，添加题目汇总链接");
                }
            }
        } catch (IOException e) {
            log.error("更新书籍汇总文件失败: {}", bookOutputPath, e);
        }
    }

    // 更新书籍进度文件（第二阶段跳过：无考试大纲）
    private void updateBookProgressPhase2Skipped(String bookPath) {
        try {
            Path progressFile = Paths.get(bookPath, "处理进度.md");
            if (!Files.exists(progressFile)) {
                log.warn("进度文件不存在，跳过更新: {}", bookPath);
                return;
            }

            List<String> lines = Files.readAllLines(progressFile);
            StringBuilder newContent = new StringBuilder();
            boolean inPhase2 = false;
            boolean inPhase3 = false;
            boolean phase2Updated = false;
            boolean phase3Updated = false;

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                newContent.append(line).append("\n");

                // 检测章节标题
                if (line.startsWith("## 第二阶段：")) {
                    inPhase2 = true;
                    inPhase3 = false;
                } else if (line.startsWith("## 第三阶段：")) {
                    inPhase3 = true;
                    inPhase2 = false;
                } else if (line.startsWith("## ")) {
                    inPhase2 = false;
                    inPhase3 = false;
                }

                // 第二阶段：替换状态
                if (inPhase2 && !phase2Updated) {
                    if (line.startsWith("> ") || line.startsWith("**状态**：")) {
                        newContent.delete(newContent.length() - line.length() - 1, newContent.length());
                        newContent.append("**状态**：⏭️ 已跳过（仅书籍模式，无需考试大纲）\n");
                        phase2Updated = true;
                    } else if (line.trim().isEmpty() && i > 0) {
                        // 如果当前行是空行，说明上面没有状态，直接插入
                        newContent.delete(newContent.length() - 1, newContent.length());
                        newContent.append("**状态**：⏭️ 已跳过（仅书籍模式，无需考试大纲）\n\n");
                        phase2Updated = true;
                    }
                }

                // 第三阶段：替换状态
                if (inPhase3 && !phase3Updated) {
                    if (line.startsWith("> ") || line.startsWith("**状态**：")) {
                        newContent.delete(newContent.length() - line.length() - 1, newContent.length());
                        newContent.append("**状态**：🔄 进行中...\n");
                        phase3Updated = true;
                    } else if (line.trim().isEmpty() && i > 0) {
                        newContent.delete(newContent.length() - 1, newContent.length());
                        newContent.append("**状态**：🔄 进行中...\n\n");
                        phase3Updated = true;
                    }
                }
            }

            Files.writeString(progressFile, newContent.toString());
            log.info("已更新进度文件：第二阶段跳过，第三阶段开始");
        } catch (IOException e) {
            log.error("更新进度文件失败: {}", bookPath, e);
        }
    }

    // 更新书籍进度文件（第二阶段：考试大纲任务）
    private void updateBookProgressPhase2(String bookPath, int outlineChapterCount) {
        try {
            Path progressFile = Paths.get(bookPath, "处理进度.md");
            if (Files.exists(progressFile)) {
                String content = Files.readString(progressFile);
                // 更新第二阶段状态
                content = content.replace(
                        "## 第二阶段：考试大纲任务\n\n> 待处理（上传考试大纲后自动开始）",
                        "## 第二阶段：考试大纲任务\n\n**状态**：✅ 已完成（" + outlineChapterCount + " 章）"
                );
                // 更新第三阶段状态为进行中
                content = content.replace(
                        "## 第三阶段：生成题目\n\n> 待处理（考试大纲解析完成后自动开始）",
                        "## 第三阶段：生成题目\n\n**状态**：🔄 进行中..."
                );
                Files.writeString(progressFile, content);
                log.info("已更新进度文件：第二阶段完成，第三阶段开始");
            }
        } catch (IOException e) {
            log.error("更新进度文件失败: {}", bookPath, e);
        }
    }

    // 更新书籍进度文件（第三阶段完成）
    private void updateBookProgressPhase3Complete(String bookPath, int successCount) {
        try {
            Path progressFile = Paths.get(bookPath, "处理进度.md");
            if (Files.exists(progressFile)) {
                String content = Files.readString(progressFile);
                content = content.replace(
                        "## 第三阶段：生成题目\n\n**状态**：🔄 进行中...",
                        "## 第三阶段：生成题目\n\n**状态**：✅ 已完成（" + successCount + " 章题目）"
                );
                Files.writeString(progressFile, content);
                log.info("已更新进度文件：第三阶段完成");
            }
        } catch (IOException e) {
            log.error("更新进度文件失败: {}", bookPath, e);
        }
    }

    // 计算题目数量（根据书籍原文长度决定，5-100题）
    private int calculateQuestionCount(String bookText, ExamOutlineChapter chapter) {
        // 基于原文长度计算题目数量
        int textLength = bookText != null ? bookText.length() : 0;
        int outlineLength = chapter.getContent() != null ? chapter.getContent().length() : 0;

        // 基础题数根据原文长度
        int baseCount;
        if (textLength < 1000) {
            baseCount = 5;
        } else if (textLength < 3000) {
            baseCount = 10;
        } else if (textLength < 6000) {
            baseCount = 20;
        } else if (textLength < 10000) {
            baseCount = 35;
        } else if (textLength < 20000) {
            baseCount = 50;
        } else if (textLength < 40000) {
            baseCount = 70;
        } else {
            baseCount = 100;
        }

        // 根据大纲内容微调（大纲内容越丰富，题目越多）
        if (outlineLength > 2000) {
            baseCount = Math.min(100, baseCount + 10);
        } else if (outlineLength > 1000) {
            baseCount = Math.min(100, baseCount + 5);
        }

        // 限制在 5-100 之间
        return Math.max(5, Math.min(100, baseCount));
    }

    // 分段生成题目（书籍原文分段处理）
    // 传入：原文、章节大纲、完整考试大纲、考试大纲章节信息
    private String generateQuestionsInChunks(String bookText, String chapterOutline,
                                             String fullExamOutline, ExamOutlineChapter outlineChapter,
                                             int questionCount) {
        int chunkSize = 4000; // 每段最多4000字符
        int totalLength = bookText.length();

        if (totalLength <= chunkSize) {
            // 原文较短，直接生成
            return examOutlineService.generateQuestionsFromBookAndOutline(
                    bookText, chapterOutline, fullExamOutline, outlineChapter, questionCount);
        }

        // 分段生成题目
        StringBuilder allQuestions = new StringBuilder();
        int chunkCount = (totalLength + chunkSize - 1) / chunkSize;

        log.info("书籍原文较长({}字符)，分为{}段处理", totalLength, chunkCount);

        for (int i = 0; i < chunkCount; i++) {
            int start = i * chunkSize;
            int end = Math.min(start + chunkSize, totalLength);
            String chunk = bookText.substring(start, end);

            int chunkQuestions = questionCount / chunkCount;
            if (i == chunkCount - 1) {
                chunkQuestions = questionCount - (chunkQuestions * (chunkCount - 1));
            }

            log.info("生成第{}/{}段题目 ({}字符，{}题)", i + 1, chunkCount, chunk.length(), chunkQuestions);

            String chunkQuestionsResult = examOutlineService.generateQuestionsFromBookAndOutline(
                    chunk, chapterOutline, fullExamOutline, outlineChapter, chunkQuestions);

            if (chunkQuestionsResult != null && !chunkQuestionsResult.isEmpty()) {
                allQuestions.append(chunkQuestionsResult).append("\n\n");
            }
        }

        String result = allQuestions.toString().trim();
        return result.isEmpty() ? "[题目生成失败]" : result;
    }

    // 仅根据书籍原文和大纲生成题目（不依赖考试大纲）
    private void generateQuestionsFromBookOnly(TaskInfo task, String bookOutputPath) {
        try {
            updateTaskStatus(task, "processing", 60, "生成题目", "正在根据原文和大纲生成题目...");

            // 读取书籍各章节原文
            Map<String, Path> bookChapterFiles = readBookChapterFiles(bookOutputPath);
            log.info("读取到 {} 个书籍章节文件", bookChapterFiles.size());

            int totalChapters = bookChapterFiles.size();
            int successCount = 0;

            StringBuilder summary = new StringBuilder();
            summary.append("---\n");
            summary.append("title: ").append(task.getFileName()).append(" 题目汇总\n");
            summary.append("date: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
            summary.append("tags: [题目汇总]\n");
            summary.append("---\n\n");
            summary.append("# ").append(task.getFileName()).append(" 题目汇总\n\n");
            summary.append("> 基于教材原文和章节大纲生成（无考试大纲）\n\n");
            summary.append("## 题目列表\n\n");
            summary.append("| 章节 | 题目文件 |\n");
            summary.append("|------|----------|\n");

            // 遍历每个书籍章节文件
            int chapterIndex = 0;
            for (Map.Entry<String, Path> entry : bookChapterFiles.entrySet()) {
                chapterIndex++;
                String chapterKey = entry.getKey();
                Path bookFile = entry.getValue();

                int progress = 60 + (chapterIndex * 35 / totalChapters);
                updateTaskStatus(task, "processing", progress, "生成题目",
                        String.format("正在生成第%d章题目 (%d/%d)", chapterIndex, chapterIndex, totalChapters));

                // 读取书籍原文
                String bookText = extractTextFromMarkdown(Files.readString(bookFile));
                if (bookText.trim().isEmpty()) {
                    log.warn("书籍章节原文为空: {}", bookFile.getFileName());
                    continue;
                }

                // 读取对应章节大纲文件
                String chapterOutline = readChapterOutlineByKey(bookOutputPath, chapterKey);

                // 提取章节标题
                String chapterTitle = extractChapterTitle(bookFile.getFileName().toString());

                // 计算题目数量（根据原文长度）
                int questionCount = calculateQuestionCountWithoutOutline(bookText);
                log.info("章节 '{}' 原文长度: {}，计算题目数: {}", chapterTitle, bookText.length(), questionCount);

                // 分段处理原文，每段最多4000字符
                String questions = generateQuestionsInChunksWithoutOutline(bookText, chapterOutline, chapterTitle, questionCount);

                // 保存题目文件（支持"第X章"和"项目X"格式）
                String questionFileName = chapterKey.startsWith("项目") ? "题目" + chapterKey + ".md" : "题目第" + chapterIndex + "章.md";
                String displayTitle = chapterKey.startsWith("项目") ? chapterKey : "第" + chapterIndex + "章";
                StringBuilder md = new StringBuilder();
                md.append("---\n");
                md.append("title: ").append(displayTitle).append("\n");
                md.append("date: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
                md.append("tags: [题目]\n");
                md.append("---\n\n");
                md.append("# ").append(displayTitle).append(" - ").append(chapterTitle).append("\n\n");
                md.append("## 章节知识大纲\n\n").append(chapterOutline != null ? chapterOutline : "[无大纲]").append("\n\n");
                md.append("---\n\n");
                md.append(questions).append("\n");

                String questionFilePath = bookOutputPath + "/" + questionFileName;
                Files.writeString(Paths.get(questionFilePath), md.toString());
                log.info("题目已保存: {}", questionFilePath);

                summary.append("| ").append(displayTitle).append(" ").append(chapterTitle)
                        .append(" | [[").append(questionFileName).append("|题目]] |\n");

                successCount++;
            }

            // 保存题目汇总文件
            Files.writeString(Paths.get(bookOutputPath, "00_题目汇总.md"), summary.toString());
            log.info("题目汇总已保存，共生成 {} 章题目", successCount);

            // 更新书籍目录中的汇总.md，加上题目汇总链接
            updateBookSummaryWithQuestions(bookOutputPath, successCount);

            // 更新进度文件（第三阶段完成）
            updateBookProgressPhase3Complete(bookOutputPath, successCount);

            // 创建完成标记文件
            try {
                Path finishFile = Paths.get(bookOutputPath).resolve("finish.md");
                Files.writeString(finishFile, "");
                log.info("已创建完成标记文件: {}", finishFile);
            } catch (Exception e) {
                log.warn("创建finish.md失败: {}", e.getMessage());
            }

            task.setStatus("completed");
            task.setProgress(100);
            task.setCurrentStep("完成");
            task.setMessage("已生成 " + successCount + " 章题目");

        } catch (Exception e) {
            log.error("生成题目失败", e);
            task.setStatus("completed");
            task.setProgress(100);
            task.setCurrentStep("完成");
            task.setMessage("原文和大纲生成完成，题目生成部分失败: " + e.getMessage());

            // 仍然创建完成标记文件
            try {
                Path finishFile = Paths.get(bookOutputPath).resolve("finish.md");
                Files.writeString(finishFile, "");
                log.info("已创建完成标记文件: {}", finishFile);
            } catch (Exception ex) {
                log.warn("创建finish.md失败: {}", ex.getMessage());
            }
        }
    }

    // 根据章节Key读取大纲文件
    private String readChapterOutlineByKey(String bookOutputPath, String chapterKey) {
        try {
            try (Stream<Path> paths = Files.list(Paths.get(bookOutputPath))) {
                List<Path> outlineFiles = paths
                        .filter(p -> p.getFileName().toString().endsWith("_大纲.md"))
                        .collect(Collectors.toList());

                for (Path file : outlineFiles) {
                    String fileName = file.getFileName().toString();
                    // 检查章节编号是否匹配
                    if (fileName.contains(chapterKey)) {
                        return extractTextFromMarkdown(Files.readString(file));
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.error("读取章节大纲失败: {}", chapterKey, e);
            return null;
        }
    }

    // 从文件名提取章节标题（支持"第X章"和"项目X"格式）
    private String extractChapterTitle(String fileName) {
        String name = fileName.replace("_原文.md", "");
        // 去掉 "书籍第X章_" 前缀
        name = name.replaceAll("^书籍第\\d+章_", "");
        // 去掉 "项目X_" 前缀
        name = name.replaceAll("^项目\\d+_", "");
        return name;
    }

    // 计算题目数量（仅根据原文长度，5-100题）
    private int calculateQuestionCountWithoutOutline(String bookText) {
        int textLength = bookText != null ? bookText.length() : 0;

        if (textLength < 1000) {
            return 5;
        } else if (textLength < 3000) {
            return 10;
        } else if (textLength < 6000) {
            return 20;
        } else if (textLength < 10000) {
            return 35;
        } else if (textLength < 20000) {
            return 50;
        } else if (textLength < 40000) {
            return 70;
        } else {
            return 100;
        }
    }

    // 分段生成题目（不依赖考试大纲）
    private String generateQuestionsInChunksWithoutOutline(String bookText, String chapterOutline,
                                                            String chapterTitle, int questionCount) {
        int chunkSize = 4000; // 每段最多4000字符
        int totalLength = bookText.length();

        if (totalLength <= chunkSize) {
            // 原文较短，直接生成
            return examOutlineService.generateQuestionsFromBookOnly(
                    bookText, chapterOutline, chapterTitle, questionCount);
        }

        // 分段生成题目
        StringBuilder allQuestions = new StringBuilder();
        int chunkCount = (totalLength + chunkSize - 1) / chunkSize;

        log.info("书籍原文较长({}字符)，分为{}段处理", totalLength, chunkCount);

        for (int i = 0; i < chunkCount; i++) {
            int start = i * chunkSize;
            int end = Math.min(start + chunkSize, totalLength);
            String chunk = bookText.substring(start, end);

            int chunkQuestions = questionCount / chunkCount;
            if (i == chunkCount - 1) {
                chunkQuestions = questionCount - (chunkQuestions * (chunkCount - 1));
            }

            log.info("生成第{}/{}段题目 ({}字符，{}题)", i + 1, chunkCount, chunk.length(), chunkQuestions);

            String chunkQuestionsResult = examOutlineService.generateQuestionsFromBookOnly(
                    chunk, chapterOutline, chapterTitle, chunkQuestions);

            if (chunkQuestionsResult != null && !chunkQuestionsResult.isEmpty()) {
                allQuestions.append(chunkQuestionsResult).append("\n\n");
            }
        }

        String result = allQuestions.toString().trim();
        return result.isEmpty() ? "[题目生成失败]" : result;
    }

    // 读取对应章节的大纲文件
    private String readChapterOutline(String bookOutputPath, ExamOutlineChapter outlineChapter) {
        try {
            // 构建大纲文件名：书籍第X章_xxx_大纲.md
            String outlineFileName = "书籍第" + outlineChapter.getChapterNumber() + "章_" + sanitizeForFileName(outlineChapter.getTitle()) + "_大纲.md";
            Path outlinePath = Paths.get(bookOutputPath, outlineFileName);

            if (Files.exists(outlinePath)) {
                String content = Files.readString(outlinePath);
                return extractTextFromMarkdown(content);
            }

            // 如果精确匹配失败，尝试模糊匹配
            try (Stream<Path> paths = Files.list(Paths.get(bookOutputPath))) {
                List<Path> outlineFiles = paths
                        .filter(p -> p.getFileName().toString().endsWith("_大纲.md"))
                        .collect(Collectors.toList());

                for (Path file : outlineFiles) {
                    String fileName = file.getFileName().toString();
                    // 检查章节编号是否匹配
                    if (fileName.contains("第" + outlineChapter.getChapterNumber() + "章")) {
                        return extractTextFromMarkdown(Files.readString(file));
                    }
                }
            }

            log.warn("未找到章节大纲文件: 第{}章 {}", outlineChapter.getChapterNumber(), outlineChapter.getTitle());
            return null;
        } catch (Exception e) {
            log.error("读取章节大纲失败: {}", outlineChapter.getTitle(), e);
            return null;
        }
    }

    // 清理文件名中的非法字符
    private String sanitizeForFileName(String title) {
        return title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    // 更新任务状态
    private void updateTaskStatus(TaskInfo task, String status, int progress, String step, String message) {
        task.setStatus(status);
        task.setProgress(progress);
        task.setCurrentStep(step);
        task.setMessage(message);
        task.setUpdateTime(System.currentTimeMillis());
        notifyListeners(task);
    }

    // 获取输出目录路径（使用文件名）
    private String getOutputPath(String filename) {
        String baseName = filename;
        if (filename.contains(".")) {
            baseName = filename.substring(0, filename.lastIndexOf('.'));
        }
        // 清理文件名中的非法字符
        baseName = baseName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();

        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String outputDir = System.getProperty("user.dir") + "/output/" + baseName + "_" + timestamp;

        try {
            java.nio.file.Path path = java.nio.file.Paths.get(outputDir);
            java.nio.file.Files.createDirectories(path);
        } catch (java.io.IOException e) {
            log.error("创建输出目录失败", e);
        }

        return outputDir;
    }
}
