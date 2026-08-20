package com.ocr.app.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskInfo {
    private String taskId;           // 任务ID
    private String fileName;         // 文件名
    private String taskType;         // 任务类型: book(书籍), outline(考试大纲)
    private String status;           // pending, processing, completed, failed
    private int progress;             // 进度 0-100
    private String currentStep;      // 当前步骤
    private String message;          // 状态消息
    private String errorMessage;     // 错误信息
    private long createTime;          // 创建时间
    private long updateTime;         // 更新时间
    private int chaptersCount;       // 章节数量
    private String outputPath;       // 输出路径
    private String relatedTaskId;    // 关联任务ID（考试大纲关联的书籍任务）
}
