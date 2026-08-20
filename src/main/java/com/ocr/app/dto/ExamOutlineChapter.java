package com.ocr.app.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamOutlineChapter {
    private int chapterNumber;       // 章节编号
    private String title;            // 章节标题
    private String content;          // 章节内容/要求
    private int startPage;           // 在PDF中的起始页
    private int endPage;             // 在PDF中的结束页
}
