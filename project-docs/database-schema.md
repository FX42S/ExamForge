# 数据库设计

## 说明

当前系统主要使用文件系统（`output/` 目录）存储处理结果，数据库仅用于存储系统配置。

## 表: system_config

系统配置表，存储 AI 服务连接信息。

| 字段 | 类型 | 长度 | 可空 | 默认值 | 说明 |
|------|------|------|------|--------|------|
| id | INT | - | NO | AUTO_INCREMENT | 主键 |
| config_key | VARCHAR | 100 | NO | - | 配置键，唯一 |
| config_value | TEXT | - | YES | NULL | 配置值 |
| description | VARCHAR | 255 | YES | NULL | 配置说明 |
| updated_at | TIMESTAMP | - | NO | CURRENT_TIMESTAMP | 更新时间 |

### 索引

- PRIMARY KEY (`id`)
- UNIQUE KEY `uk_config_key` (`config_key`)

### 建表 SQL

```sql
CREATE DATABASE IF NOT EXISTS ocr_db 
CHARACTER SET utf8mb4 
COLLATE utf8mb4_general_ci;

USE ocr_db;

CREATE TABLE IF NOT EXISTS system_config (
    id INT PRIMARY KEY AUTO_INCREMENT,
    config_key VARCHAR(100) UNIQUE,
    config_value TEXT,
    description VARCHAR(255),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
```

### 配置优先级

1. `config.json`（项目根目录）优先级最高
2. 数据库 system_config 表次之
3. application.yml 默认值最低

### 当前配置项

项目根目录 `config.json`：

```json
{
  "lmstudio.base.url": "http://localhost:1234",
  "lmstudio.api.path": "/v1/chat/completions",
  "lmstudio.model.name": "qwen/qwen3.5-9b"
}
```
