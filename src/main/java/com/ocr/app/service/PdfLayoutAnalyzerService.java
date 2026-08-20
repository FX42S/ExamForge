package com.ocr.app.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * PDF 版面分析服务
 *
 * 负责调用本地 Python 脚本 pdf_layout_analyzer.py，
 * 对 PDF 页面图片进行版面分析：提取文字 + 识别插图位置 + 裁剪插图 + 按原位置插入 Markdown。
 *
 * 仅在书籍处理阶段使用，考试大纲解析和题目生成阶段不调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfLayoutAnalyzerService {

    private final ConfigService configService;

    private static final String PYTHON_SCRIPT = "src/main/python/pdf_layout_analyzer.py";

    /**
     * 对章节图片进行版面分析，返回带插图链接的 Markdown 文本。
     *
     * @param imagePaths  章节页面图片路径列表
     * @param outputDir   输出目录，插图会保存到 outputDir/figures/
     * @param chapterTitle 章节标题，用于命名插图
     * @return 包含插图链接的 Markdown 文本
     */
    public String analyzeChapterImages(List<String> imagePaths, Path outputDir, String chapterTitle) {
        if (imagePaths == null || imagePaths.isEmpty()) {
            log.warn("章节 [{}] 没有图片，跳过版面分析", chapterTitle);
            return "";
        }

        Path scriptPath = resolveScriptPath();
        if (!scriptPath.toFile().exists()) {
            log.error("Python 版面分析脚本不存在: {}", scriptPath);
            throw new RuntimeException("Python 版面分析脚本不存在: " + scriptPath);
        }

        String configPath = resolveConfigPath();
        // Windows 路径中的反斜杠在命令行里容易出歧义，统一成正斜杠传参
        String imagesArg = imagePaths.stream()
                .map(p -> p.replace('\\', '/'))
                .collect(Collectors.joining(","));

        List<String> command = buildCommand(scriptPath, configPath, imagesArg, outputDir, chapterTitle);
        log.info("[layout-analyzer] 章节 [{}] 调用 Python 脚本，共 {} 张图片", chapterTitle, imagePaths.size());
        log.info("[layout-analyzer] 命令: {}", command);
        log.info("[layout-analyzer] 工作目录: {}", Paths.get("").toAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(command);
        // stderr 保持管道，Java 读取后写入日志，不污染 stdout
        pb.redirectErrorStream(false);
        // 强制 Python 使用 UTF-8 编码，避免 Windows 控制台 GBK 导致 UnicodeEncodeError
        pb.environment().put("PYTHONIOENCODING", "utf-8");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Process process = null;
        Future<String> stdoutFuture = null;
        Future<String> stderrFuture = null;

        try {
            process = pb.start();
            final Process capturedProcess = process;

            stdoutFuture = executor.submit(() -> readStream(capturedProcess.getInputStream()));
            stderrFuture = executor.submit(() -> readStream(capturedProcess.getErrorStream()));

            // 单页约 10-30 秒，设置最低 1200 秒超时，避免大章节被中断
            long timeoutSeconds = Math.max(1200, imagePaths.size() * 60L);
            log.info("[layout-analyzer] 等待 Python 脚本完成，超时时间 {} 秒", timeoutSeconds);

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(5, TimeUnit.SECONDS);

            if (!stderr.isBlank()) {
                log.warn("[layout-analyzer] Python stderr:\n{}", stderr);
            }

            if (!finished) {
                process.destroyForcibly();
                stdoutFuture.cancel(true);
                log.error("[layout-analyzer] Python 脚本执行超时（{} 秒），强制终止，将降级为纯 OCR", timeoutSeconds);
                // 超时返回空字符串，由上层降级为纯文字提取
                return "";
            }

            String stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
            int exitCode = process.exitValue();

            log.info("[layout-analyzer] Python 脚本退出码: {}，stdout 长度: {}", exitCode, stdout.length());
            if (stdout.length() < 500) {
                log.info("[layout-analyzer] Python stdout:\n{}", stdout);
            } else {
                log.info("[layout-analyzer] Python stdout 前 500 字符:\n{}", stdout.substring(0, 500));
            }

            if (exitCode != 0) {
                throw new RuntimeException("版面分析失败，脚本退出码: " + exitCode + "，stderr: " + stderr);
            }

            if (stdout.trim().isEmpty()) {
                throw new RuntimeException("版面分析返回空 Markdown");
            }

            return stdout;

        } catch (IOException e) {
            log.error("[layout-analyzer] 调用 Python 版面分析脚本失败", e);
            throw new RuntimeException("调用 Python 版面分析脚本失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[layout-analyzer] Python 版面分析脚本被中断", e);
            throw new RuntimeException("Python 版面分析脚本被中断", e);
        } catch (ExecutionException | TimeoutException e) {
            log.error("[layout-analyzer] 读取 Python 输出失败，将降级为纯 OCR", e);
            // 读取输出失败时返回空字符串，由上层降级为纯文字提取
            return "";
        } finally {
            executor.shutdownNow();
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String readStream(java.io.InputStream stream) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    /**
     * 检测 Python 可执行文件
     */
    private String detectPythonExecutable() {
        String[] candidates = {"python3", "python", "py"};
        for (String candidate : candidates) {
            try {
                ProcessBuilder pb = new ProcessBuilder(candidate, "--version");
                Process process = pb.start();
                boolean finished = process.waitFor(5, TimeUnit.SECONDS);
                if (finished && process.exitValue() == 0) {
                    log.info("[layout-analyzer] 检测到 Python 命令: {}", candidate);
                    return candidate;
                }
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            } catch (Exception e) {
                log.debug("[layout-analyzer] 检测 Python 命令 {} 失败: {}", candidate, e.getMessage());
            }
        }
        log.warn("[layout-analyzer] 未检测到可用的 Python 命令，默认使用 python");
        return "python";
    }

    private List<String> buildCommand(Path scriptPath, String configPath, String imagesArg,
                                      Path outputDir, String chapterTitle) {
        String python = detectPythonExecutable();
        List<String> cmd = new ArrayList<>();
        cmd.add(python);
        cmd.add(scriptPath.toString().replace('\\', '/'));
        cmd.add("--images");
        cmd.add(imagesArg);
        cmd.add("--output-dir");
        cmd.add(outputDir.toString().replace('\\', '/'));
        cmd.add("--config");
        cmd.add(configPath.replace('\\', '/'));
        cmd.add("--chapter-title");
        cmd.add(sanitizeForCommand(chapterTitle));
        return cmd;
    }

    private Path resolveScriptPath() {
        // 1. 从运行目录解析相对路径
        Path current = Paths.get("").toAbsolutePath();
        Path relative = current.resolve(PYTHON_SCRIPT).normalize();
        if (relative.toFile().exists()) {
            log.info("[layout-analyzer] 找到脚本（运行目录）: {}", relative);
            return relative;
        }

        // 2. 兼容从 jar 包运行：当前目录是 target，上级是项目根
        Path parentOfTarget = current.getParent();
        if (parentOfTarget != null) {
            Path fromProjectRoot = parentOfTarget.resolve(PYTHON_SCRIPT).normalize();
            if (fromProjectRoot.toFile().exists()) {
                log.info("[layout-analyzer] 找到脚本（项目根目录）: {}", fromProjectRoot);
                return fromProjectRoot;
            }
        }

        // 3. 兼容 class path 资源目录
        Path classPath = Paths.get(PdfLayoutAnalyzerService.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath()).toAbsolutePath();
        log.debug("[layout-analyzer] class path 位置: {}", classPath);
        if (classPath.toString().endsWith("target/classes")) {
            Path fromClasses = classPath.getParent().resolve(PYTHON_SCRIPT).normalize();
            if (fromClasses.toFile().exists()) {
                log.info("[layout-analyzer] 找到脚本（target/classes 上级）: {}", fromClasses);
                return fromClasses;
            }
        }

        log.warn("[layout-analyzer] 未找到脚本，使用默认路径: {}", relative);
        return relative;
    }

    private String resolveConfigPath() {
        String configFilePath = configService.getConfigFilePath();
        Path path = Paths.get(configFilePath);
        Path resolved;
        if (path.isAbsolute()) {
            resolved = path;
        } else {
            resolved = Paths.get("").toAbsolutePath().resolve(path).normalize();
        }
        log.info("[layout-analyzer] 配置文件路径: {}", resolved);
        return resolved.toString();
    }

    private String sanitizeForCommand(String title) {
        if (title == null) {
            return "章节";
        }
        return title.replaceAll("[\\r\\n\\t]", " ").trim();
    }
}
