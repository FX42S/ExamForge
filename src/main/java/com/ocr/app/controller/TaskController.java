package com.ocr.app.controller;

import com.ocr.app.dto.ApiResponse;
import com.ocr.app.dto.TaskInfo;
import com.ocr.app.service.ExamOutlineService;
import com.ocr.app.service.PdfBookAnalysisService;
import com.ocr.app.service.TaskQueueService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@RestController
@RequestMapping("/api/tasks")
@CrossOrigin(origins = "*")
public class TaskController {

    private final TaskQueueService taskQueueService;
    private final PdfBookAnalysisService pdfBookAnalysisService;
    private final ExamOutlineService examOutlineService;

    // SSE发射器列表
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    // SSE超时时间（30分钟）
    private static final long SSE_TIMEOUT = 30 * 60 * 1000L;

    // 任务队列监听器
    private TaskQueueService.TaskListener taskListener;

    public TaskController(TaskQueueService taskQueueService, 
                         PdfBookAnalysisService pdfBookAnalysisService,
                         ExamOutlineService examOutlineService) {
        this.taskQueueService = taskQueueService;
        this.pdfBookAnalysisService = pdfBookAnalysisService;
        this.examOutlineService = examOutlineService;

        // 创建监听器
        this.taskListener = new TaskQueueService.TaskListener() {
            @Override
            public void onTaskUpdate(TaskInfo task) {
                broadcast(task);
            }
        };

        // 注册监听器
        this.taskQueueService.addListener(this.taskListener);

        // 启动队列处理器
        this.taskQueueService.startProcessing(this.pdfBookAnalysisService, this.examOutlineService);
    }

    /**
     * 订阅任务进度（SSE）
     */
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        
        emitters.add(emitter);
        
        emitter.onCompletion(() -> {
            emitters.remove(emitter);
            log.debug("SSE连接完成");
        });
        
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            log.debug("SSE连接超时");
        });
        
        emitter.onError(e -> {
            emitters.remove(emitter);
            log.debug("SSE连接错误: {}", e.getMessage());
        });

        // 发送初始连接成功消息
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("{\"type\":\"connected\",\"message\":\"已连接\"}"));
        } catch (IOException e) {
            log.error("发送SSE初始消息失败", e);
        }

        // 发送当前所有任务状态
        try {
            List<TaskInfo> tasks = taskQueueService.getAllTasks();
            emitter.send(SseEmitter.event()
                    .name("init")
                    .data("{\"type\":\"init\",\"tasks\":" + toJson(tasks) + "}"));
        } catch (IOException e) {
            log.error("发送初始任务列表失败", e);
        }

        return emitter;
    }

    /**
     * 广播消息到所有SSE连接
     */
    private void broadcast(TaskInfo task) {
        String json = toJson(task);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("taskUpdate")
                        .data(json));
            } catch (IOException e) {
                emitters.remove(emitter);
                log.debug("广播消息失败，移除连接");
            }
        }
    }

    /**
     * 提交书籍分析任务
     */
    @PostMapping("/submit/book")
    public ApiResponse<TaskInfo> submitBookTask(@RequestParam("file") MultipartFile file,
                                                 @RequestParam("filename") String fileName) {
        try {
            // 检查文件数量限制
            int pending = taskQueueService.getPendingCount();
            int processing = taskQueueService.getProcessingCount();
            
            if (pending + processing >= 50) {
                return ApiResponse.error("等待处理的任务过多，请稍后再试");
            }

            // 获取文件数据
            byte[] fileData = file.getBytes();
            
            // 提交书籍任务
            TaskInfo task = taskQueueService.submitBookTask(fileName, fileData);

            return ApiResponse.success("书籍任务已提交", task);
        } catch (Exception e) {
            log.error("提交书籍任务失败", e);
            return ApiResponse.error("提交失败: " + e.getMessage());
        }
    }

    /**
     * 提交仅书籍任务（无需考试大纲，自动根据原文和大纲生成题目）
     */
    @PostMapping("/submit/book-only")
    public ApiResponse<TaskInfo> submitBookOnlyTask(@RequestParam("file") MultipartFile file,
                                                     @RequestParam("filename") String fileName) {
        try {
            int pending = taskQueueService.getPendingCount();
            int processing = taskQueueService.getProcessingCount();

            if (pending + processing >= 50) {
                return ApiResponse.error("等待处理的任务过多，请稍后再试");
            }

            byte[] fileData = file.getBytes();

            TaskInfo task = taskQueueService.submitBookOnlyTask(fileName, fileData);

            return ApiResponse.success("书籍任务已提交（仅书籍模式，自动生成题目）", task);
        } catch (Exception e) {
            log.error("提交书籍任务失败", e);
            return ApiResponse.error("提交失败: " + e.getMessage());
        }
    }

    /**
     * 提交考试大纲解析任务
     */
    @PostMapping("/submit/outline")
    public ApiResponse<TaskInfo> submitOutlineTask(@RequestParam("file") MultipartFile file,
                                                    @RequestParam("filename") String fileName,
                                                    @RequestParam(value = "relatedTaskId", required = false) String relatedTaskId) {
        try {
            // 检查文件数量限制
            int pending = taskQueueService.getPendingCount();
            int processing = taskQueueService.getProcessingCount();
            
            if (pending + processing >= 50) {
                return ApiResponse.error("等待处理的任务过多，请稍后再试");
            }

            // 获取文件数据
            byte[] fileData = file.getBytes();
            
            // 提交考试大纲任务
            TaskInfo task = taskQueueService.submitOutlineTask(fileName, fileData, relatedTaskId);

            return ApiResponse.success("考试大纲任务已提交", task);
        } catch (Exception e) {
            log.error("提交考试大纲任务失败", e);
            return ApiResponse.error("提交失败: " + e.getMessage());
        }
    }

    /**
     * 获取所有任务
     */
    @GetMapping("/list")
    public ApiResponse<List<TaskInfo>> getAllTasks() {
        List<TaskInfo> tasks = taskQueueService.getAllTasks();
        return ApiResponse.success(tasks);
    }

    /**
     * 删除任务
     */
    @DeleteMapping("/{taskId}")
    public ApiResponse<Void> removeTask(@PathVariable String taskId) {
        boolean removed = taskQueueService.removeTask(taskId);
        if (removed) {
            return ApiResponse.success("任务已删除", null);
        } else {
            return ApiResponse.error("任务不存在或无法删除");
        }
    }

    /**
     * 清空已完成任务
     */
    @DeleteMapping("/completed")
    public ApiResponse<Void> clearCompleted() {
        taskQueueService.clearCompletedTasks();
        return ApiResponse.success("已完成任务已清空", null);
    }

    /**
     * 获取任务统计
     */
    @GetMapping("/stats")
    public ApiResponse<Object> getStats() {
        return ApiResponse.success(java.util.Map.of(
            "pending", taskQueueService.getPendingCount(),
            "processing", taskQueueService.getProcessingCount()
        ));
    }

    /**
     * 简单的JSON转换
     */
    private String toJson(Object obj) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = 
                new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
