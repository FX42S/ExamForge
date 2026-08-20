# OCR PDF 处理系统 API 接口文档

## 基本信息

- **服务地址**：`http://localhost:8188`
- **接口前缀**：`/api`
- **返回格式**：JSON
- **字符编码**：UTF-8

---

## 一、快速题目生成（Fast）

**使用场景**：只需上传考试大纲 PDF，直接生成 500 道题目

### 1.1 提交任务

```
POST /api/fast/generate
Content-Type: multipart/form-data
```

**请求参数**：

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| file | File | 是 | PDF 考试大纲文件 |
| filename | String | 是 | 文件名（用于输出目录命名） |

**请求示例**：
```bash
curl -X POST "http://localhost:8188/api/fast/generate" \
  -F "file=@考试大纲.pdf" \
  -F "filename=考试大纲.pdf"
```

**返回示例**：
```json
{
  "success": true,
  "code": 200,
  "message": "任务已加入队列",
  "data": {
    "taskId": "f8a7b6c5-d4e3-f2a1-b6c5-d4e3f2a1b6c5",
    "filename": "考试大纲.pdf",
    "status": "pending",
    "message": "等待处理，队列位置: 1",
    "progress": 0,
    "queuePosition": 1
  }
}
```

---

### 1.2 批量提交（多文件）

```
POST /api/fast/generate/batch
Content-Type: multipart/form-data
```

**请求参数**：

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| files | File[] | 是 | 多个 PDF 文件 |

**返回示例**：
```json
{
  "success": true,
  "code": 200,
  "message": "已提交 3 个任务",
  "data": [
    {"taskId": "xxx1", "filename": "大纲1.pdf", "status": "pending"},
    {"taskId": "xxx2", "filename": "大纲2.pdf", "status": "pending"},
    {"taskId": "xxx3", "filename": "大纲3.pdf", "status": "pending"}
  ]
}
```

---

### 1.3 查询任务状态

```
GET /api/fast/task/{taskId}
```

**路径参数**：

| 参数名 | 类型 | 说明 |
|--------|------|------|
| taskId | String | 任务ID（提交时返回的 taskId） |

**返回示例**：
```json
{
  "success": true,
  "code": 200,
  "data": {
    "taskId": "f8a7b6c5-d4e3-f2a1",
    "filename": "考试大纲.pdf",
    "status": "processing",
    "progress": 75,
    "message": "正在生成题目...",
    "queuePosition": 0
  }
}
```

**status 状态说明**：

| 状态 | 说明 |
|------|------|
| pending | 等待中 |
| processing | 处理中 |
| completed | 已完成 |
| failed | 失败 |

---

### 1.4 获取所有任务列表

```
GET /api/fast/tasks
```

**返回示例**：
```json
{
  "success": true,
  "code": 200,
  "data": [
    {"taskId": "xxx1", "filename": "大纲1.pdf", "status": "completed", "progress": 100},
    {"taskId": "xxx2", "filename": "大纲2.pdf", "status": "processing", "progress": 45}
  ]
}
```

---

### 1.5 下载结果

```
GET /api/fast/download/{taskId}
```

**返回**：文件下载（Content-Type: application/octet-stream）

**返回示例**：成功返回文件内容，失败返回 404

---

### 1.6 获取队列统计

```
GET /api/fast/stats
```

**返回示例**：
```json
{
  "success": true,
  "code": 200,
  "data": {
    "waiting": 3,
    "processing": 1,
    "completed": 12,
    "failed": 1,
    "queueSize": 4
  }
}
```

---

## 二、PDF 书籍分析（pdfbook）

**使用场景**：上传教材 PDF + 考试大纲，生成原文、大纲、题目

### 2.1 提交书籍任务

```
POST /api/tasks/submit/book
Content-Type: multipart/form-data
```

**请求参数**：

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| file | File | 是 | PDF 教材文件 |
| filename | String | 是 | 文件名（用于输出目录命名） |

**请求示例**：
```bash
curl -X POST "http://localhost:8188/api/tasks/submit/book" \
  -F "file=@教材.pdf" \
  -F "filename=教材.pdf"
```

**返回示例**：
```json
{
  "success": true,
  "code": 200,
  "message": "书籍任务已提交",
  "data": {
    "taskId": "book_20250622_143520",
    "fileName": "教材.pdf",
    "status": "pending",
    "currentStep": "等待处理",
    "progress": 0
  }
}
```

---

### 2.2 提交仅书籍任务（无考纲，自动生成题目）

```
POST /api/tasks/submit/book-only
Content-Type: multipart/form-data
```

**请求参数**：

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| file | File | 是 | PDF 教材文件 |
| filename | String | 是 | 文件名（用于输出目录命名） |

**返回示例**：
```json
{
  "success": true,
  "code": 200,
  "message": "书籍任务已提交（仅书籍模式，自动生成题目）",
  "data": {
    "taskId": "only_20250622_143520",
    "fileName": "教材.pdf",
    "status": "pending",
    "currentStep": "等待处理"
  }
}
```

---

### 2.3 订阅任务进度（SSE 实时推送）

```
GET /api/tasks/subscribe
Accept: text/event-stream
```

**说明**：建立 SSE 连接，实时接收所有任务的状态更新。

**推送消息格式**：
```json
{"type":"taskUpdate","data":{"taskId":"xxx","status":"processing","progress":45,"currentStep":"生成章节大纲","message":"正在处理第 3/9 章..."}}
```

**连接示例**（JavaScript）：
```javascript
const eventSource = new EventSource('/api/tasks/subscribe');
eventSource.addEventListener('taskUpdate', (event) => {
    const task = JSON.parse(event.data);
    console.log('任务更新:', task.data);
});
```

---

### 2.4 获取所有任务列表

```
GET /api/tasks/list
```

**返回示例**：
```json
{
  "success": true,
  "code": 200,
  "data": [
    {
      "taskId": "xxx1",
      "fileName": "教材.pdf",
      "status": "completed",
      "progress": 100,
      "currentStep": "完成"
    },
    {
      "taskId": "xxx2",
      "fileName": "教材2.pdf",
      "status": "processing",
      "progress": 45,
      "currentStep": "生成章节大纲",
      "message": "正在处理第 3/9 章..."
    }
  ]
}
```

---

### 2.5 删除任务

```
DELETE /api/tasks/{taskId}
```

---

### 2.6 清空已完成任务

```
DELETE /api/tasks/completed
```

---

### 2.7 获取队列统计

```
GET /api/tasks/stats
```

**返回示例**：
```json
{
  "success": true,
  "code": 200,
  "data": {
    "pending": 2,
    "processing": 1
  }
}
```

---

## 三、健康检查

### 3.1 服务健康状态

```
GET /api/health
```

**返回示例**：
```json
{
  "success": true,
  "code": 200,
  "message": "服务正常",
  "data": {
    "status": "UP",
    "timestamp": "2025-06-22 14:35:20",
    "service": "ORC PDF处理系统",
    "version": "1.0.0"
  }
}
```

---

## 四、错误码说明

| 错误码 | 说明 |
|--------|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 404 | 资源不存在 |
| 413 | 文件过大（单文件超过 100MB） |
| 500 | 服务器内部错误 |

**错误返回格式**：
```json
{
  "success": false,
  "code": 400,
  "message": "文件不能为空"
}
```

---

## 五、使用示例

### 5.1 Python requests 示例

```python
import requests
import time

BASE_URL = "http://localhost:8188/api"

# ============ Fast 快速题目生成 ============
def submit_fast_task(pdf_path, filename):
    """提交快速题目生成任务"""
    with open(pdf_path, 'rb') as f:
        files = {'file': (filename, f, 'application/pdf')}
        data = {'filename': filename}
        response = requests.post(f"{BASE_URL}/fast/generate", data=data, files=files)
    return response.json()

def get_fast_status(task_id):
    """查询Fast任务状态"""
    response = requests.get(f"{BASE_URL}/fast/task/{task_id}")
    return response.json()

def get_all_fast_tasks():
    """获取所有Fast任务"""
    response = requests.get(f"{BASE_URL}/fast/tasks")
    return response.json()

def download_fast_result(task_id, save_path):
    """下载Fast任务结果"""
    response = requests.get(f"{BASE_URL}/fast/download/{task_id}")
    if response.status_code == 200:
        with open(save_path, 'wb') as f:
            f.write(response.content)
        return True
    return False

def get_fast_stats():
    """获取Fast队列统计"""
    response = requests.get(f"{BASE_URL}/fast/stats")
    return response.json()

# ============ PDFBook 书籍+考纲 ============
def submit_pdfbook_task(pdf_path, filename):
    """提交书籍+考纲任务"""
    with open(pdf_path, 'rb') as f:
        files = {'file': (filename, f, 'application/pdf')}
        data = {'filename': filename}
        response = requests.post(f"{BASE_URL}/tasks/submit/book", data=data, files=files)
    return response.json()

def submit_book_only_task(pdf_path, filename):
    """提交仅书籍任务（自动生成题目）"""
    with open(pdf_path, 'rb') as f:
        files = {'file': (filename, f, 'application/pdf')}
        data = {'filename': filename}
        response = requests.post(f"{BASE_URL}/tasks/submit/book-only", data=data, files=files)
    return response.json()

def get_all_tasks():
    """获取所有书籍任务"""
    response = requests.get(f"{BASE_URL}/tasks/list")
    return response.json()

def delete_task(task_id):
    """删除任务"""
    response = requests.delete(f"{BASE_URL}/tasks/{task_id}")
    return response.json()

def clear_completed():
    """清空已完成任务"""
    response = requests.delete(f"{BASE_URL}/tasks/completed")
    return response.json()

def get_tasks_stats():
    """获取书籍队列统计"""
    response = requests.get(f"{BASE_URL}/tasks/stats")
    return response.json()

# ============ 健康检查 ============
def health_check():
    """健康检查"""
    response = requests.get(f"{BASE_URL}/health")
    return response.json()

# ============ 使用示例 ============
if __name__ == "__main__":
    # 1. 健康检查
    print(health_check())

    # 2. 提交Fast任务并等待完成
    result = submit_fast_task("考试大纲.pdf", "考试大纲.pdf")
    task_id = result['data']['taskId']
    print(f"任务ID: {task_id}")

    while True:
        status = get_fast_status(task_id)
        print(f"状态: {status['data']['status']}, 进度: {status['data']['progress']}%")
        if status['data']['status'] in ['completed', 'failed']:
            break
        time.sleep(3)

    # 3. 下载结果
    if status['data']['status'] == 'completed':
        download_fast_result(task_id, "result.md")

    # 4. 查看队列统计
    print(get_fast_stats())
```

### 5.2 curl 命令示例

```bash
# ============= Fast 快速题目生成 =============

# 1. 提交任务
TASK_ID=$(curl -s -X POST "http://localhost:8188/api/fast/generate" \
  -F "file=@考试大纲.pdf;type=application/pdf" \
  -F "filename=考试大纲.pdf" | jq -r '.data.taskId')
echo "任务ID: $TASK_ID"

# 2. 查询状态
curl -s "http://localhost:8188/api/fast/task/$TASK_ID" | jq

# 3. 获取所有任务
curl -s "http://localhost:8188/api/fast/tasks" | jq

# 4. 下载结果
curl -O "http://localhost:8188/api/fast/download/$TASK_ID"

# 5. 获取队列统计
curl -s "http://localhost:8188/api/fast/stats" | jq

# ============= PDFBook 书籍+考纲 =============

# 1. 提交书籍任务
BOOK_TASK=$(curl -s -X POST "http://localhost:8188/api/tasks/submit/book" \
  -F "file=@教材.pdf;type=application/pdf" \
  -F "filename=教材.pdf" | jq -r '.data.taskId')
echo "书籍任务ID: $BOOK_TASK"

# 2. 提交仅书籍任务
ONLY_TASK=$(curl -s -X POST "http://localhost:8188/api/tasks/submit/book-only" \
  -F "file=@教材.pdf;type=application/pdf" \
  -F "filename=教材.pdf" | jq -r '.data.taskId')
echo "仅书籍任务ID: $ONLY_TASK"

# 3. 获取所有任务
curl -s "http://localhost:8188/api/tasks/list" | jq

# 4. 获取队列统计
curl -s "http://localhost:8188/api/tasks/stats" | jq

# 5. 删除任务
curl -X DELETE "http://localhost:8188/api/tasks/$BOOK_TASK" | jq

# 6. 清空已完成任务
curl -X DELETE "http://localhost:8188/api/tasks/completed" | jq

# ============= 健康检查 =============
curl -s "http://localhost:8188/api/health" | jq
```

---

## 六、输出文件说明

### 6.1 快速题目生成输出

```
output/
└── 考试大纲/                    # 以 PDF 文件名命名
    └── 考试大纲.md              # 500 道题目
```

### 6.2 PDF 书籍分析输出

```
output/
└── 教材名_时间戳/
    ├── 00_目录.md               # 章节目录
    ├── 00_汇总.md               # 处理汇总
    ├── 处理进度.md              # 三阶段处理进度
    ├── 考试大纲_原文.txt        # 清理后的考纲（如果有）
    ├── 书籍第1章_标题_原文.md   # 各章原文
    ├── 书籍第1章_标题_大纲.md   # 各章大纲
    ├── 题目第1章.md             # 各章题目
    └── 00_题目汇总.md           # 题目汇总
```

---

## 七、注意事项

1. **队列处理**：任务提交后按顺序依次处理，关闭浏览器不影响后台运行
2. **文件大小**：建议单文件不超过 100MB
3. **处理时间**：根据 PDF 页数和 AI 响应速度，完整处理可能需要几分钟到几十分钟
4. **并发限制**：pdfbook 队列最多 50 个任务，fast 队列最多 10 个任务同时等待
5. **输出路径**：输出目录为程序运行目录下的 `output` 文件夹
6. **SSE 连接**：使用 SSE 实时推送时，连接超时时间为 30 分钟
