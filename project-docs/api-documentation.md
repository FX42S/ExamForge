# 智能题库生成系统 - API 接口文档

**基础地址**: `http://localhost:8188`

**统一响应格式**:

```json
{
  "success": true,
  "message": "操作成功",
  "data": { }
}
```

---

## 健康检查

### 检查服务状态

```http
GET /api/health
```

**响应示例**:

```json
{
  "success": true,
  "message": "服务运行正常",
  "data": "UP"
}
```

---

## 一、Fast 快速题目生成

适用于：上传 PDF 考试大纲，直接生成 500 道题目。

### 1.1 单文件提交

```http
POST /api/fast/generate
Content-Type: multipart/form-data
```

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| `file` | File | 是 | PDF 考试大纲文件 |
| `filename` | String | 是 | 文件名（不带扩展名，用于输出目录命名） |

**curl 示例**:

```bash
curl -X POST "http://localhost:8188/api/fast/generate" \
  -F "file=@考试大纲.pdf" \
  -F "filename=数据结构考试大纲"
```

**响应示例**:

```json
{
  "success": true,
  "message": "任务已提交",
  "data": {
    "taskId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "status": "pending",
    "message": "等待处理"
  }
}
```

### 1.2 批量提交

```http
POST /api/fast/generate/batch
Content-Type: multipart/form-data
```

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| `files` | File[] | 是 | 多个 PDF 文件 |

**curl 示例**:

```bash
curl -X POST "http://localhost:8188/api/fast/generate/batch" \
  -F "files=@考试大纲1.pdf" \
  -F "files=@考试大纲2.pdf"
```

### 1.3 查询任务状态

```http
GET /api/fast/task/{taskId}
```

**响应示例**:

```json
{
  "success": true,
  "message": "查询成功",
  "data": {
    "taskId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "fileName": "数据结构考试大纲",
    "status": "processing",
    "progress": 45,
    "currentStep": "AI生成题目",
    "message": "第 2/5 段题目生成中",
    "outputPath": "output/数据结构考试大纲"
  }
}
```

### 1.4 查询所有 Fast 任务

```http
GET /api/fast/tasks
```

### 1.5 下载生成的题目

```http
GET /api/fast/download/{taskId}
```

**响应**: 返回生成的 Markdown 文件。

### 1.6 获取队列统计

```http
GET /api/fast/stats
```

**响应示例**:

```json
{
  "success": true,
  "message": "查询成功",
  "data": {
    "pending": 2,
    "processing": 1,
    "completed": 5,
    "failed": 0
  }
}
```

---

## 二、PDF 书籍 + 考纲模式

适用于：上传 PDF 书籍和 PDF 考试大纲，生成原文、大纲和题目。

### 2.1 提交任务

```http
POST /api/tasks/submit/book
Content-Type: multipart/form-data
```

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| `bookFile` | File | 是 | PDF 书籍文件 |
| `bookFilename` | String | 是 | 书籍文件名 |
| `outlineFile` | File | 是 | PDF 考试大纲文件 |
| `outlineFilename` | String | 是 | 考纲文件名 |

**curl 示例**:

```bash
curl -X POST "http://localhost:8188/api/tasks/submit/book" \
  -F "bookFile=@数据结构.pdf" \
  -F "bookFilename=数据结构" \
  -F "outlineFile=@数据结构考试大纲.pdf" \
  -F "outlineFilename=数据结构考试大纲"
```

**响应示例**:

```json
{
  "success": true,
  "message": "书籍任务已提交，请等待第一阶段完成后上传考试大纲",
  "data": {
    "taskId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "status": "pending",
    "taskType": "book"
  }
}
```

> 注意：提交后系统会返回书籍任务 ID，考纲上传需要关联该 ID。实际使用中建议直接通过 `/only.html` 模式，或前端自动关联。

---

## 三、仅书籍模式（Only）

适用于：只上传 PDF 书籍，自动生成原文、大纲和题目，无需考试大纲。

### 3.1 提交任务

```http
POST /api/tasks/submit/book-only
Content-Type: multipart/form-data
```

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| `file` | File | 是 | PDF 书籍文件 |
| `filename` | String | 是 | 文件名 |

**curl 示例**:

```bash
curl -X POST "http://localhost:8188/api/tasks/submit/book-only" \
  -F "file=@数据结构.pdf" \
  -F "filename=数据结构"
```

**响应示例**:

```json
{
  "success": true,
  "message": "书籍任务已提交（仅书籍模式，自动生成题目）",
  "data": {
    "taskId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "status": "pending",
    "taskType": "book-only"
  }
}
```

---

## 四、任务状态订阅（SSE）

前端推荐使用 SSE 实时获取任务进度。

```http
GET /api/tasks/subscribe
```

**说明**:

- 建立 Server-Sent Events 连接
- 服务会推送 `init` 事件（当前所有任务）和 `taskUpdate` 事件（任务更新）
- 浏览器关闭后自动断开，不影响后台处理

**JavaScript 示例**:

```javascript
const eventSource = new EventSource('/api/tasks/subscribe');

eventSource.onmessage = function(event) {
    const data = JSON.parse(event.data);
    console.log(data);
};
```

---

## 五、任务管理

### 5.1 获取任务列表

```http
GET /api/tasks/list
```

### 5.2 删除任务

```http
DELETE /api/tasks/{taskId}
```

### 5.3 清空已完成任务

```http
DELETE /api/tasks/completed
```

### 5.4 获取队列统计

```http
GET /api/tasks/stats
```

**响应示例**:

```json
{
  "success": true,
  "message": "查询成功",
  "data": {
    "pending": 3,
    "processing": 1,
    "completed": 10,
    "failed": 0
  }
}
```

---

## 六、处理完成判断

任务处理完成后，会在输出目录生成空文件 `finish.md`。

外部服务可以通过以下方式判断：

1. 调用 `/api/fast/task/{taskId}` 或 `/api/tasks/list` 查看 `status` 字段
2. 检查输出目录下是否存在 `finish.md` 文件

示例路径：

```
output/数据结构_20260101_120000/finish.md
output/数据结构考试大纲/finish.md
```

---

## 七、常见错误码

| HTTP 状态码 | 说明 |
|-------------|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 413 | 文件过大 |
| 429 | 任务队列已满 |
| 500 | 服务器内部错误 |

---

## 八、Python 调用示例

```python
import requests
import time

# 提交 Fast 任务
res = requests.post(
    "http://localhost:8188/api/fast/generate",
    files={"file": open("考试大纲.pdf", "rb")},
    data={"filename": "数据结构考试大纲"}
)
task_id = res.json()["data"]["taskId"]

# 轮询状态
while True:
    status = requests.get(f"http://localhost:8188/api/fast/task/{task_id}").json()
    print(status["data"]["status"], status["data"]["message"])
    if status["data"]["status"] in ("completed", "failed"):
        break
    time.sleep(3)

# 下载结果
r = requests.get(f"http://localhost:8188/api/fast/download/{task_id}")
open("生成的题目.md", "wb").write(r.content)
```
