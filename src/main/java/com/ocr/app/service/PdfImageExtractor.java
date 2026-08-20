package com.ocr.app.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class PdfImageExtractor {

    private int totalExtractedCount = 0;

    public List<ExtractedImage> extractImages(byte[] fileData, Path outputDir) throws IOException {
        totalExtractedCount = 0;
        List<ExtractedImage> images = new ArrayList<>();
        
        try (PDDocument document = Loader.loadPDF(fileData)) {
            int pageCount = document.getNumberOfPages();
            log.info("开始提取 PDF 图片，共 {} 页", pageCount);
            
            Path imagesDir = outputDir.resolve("images");
            Files.createDirectories(imagesDir);
            
            for (int i = 0; i < pageCount; i++) {
                PDPage page = document.getPage(i);
                int pageNum = i + 1;
                
                List<ExtractedImage> pageImages = extractImagesFromPageRecursive(page, pageNum, imagesDir, document);
                images.addAll(pageImages);
                
                if (!pageImages.isEmpty()) {
                    log.info("第 {} 页提取了 {} 张图片", pageNum, pageImages.size());
                }
            }
        }
        
        log.info("PDF 图片提取完成，共 {} 张图片", images.size());
        return images;
    }
    
    private List<ExtractedImage> extractImagesFromPageRecursive(PDPage page, int pageNum, Path imagesDir, PDDocument document) throws IOException {
        List<ExtractedImage> images = new ArrayList<>();
        PDResources resources = page.getResources();
        
        if (resources == null) {
            return images;
        }
        
        images.addAll(extractFromResources(resources, pageNum, imagesDir, document));
        
        return images;
    }
    
    private List<ExtractedImage> extractFromResources(PDResources resources, int pageNum, Path imagesDir, PDDocument document) throws IOException {
        List<ExtractedImage> images = new ArrayList<>();
        
        if (resources == null) {
            return images;
        }
        
        int imgIndex = 1;
        
        for (COSName name : resources.getXObjectNames()) {
            try {
                PDXObject xObject = resources.getXObject(name);
                
                if (xObject == null) {
                    continue;
                }
                
                if (xObject instanceof PDImageXObject) {
                    PDImageXObject image = (PDImageXObject) xObject;
                    
                    if (image.getWidth() < 50 || image.getHeight() < 50) {
                        log.debug("跳过太小的图片: {}x{}", image.getWidth(), image.getHeight());
                        continue;
                    }
                    
                    totalExtractedCount++;
                    String filename = String.format("page_%d_img_%03d.png", pageNum, totalExtractedCount);
                    Path imagePath = imagesDir.resolve(filename);
                    
                    try {
                        BufferedImage bufferedImage = image.getImage();
                        if (bufferedImage != null) {
                            ImageIO.write(bufferedImage, "PNG", imagePath.toFile());
                            
                            ExtractedImage extractedImage = new ExtractedImage(
                                pageNum,
                                totalExtractedCount,
                                filename,
                                "images/" + filename,
                                bufferedImage.getWidth(),
                                bufferedImage.getHeight()
                            );
                            images.add(extractedImage);
                            log.info("提取图片: {} ({}x{})", filename, bufferedImage.getWidth(), bufferedImage.getHeight());
                        }
                    } catch (Exception e) {
                        log.warn("提取图片失败: {}, 原因: {}", filename, e.getMessage());
                    }
                    
                } else if (xObject instanceof PDFormXObject) {
                    PDFormXObject form = (PDFormXObject) xObject;
                    PDResources formResources = form.getResources();
                    
                    if (formResources != null) {
                        List<ExtractedImage> formImages = extractFromResources(formResources, pageNum, imagesDir, document);
                        images.addAll(formImages);
                    }
                }
            } catch (Exception e) {
                log.debug("处理XObject失败: {}, 原因: {}", name, e.getMessage());
            }
        }
        
        return images;
    }
    
    public static class ExtractedImage {
        private final int pageNumber;
        private final int imageIndex;
        private final String filename;
        private final String relativePath;
        private final int width;
        private final int height;
        
        public ExtractedImage(int pageNumber, int imageIndex, String filename, 
                             String relativePath, int width, int height) {
            this.pageNumber = pageNumber;
            this.imageIndex = imageIndex;
            this.filename = filename;
            this.relativePath = relativePath;
            this.width = width;
            this.height = height;
        }
        
        public int getPageNumber() { return pageNumber; }
        public int getImageIndex() { return imageIndex; }
        public String getFilename() { return filename; }
        public String getRelativePath() { return relativePath; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
    }
}
