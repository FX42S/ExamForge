package com.ocr.app.controller;

import com.ocr.app.dto.ApiResponse;
import com.ocr.app.service.FastQuestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@RestController
@RequestMapping("/api/fast")
@CrossOrigin(origins = "*")
public class FastQuestionController {

    private final FastQuestionService fastQuestionService;

    // 任务队列（依次处理）- 使用静态变量确保服务器重启前队列不丢失
    private static final BlockingQueue<FastTask> taskQueue = new LinkedBlockingQueue<>();
    private static final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private static final Map<String, FastTaskStatus> taskStatusMap = new ConcurrentHashMap<>();
    private static volatile boolean processorStarted = false;

    public FastQuestionController(FastQuestionService fastQuestionService) {
        this.fastQuestionService = fastQuestionService;
        // 启动队列处理器（只启动一次）
        startQueueProcessor();
    }

    /**
     * 启动队列处理器，依次处理任务
     * 使用静态变量确保只启动一次，关浏览器不影响后台处理
     */
    private synchronized void startQueueProcessor() {
        if (processorStarted) {
            log.info("【快速题目生成】队列处理器已在运行");
            return;
        }
        processorStarted = true;

        executorService.submit(() -> {
            log.info("【快速题目生成】队列处理器已启动（后台运行，关闭浏览器不影响）");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    FastTask task = taskQueue.take();
                    log.info("【队列】开始处理任务: {}, 队列剩余: {} 个", task.filename, taskQueue.size());

                    FastTaskStatus status = taskStatusMap.get(task.taskId);
                    if (status != null) {
                        status.status = "processing";
                        status.message = "正在处理...";
                    }

                    try {
                        String outputPath = fastQuestionService.generateQuestionsFromPdf(task.fileData, task.filename);

                        if (status != null) {
                            status.status = "completed";
                            status.message = "处理完成";
                            status.outputPath = outputPath;
                            status.progress = 100;
                        }
                        log.info("【队列】任务完成: {}", task.filename);
                    } catch (Exception e) {
                        log.error("【队列】任务处理失败: {}", task.filename, e);
                        if (status != null) {
                            status.status = "failed";
                            status.message = "处理失败: " + e.getMessage();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.info("【快速题目生成】队列处理器已停止");
        });
    }

    /**
     * 提交快速生成任务（加入队列）
     */
    @PostMapping("/generate")
    public ApiResponse<FastTaskStatus> submitTask(
            @RequestParam("file") MultipartFile file,
            @RequestParam("filename") String fileName) {
        try {
            if (file.isEmpty()) {
                return ApiResponse.error("文件不能为空");
            }

            String taskId = UUID.randomUUID().toString();
            byte[] fileData = file.getBytes();

            FastTask task = new FastTask(taskId, fileName, fileData);
            FastTaskStatus status = new FastTaskStatus(taskId, fileName);

            taskStatusMap.put(taskId, status);
            taskQueue.offer(task);

            log.info("【提交】任务已加入队列: {}, 任务ID: {}, 队列当前: {} 个", fileName, taskId, taskQueue.size());

            status.status = "pending";
            status.message = "等待处理，队列位置: " + taskQueue.size();
            status.queuePosition = taskQueue.size();

            return ApiResponse.success("任务已加入队列", status);
        } catch (Exception e) {
            log.error("【提交】任务提交失败: {}", fileName, e);
            return ApiResponse.error("提交失败: " + e.getMessage());
        }
    }

    /**
     * 批量提交快速生成任务
     */
    @PostMapping("/generate/batch")
    public ApiResponse<List<FastTaskStatus>> submitBatchTasks(
            @RequestParam("files") MultipartFile[] files) {
        try {
            if (files == null || files.length == 0) {
                return ApiResponse.error("文件不能为空");
            }

            List<FastTaskStatus> results = new ArrayList<>();
            for (MultipartFile file : files) {
                if (file.isEmpty()) continue;

                String taskId = UUID.randomUUID().toString();
                byte[] fileData = file.getBytes();
                String fileName = file.getOriginalFilename();

                FastTask task = new FastTask(taskId, fileName, fileData);
                FastTaskStatus status = new FastTaskStatus(taskId, fileName);

                taskStatusMap.put(taskId, status);
                taskQueue.offer(task);

                status.status = "pending";
                status.message = "等待处理，队列位置: " + taskQueue.size();
                status.queuePosition = taskQueue.size();
                results.add(status);

                log.info("【批量提交】任务已加入队列: {}, 任务ID: {}", fileName, taskId);
            }

            log.info("【批量提交】共提交 {} 个任务，队列当前: {} 个", results.size(), taskQueue.size());
            return ApiResponse.success("已提交 " + results.size() + " 个任务", results);
        } catch (Exception e) {
            log.error("【批量提交】任务提交失败", e);
            return ApiResponse.error("提交失败: " + e.getMessage());
        }
    }

    /**
     * 查询任务状态
     */
    @GetMapping("/task/{taskId}")
    public ApiResponse<FastTaskStatus> getTaskStatus(@PathVariable String taskId) {
        FastTaskStatus status = taskStatusMap.get(taskId);
        if (status == null) {
            return ApiResponse.error("任务不存在");
        }
        // 更新队列位置
        if ("pending".equals(status.status)) {
            int position = 1;
            for (FastTask task : taskQueue) {
                if (task.taskId.equals(taskId)) {
                    status.queuePosition = position;
                    status.message = "等待处理，队列位置: " + position;
                    break;
                }
                position++;
            }
        }
        return ApiResponse.success(status);
    }

    /**
     * 获取所有任务状态
     */
    @GetMapping("/tasks")
    public ApiResponse<List<FastTaskStatus>> getAllTasks() {
        return ApiResponse.success(new ArrayList<>(taskStatusMap.values()));
    }

    /**
     * 下载生成的题目文件
     */
    @GetMapping("/download")
    public ResponseEntity<Resource> downloadFile(@RequestParam("path") String filePath) {
        try {
            Path path = Paths.get(filePath);
            File file = path.toFile();

            if (!file.exists()) {
                return ResponseEntity.notFound().build();
            }

            Resource resource = new FileSystemResource(file);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(resource);
        } catch (Exception e) {
            log.error("下载文件失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 根据任务ID下载生成的题目文件
     */
    @GetMapping("/download/{taskId}")
    public ResponseEntity<Resource> downloadByTaskId(@PathVariable String taskId) {
        try {
            FastTaskStatus status = taskStatusMap.get(taskId);
            if (status == null) {
                return ResponseEntity.notFound().build();
            }

            if (status.outputPath == null || status.outputPath.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Path path = Paths.get(status.outputPath);
            File file = path.toFile();

            if (!file.exists()) {
                return ResponseEntity.notFound().build();
            }

            Resource resource = new FileSystemResource(file);
            String filename = file.getName();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(resource);
        } catch (Exception e) {
            log.error("下载文件失败: {}", taskId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 获取Fast队列统计
     */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getStats() {
        int waiting = 0;
        int processing = 0;
        int completed = 0;
        int failed = 0;

        for (FastTaskStatus status : taskStatusMap.values()) {
            switch (status.status) {
                case "pending":
                    waiting++;
                    break;
                case "processing":
                    processing++;
                    break;
                case "completed":
                    completed++;
                    break;
                case "failed":
                    failed++;
                    break;
            }
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("waiting", waiting);
        stats.put("processing", processing);
        stats.put("completed", completed);
        stats.put("failed", failed);
        stats.put("queueSize", taskQueue.size());

        return ApiResponse.success(stats);
    }

    // 任务对象
    private static class FastTask {
        String taskId;
        String filename;
        byte[] fileData;

        FastTask(String taskId, String filename, byte[] fileData) {
            this.taskId = taskId;
            this.filename = filename;
            this.fileData = fileData;
        }
    }

    // 任务状态（返回给前端）
    public static class FastTaskStatus {
        private String taskId;
        private String filename;
        private String status; // pending, processing, completed, failed
        private String message;
        private int progress;
        private String outputPath;
        private int queuePosition;
        private long submitTime;

        public FastTaskStatus(String taskId, String filename) {
            this.taskId = taskId;
            this.filename = filename;
            this.status = "pending";
            this.message = "等待处理";
            this.progress = 0;
            this.submitTime = System.currentTimeMillis();
        }

        // Getters and Setters
        public String getTaskId() { return taskId; }
        public void setTaskId(String taskId) { this.taskId = taskId; }
        public String getFilename() { return filename; }
        public void setFilename(String filename) { this.filename = filename; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public int getProgress() { return progress; }
        public void setProgress(int progress) { this.progress = progress; }
        public String getOutputPath() { return outputPath; }
        public void setOutputPath(String outputPath) { this.outputPath = outputPath; }
        public int getQueuePosition() { return queuePosition; }
        public void setQueuePosition(int queuePosition) { this.queuePosition = queuePosition; }
        public long getSubmitTime() { return submitTime; }
        public void setSubmitTime(long submitTime) { this.submitTime = submitTime; }
    }
}
