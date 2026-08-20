# 智能题库生成系统

基于 Spring Boot + LM Studio 的 PDF 智能分析系统，支持从 PDF 书籍/考试大纲自动生成题目。

## 技术栈

- **后端**: Spring Boot 3.2.x + JDK 21 + PDFBox
- **前端**: HTML + CSS + JavaScript (原生，无框架)
- **AI 引擎**: LM Studio (OpenAI 兼容 API)
- **构建工具**: Maven

## 核心功能

系统提供三种处理模式：

| 模式 | 页面 | 输入 | 输出 |
|------|------|------|------|
| **完整三阶段** | `/pdfbook.html` | PDF 书籍 + PDF 考纲 | 原文、大纲、题目 |
| **仅书籍模式** | `/only.html` | PDF 书籍 | 原文、大纲、题目（无考纲） |
| **快速题目模式** | `/fast.html` | PDF 考纲 | 直接生成 500 道题目 |

## 快速启动

### 1. 配置 AI 模型

编辑项目根目录 `config.json`：

```json
{
  "lmstudio.base.url": "http://localhost:1234",
  "lmstudio.api.path": "/v1/chat/completions",
  "lmstudio.model.name": "qwen/qwen3.5-9b"
}
```

### 2. 启动 LM Studio

确保 LM Studio 已启动并加载模型，API 可访问。

### 3. 运行项目

```bash
mvn spring-boot:run
```

访问 http://localhost:8188

## 前端页面

| 页面 | 地址 | 说明 |
|------|------|------|
| PDF 书籍+考纲 | http://localhost:8188/pdfbook.html | 完整三阶段处理 |
| 仅书籍模式 | http://localhost:8188/only.html | 自动跳过考纲阶段 |
| 快速题目生成 | http://localhost:8188/fast.html | 上传考纲直接出 500 题 |

## 输出目录

所有处理结果保存到 `output/` 目录：

```
output/
├── 数据结构_20260101_120000/        # 书籍+考纲 / 仅书籍模式
│   ├── 00_目录.md
│   ├── 00_汇总.md
│   ├── 00_题目汇总.md
│   ├── 处理进度.md
│   ├── finish.md                    # 完成标记文件
│   ├── 书籍第1章_xxx_原文.md
│   ├── 书籍第1章_xxx_大纲.md
│   ├── 题目第1章.md
│   └── ...
└── 数据结构考试大纲/                  # 快速题目模式
    ├── 数据结构考试大纲.md
    └── finish.md
```

## 任务队列

- 后端使用单线程队列依次处理任务
- pdfbook / only 模式最多排队 **50 个任务**
- fast 模式最多排队 **10 个任务**
- 支持后台运行，关闭浏览器不影响处理

## API 接口

系统已对外提供完整的 REST API，详见 [api-documentation.md](api-documentation.md)。

## 项目文档

| 文档 | 说明 |
|------|------|
| [README.md](README.md) | 项目简介与快速启动 |
| [api-documentation.md](api-documentation.md) | 完整 API 接口文档 |
| [workflows.md](workflows.md) | 各模式详细工作流程 |
| [development-guide.md](development-guide.md) | 开发指南 |
| [troubleshooting.md](troubleshooting.md) | 故障排查手册 |
| [database-schema.md](database-schema.md) | 数据库表结构 |

## 目录结构

```
orc-app/
├── src/main/java/com/ocr/app/
│   ├── OcrApplication.java
│   ├── config/
│   │   └── WebConfig.java
│   ├── controller/
│   │   ├── FastQuestionController.java
│   │   ├── HealthController.java
│   │   ├── PdfBookAnalysisController.java
│   │   └── TaskController.java
│   ├── dto/
│   │   ├── ApiResponse.java
│   │   ├── ExamOutlineChapter.java
│   │   ├── ExamOutlineResult.java
│   │   ├── PdfAnalysisResponse.java
│   │   └── TaskInfo.java
│   └── service/
│       ├── converter/
│       │   └── PdfConverter.java
│       ├── AiService.java
│       ├── ConfigService.java
│       ├── DocumentConverterService.java
│       ├── ExamOutlineService.java
│       ├── FastQuestionService.java
│       ├── PdfBookAnalysisService.java
│       ├── PdfImageExtractor.java
│       └── TaskQueueService.java
├── src/main/resources/static/
│   ├── fast.html
│   ├── only.html
│   └── pdfbook.html
├── project-docs/
│   ├── README.md
│   ├── api-documentation.md
│   ├── workflows.md
│   ├── development-guide.md
│   ├── troubleshooting.md
│   └── database-schema.md
├── output/                            # 处理结果输出目录
├── config.json                        # AI 模型配置
└── pom.xml
```
