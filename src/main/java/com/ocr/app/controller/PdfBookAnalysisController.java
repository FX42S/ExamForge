package com.ocr.app.controller;

import com.ocr.app.dto.ApiResponse;
import com.ocr.app.dto.PdfAnalysisResponse;
import com.ocr.app.service.PdfBookAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/api/pdfbook")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "PDF 书籍分析", description = "上传 PDF 书籍，AI 自动提取章节、生成知识大纲和练习题")
public class PdfBookAnalysisController {

    private final PdfBookAnalysisService pdfBookAnalysisService;

    /**
     * 分析 PDF 书籍
     */
    @Operation(
            summary = "分析 PDF 书籍",
            description = """
                    上传 PDF 书籍文件，系统将：
                    1. 提取章节结构
                    2. 为每个章节生成核心知识大纲
                    3. 根据知识点生成练习题（包含题目、答案、知识点、难度）
                    4. 所有结果保存为本地 TXT 文件

                    支持的文件格式：PDF
                    输出位置：程序运行目录下的 output 文件夹
                    """
    )
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "分析成功",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResponse.class))
            )
    })
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PdfAnalysisResponse> analyzePdfBook(
            @Parameter(description = "PDF 书籍文件", required = true)
            @RequestParam("file") MultipartFile file) {
        log.info("收到 PDF 书籍分析请求: {}", file.getOriginalFilename());

        if (file.isEmpty()) {
            return ApiResponse.error("文件不能为空");
        }

        String filename = file.getOriginalFilename();
        if (filename == null) {
            return ApiResponse.error("文件名不能为空");
        }

        // 检查文件类型
        if (!filename.toLowerCase().endsWith(".pdf")) {
            return ApiResponse.error("仅支持 PDF 格式的书籍文件");
        }

        try {
            // 读取文件内容
            byte[] fileData = file.getBytes();

            // 调用分析服务
            PdfAnalysisResponse response = pdfBookAnalysisService.analyzePdfBook(fileData, filename);

            if (response.isSuccess()) {
                log.info("PDF 分析完成，生成了 {} 个文件", response.getGeneratedFiles().size());
                return ApiResponse.ok(response);
            } else {
                return ApiResponse.error(response.getError());
            }

        } catch (Exception e) {
            log.error("PDF 分析失败", e);
            return ApiResponse.error("分析失败: " + e.getMessage());
        }
    }
}
