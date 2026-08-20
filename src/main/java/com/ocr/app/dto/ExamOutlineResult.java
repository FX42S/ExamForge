package com.ocr.app.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamOutlineResult {
    private boolean success;
    private String filename;
    private String error;
    private List<ExamOutlineChapter> chapters;
    private String outputPath;
    private String fullText;         // 完整文本内容
}
