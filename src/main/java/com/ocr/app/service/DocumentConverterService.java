package com.ocr.app.service;

import com.ocr.app.service.converter.PdfConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * 文档转换服务 - 仅支持PDF
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentConverterService {
    
    /**
     * 将 PDF 转换为图片并保存到指定目录
     * @param fileData PDF 文件字节数组
     * @param filename 文件名
     * @param outputDir 输出目录
     * @return 生成的图片文件路径列表
     * @throws Exception 转换异常
     */
    public java.util.List<String> convertToImagesAndSave(byte[] fileData, String filename, Path outputDir) throws Exception {
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("该方法仅支持 PDF 文件: " + filename);
        }
        
        PdfConverter pdfConverter = new PdfConverter();
        return pdfConverter.convertToImagesAndSave(fileData, outputDir, filename);
    }
}
