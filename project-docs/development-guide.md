# 智能题库生成系统 - 开发指南

## 一、项目结构

```
orc-app/
├── src/main/java/com/ocr/app/
│   ├── OcrApplication.java              # 启动类
│   ├── config/
│   │   └── WebConfig.java               # Web 配置（跨域等）
│   ├── controller/                      # REST API 控制器
│   │   ├── FastQuestionController.java  # Fast 快速题目接口
│   │   ├── HealthController.java        # 健康检查接口
│   │   ├── PdfBookAnalysisController.java # 单本书籍分析接口
│   │   └── TaskController.java          # 任务队列接口
│   ├── dto/                             # 数据传输对象
│   │   ├── ApiResponse.java
│   │   ├── ExamOutlineChapter.java
│   │   ├── ExamOutlineResult.java
│   │   ├── PdfAnalysisResponse.java
│   │   └── TaskInfo.java
│   └── service/                         # 业务逻辑层
│       ├── converter/
│       │   └── PdfConverter.java        # PDF 转图片（含 OTF 字体兜底）
│       ├── AiService.java               # AI 调用服务
│       ├── ConfigService.java           # 配置管理服务
│       ├── DocumentConverterService.java # 文档转换统一入口
│       ├── ExamOutlineService.java      # 考试大纲解析
│       ├── FastQuestionService.java     # 快速题目生成
│       ├── PdfBookAnalysisService.java  # PDF 书籍分析核心
│       ├── PdfImageExtractor.java       # PDF 图片提取
│       └── TaskQueueService.java        # 任务队列调度
├── src/main/resources/
│   ├── static/                          # 前端页面
│   │   ├── fast.html
│   │   ├── only.html
│   │   └── pdfbook.html
│   └── application.yml
├── project-docs/                        # 项目文档
├── output/                              # 处理结果输出
├── config.json                          # AI 配置
└── pom.xml
```

## 二、核心服务说明

### 2.1 PdfBookAnalysisService

负责 PDF 书籍的完整分析流程：

- `analyzeBook()`: 入口方法，被 pdfbook 和 only 模式共用
- `analyzeTableOfContents()`: AI 识别目录
- `parseTableOfContents()`: 解析 AI 返回的目录
- `filterNonChapterEntries()`: 过滤非正文章节
- `processChaptersWithCallback()`: 逐章处理原文和大纲

### 2.2 TaskQueueService

负责任务队列调度：

- `submitBookTask()`: 提交书籍任务
- `submitBookOnlyTask()`: 提交仅书籍任务
- `submitOutlineTask()`: 提交考纲任务
- `startProcessing()`: 启动队列处理器
- SSE 实时推送：`subscribeToUpdates()`

### 2.3 FastQuestionService

负责快速题目生成：

- `generateQuestions()`: 核心方法
- 提取"课程内容与考核要求"
- 清理垃圾内容
- 分块生成 500 道题目

### 2.4 AiService

负责调用 LM Studio：

- `callAi()`: 文本对话
- `callAiWithImages()`: 图片+文本对话
- 支持配置化模型和 API 地址

## 三、目录识别支持格式

系统支持将以下格式统一识别为章节：

- `第1章`、`第一章`
- `1. 第一章`、`1、第一章`
- `Module 1`、`Unit 1`
- `项目一`、`项目1`
- `专题一`
- `教学单元一`、`教学单元1`
- `单元一`、`单元1`

## 四、常见问题

### 4.1 PDF 字体渲染失败

解决方案：`PdfConverter.java` 中使用文本兜底渲染，将文字绘制成图片。

### 4.2 AI 返回格式不规范

解决方案：

1. Prompt 中明确要求格式
2. 后端多重解析规则兜底
3. 过滤异常章节

### 4.3 队列任务过多

pdfbook / only 模式最大排队 50 个，fast 模式最大排队 10 个。

## 五、扩展开发

### 5.1 新增处理模式

1. 在 `TaskController` 添加提交接口
2. 在 `TaskQueueService` 添加处理逻辑
3. 在 `src/main/resources/static/` 添加前端页面

### 5.2 修改 AI 提示词

- 书籍目录识别：`PdfBookAnalysisService.analyzeTableOfContents()`
- 原文提取：`PdfBookAnalysisService.processChaptersWithCallback()`
- 大纲生成：`PdfBookAnalysisService.generateChapterOutline()`
- 题目生成：`TaskQueueService.generateQuestionsForChapter()` / `generateQuestionsFromBookOnly()`
- 快速题目：`FastQuestionService.generateQuestions()`

## 六、调试技巧

1. 查看日志：`logs/app.log`
2. 查看 AI 原始响应：日志中搜索 "AI目录识别原始响应"
3. 查看输出目录：`output/`
4. 使用 `/api/health` 检查服务状态
