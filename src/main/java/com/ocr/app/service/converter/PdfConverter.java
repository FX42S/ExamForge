package com.ocr.app.service.converter;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * PDF 文档转换器
 */
@Slf4j
@Component
public class PdfConverter {
    
    private static final List<String> SUPPORTED_EXTENSIONS = List.of("pdf");
    private static final int DPI = 200;
    
    /**
     * 检测是否支持该文件
     */
    public boolean supports(String filename) {
        String ext = getExtension(filename);
        return SUPPORTED_EXTENSIONS.contains(ext);
    }
    
    /**
     * 将 PDF 转换为图片并保存到指定目录
     * @param fileData PDF 文件字节数组
     * @param outputDir 输出目录
     * @param filename 文件名
     * @return 生成的图片文件路径列表（按页码顺序）
     * @throws Exception 转换异常
     */
    public List<String> convertToImagesAndSave(byte[] fileData, Path outputDir, String filename) throws Exception {
        List<String> imagePaths = new ArrayList<>();
        
        Path tempImageDir = outputDir.resolve("tempimage");
        Files.createDirectories(tempImageDir);
        
        try (PDDocument document = Loader.loadPDF(fileData)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pageCount = document.getNumberOfPages();
            
            log.info("PDF 总页数: {}，保存到: {}", pageCount, tempImageDir);
            
            for (int i = 0; i < pageCount; i++) {
                BufferedImage image = null;
                boolean rendered = false;

                try {
                    image = renderer.renderImageWithDPI(i, DPI);
                    rendered = true;
                } catch (UnsupportedOperationException e) {
                    log.warn("PDF 第 {} 页字体渲染失败，使用文本提取兜底方案", i + 1);
                    image = renderPageAsText(document, i);
                    if (image != null) rendered = true;
                } catch (Exception e) {
                    log.error("PDF 第 {} 页渲染异常，尝试文本兜底", i + 1, e);
                    image = renderPageAsText(document, i);
                    if (image != null) rendered = true;
                }

                if (rendered && image != null) {
                    String imageName = String.format("page_%04d.png", i + 1);
                    Path imagePath = tempImageDir.resolve(imageName);
                    ImageIO.write(image, "PNG", imagePath.toFile());
                    imagePaths.add(imagePath.toString());
                }

                if ((i + 1) % 10 == 0 || i == pageCount - 1) {
                    log.info("PDF 转换进度: {}/{} 页 (成功: {} 页)", i + 1, pageCount, imagePaths.size());
                }
            }
        }
        
        log.info("PDF 转图片完成，共 {} 张图片", imagePaths.size());
        return imagePaths;
    }
    
    /**
     * 将 PDF 转换为 Base64 图片列表
     * @param fileData PDF 文件字节数组
     * @return Base64 图片列表
     * @throws Exception 转换异常
     */
    public List<String> convertToImages(byte[] fileData) throws Exception {
        List<String> base64Images = new ArrayList<>();
        
        try (PDDocument document = Loader.loadPDF(fileData)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pageCount = document.getNumberOfPages();
            
            log.info("PDF 总页数: {}", pageCount);
            
            for (int i = 0; i < pageCount; i++) {
                BufferedImage image = null;
                boolean rendered = false;

                try {
                    image = renderer.renderImageWithDPI(i, DPI);
                    rendered = true;
                } catch (UnsupportedOperationException e) {
                    log.warn("PDF 第 {} 页字体渲染失败，使用文本提取兜底方案", i + 1);
                    image = renderPageAsText(document, i);
                    if (image != null) rendered = true;
                } catch (Exception e) {
                    log.error("PDF 第 {} 页渲染异常，尝试文本兜底", i + 1, e);
                    image = renderPageAsText(document, i);
                    if (image != null) rendered = true;
                }

                if (rendered && image != null) {
                    String base64 = bufferedImageToBase64(image);
                    base64Images.add(base64);
                    log.info("PDF 第 {} 页转换完成", i + 1);
                }
            }
        }
        
        return base64Images;
    }
    
    public String getConverterName() {
        return "PDF Converter";
    }
    
    public List<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }
    
    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }
    
    /**
     * 文本兜底方案：当字体渲染失败时，提取页面文本并绘制成图片
     */
    private BufferedImage renderPageAsText(PDDocument document, int pageIndex) {
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            String text = stripper.getText(document);

            if (text == null || text.trim().isEmpty()) {
                log.warn("PDF 第 {} 页文本提取为空", pageIndex + 1);
                return createPlaceholderImage("第 " + (pageIndex + 1) + " 页\n(无文本内容)");
            }

            return createTextImage(text, pageIndex + 1);
        } catch (Exception e) {
            log.error("PDF 第 {} 页文本提取也失败了", pageIndex + 1, e);
            return createPlaceholderImage("第 " + (pageIndex + 1) + " 页\n(提取失败)");
        }
    }

    /**
     * 将文本绘制成图片（A4 比例，白色背景）
     */
    private BufferedImage createTextImage(String text, int pageNumber) {
        int width = 1654;  // A4 @ 200 DPI
        int height = 2339;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();

        // 白色背景
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);

        // 绘制页码
        g.setColor(Color.GRAY);
        g.setFont(new Font("SimSun", Font.PLAIN, 36));
        g.drawString("第 " + pageNumber + " 页 (文本模式)", 60, 60);

        // 绘制分隔线
        g.drawLine(60, 80, width - 60, 80);

        // 绘制文本内容
        g.setColor(Color.BLACK);
        g.setFont(new Font("SimSun", Font.PLAIN, 28));

        String[] lines = text.split("\n");
        int x = 60;
        int y = 130;
        int lineHeight = 40;
        int maxLines = (height - 160) / lineHeight;

        for (int i = 0; i < lines.length && i < maxLines; i++) {
            String line = lines[i];
            if (line.length() > 80) {
                line = line.substring(0, 80) + "...";
            }
            g.drawString(line, x, y);
            y += lineHeight;
        }

        if (lines.length > maxLines) {
            g.setColor(Color.GRAY);
            g.drawString("... (还有 " + (lines.length - maxLines) + " 行文本未显示)", x, y);
        }

        g.dispose();
        return image;
    }

    /**
     * 创建占位图片
     */
    private BufferedImage createPlaceholderImage(String message) {
        int width = 1654;
        int height = 2339;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.GRAY);
        g.setFont(new Font("SimSun", Font.BOLD, 48));

        String[] lines = message.split("\n");
        int y = height / 2 - (lines.length * 30);
        for (String line : lines) {
            int strWidth = g.getFontMetrics().stringWidth(line);
            g.drawString(line, (width - strWidth) / 2, y);
            y += 70;
        }

        g.dispose();
        return image;
    }

    private String bufferedImageToBase64(BufferedImage image) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }
}
