package com.ocr.app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@Schema(description = "PDF 分析结果")
public class PdfAnalysisResponse {

    @Schema(description = "是否处理成功", example = "true")
    private boolean success;

    @Schema(description = "错误信息（处理失败时）", example = "处理失败: 网络超时")
    private String error;

    @Schema(description = "文件名", example = "Java编程思想.pdf")
    private String filename;

    @Schema(description = "输出目录路径")
    private String outputPath;

    @Schema(description = "章节列表")
    private List<ChapterInfo> chapters;

    @Schema(description = "生成的文件路径列表")
    private List<String> generatedFiles;

    @Data
    @Builder
    @Schema(description = "章节信息")
    public static class ChapterInfo {
        @Schema(description = "章节编号", example = "1")
        private int chapterNumber;

        @Schema(description = "章节标题", example = "第一章：对象导论")
        private String title;

        @Schema(description = "页码范围", example = "1-25")
        private String pageRange;

        @Schema(description = "该章节的知识点数量", example = "5")
        private int knowledgePointCount;
    }
}
