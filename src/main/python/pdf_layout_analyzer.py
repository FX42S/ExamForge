#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PDF 版面分析器

功能：
1. 接收 PDF 页面图片路径列表
2. 调用远程多模态 AI（OpenAI 兼容接口）进行版面分析
3. 提取页面文字，识别插图/图表/照片位置
4. 按原位置将插图裁剪并插入 Markdown

输出：
- stdout：包含图片链接的完整 Markdown 文本
- output_dir/figures/：裁剪出的插图文件

使用示例：
    python pdf_layout_analyzer.py \
        --images page_0001.png,page_0002.png \
        --output-dir ./chapter_output \
        --config ../../config.json \
        --chapter-title "第1章 示例"
"""

import argparse
import base64
import io
import json
import os
import re
import sys
import time
from pathlib import Path
from typing import Any

# Windows 控制台默认 GBK，中文日志会触发 UnicodeEncodeError 导致进程崩溃
# 强制 stdout/stderr 使用 UTF-8；如果不行则替换为不抛错的 writer
try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass


def safe_print(message: str, file=None):
    """跨平台安全打印，避免编码错误导致进程崩溃"""
    target = file or sys.stdout
    try:
        print(message, file=target)
    except UnicodeEncodeError:
        try:
            print(message.encode("utf-8", errors="replace").decode("utf-8"), file=target)
        except Exception:
            pass
    except Exception:
        pass


try:
    from PIL import Image
except ImportError:
    safe_print("错误：缺少 Pillow 依赖，请执行 pip install Pillow", file=sys.stderr)
    sys.exit(1)

try:
    from openai import OpenAI
except ImportError:
    safe_print("错误：缺少 openai 依赖，请执行 pip install openai", file=sys.stderr)
    sys.exit(1)


def load_config(config_path: str) -> dict:
    """读取 Java 同款的 config.json"""
    with open(config_path, "r", encoding="utf-8") as f:
        raw = json.load(f)

    # 兼容平铺键值与嵌套键值
    config = {}
    for k, v in raw.items():
        if isinstance(v, dict):
            config.update(v)
        else:
            config[k] = v

    return {
        "api_url": config.get("lmstudio.api.url", "http://localhost:1234/v1/chat/completions"),
        "model": config.get("lmstudio.model.name", "qwen/qwen3.5-9b"),
        "max_tokens": int(config.get("lmstudio.max.tokens", 30000)),
    }


def encode_image_to_base64(image_path: str) -> str:
    """将图片文件编码为 base64"""
    with open(image_path, "rb") as f:
        return base64.b64encode(f.read()).decode("utf-8")


def resize_if_needed(image_path: str, max_size: int = 1600) -> str:
    """
    如果图片尺寸过大，先缩放到短边不超过 max_size，返回临时文件路径。
    部分远程模型对单张图片分辨率有限制，适度缩放可减少显存与 token。
    """
    img = Image.open(image_path)
    width, height = img.size
    if width <= max_size and height <= max_size:
        return image_path

    ratio = min(max_size / width, max_size / height)
    new_size = (int(width * ratio), int(height * ratio))
    img = img.resize(new_size, Image.Resampling.LANCZOS)

    temp_path = image_path + ".resized.png"
    img.save(temp_path, "PNG")
    return temp_path


def normalize_base_url(api_url: str) -> str:
    """将 /v1/chat/completions 或 /v1 结尾的 URL 统一为 OpenAI client 需要的 base_url"""
    url = api_url.strip()
    # 去掉末尾的 /chat/completions
    if url.endswith("/chat/completions"):
        url = url[: -len("/chat/completions")]
    # 确保以 /v1 结尾（OpenAI 兼容接口标准）
    if not url.endswith("/v1"):
        url = url.rstrip("/") + "/v1"
    return url


def call_ai_vision(
    api_url: str,
    model: str,
    max_tokens: int,
    image_path: str,
    prompt: str,
    api_key: str = "not-needed",
) -> str:
    """调用 OpenAI 兼容接口的多模态模型"""
    client = OpenAI(base_url=normalize_base_url(api_url), api_key=api_key)

    resized_path = resize_if_needed(image_path)
    base64_image = encode_image_to_base64(resized_path)

    if resized_path != image_path and os.path.exists(resized_path):
        try:
            os.remove(resized_path)
        except OSError:
            pass

    messages = [
        {
            "role": "system",
            "content": "你是一个 PDF 版面分析助手。无论用户请求什么，你都必须只输出 JSON 格式，不要输出 Markdown 正文、解释或任何其他内容。",
        },
        {
            "role": "user",
            "content": [
                {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{base64_image}"}},
                {"type": "text", "text": prompt},
            ],
        }
    ]

    max_retries = 3
    for attempt in range(max_retries):
        try:
            response = client.chat.completions.create(
                model=model,
                messages=messages,
                max_tokens=max_tokens,
                temperature=0.2,
            )
            return response.choices[0].message.content or ""
        except Exception as e:
            safe_print(f"AI 调用失败（第 {attempt + 1}/{max_retries} 次）: {e}", file=sys.stderr)
            if attempt < max_retries - 1:
                time.sleep(2 ** attempt)
            else:
                raise

    return ""


def parse_ai_response(response: str) -> dict:
    """从 AI 返回中提取 JSON"""
    if not response:
        return {"markdown": "", "figures": []}

    # 先尝试整段解析
    cleaned = response.strip()
    if cleaned.startswith("```"):
        # 去掉 ```json ... ``` 包裹
        cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned)
        cleaned = re.sub(r"\s*```$", "", cleaned)

    try:
        return json.loads(cleaned)
    except json.JSONDecodeError:
        pass

    # 尝试从文本中提取 JSON 块
    match = re.search(r"\{.*\}", cleaned, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(0))
        except json.JSONDecodeError:
            pass

    safe_print("无法解析 AI 响应为 JSON，已忽略当前页面输出", file=sys.stderr)
    return {"markdown": "", "figures": []}


def crop_figure(image_path: str, bbox: list, output_path: str) -> bool:
    """根据相对坐标 [x, y, width, height] 裁剪插图"""
    try:
        with Image.open(image_path) as img:
            width, height = img.size
            x = int(bbox[0] * width)
            y = int(bbox[1] * height)
            w = int(bbox[2] * width)
            h = int(bbox[3] * height)

            # 边界保护
            x = max(0, x)
            y = max(0, y)
            w = min(w, width - x)
            h = min(h, height - y)

            # 几何过滤：跳过明显不是图片的区域
            # 1. 绝对尺寸过小（避免文字行、标点、行内小图）
            if w < 80 or h < 80:
                safe_print(f"跳过尺寸过小的区域：{bbox} -> {w}x{h}", file=sys.stderr)
                return False

            # 2. 宽高比异常（文字行通常很扁）
            aspect_ratio = w / h if h > 0 else 999
            if aspect_ratio > 8 or aspect_ratio < 0.125:
                safe_print(f"跳过宽高比异常的区域：{bbox} -> 比例 {aspect_ratio:.2f}", file=sys.stderr)
                return False

            # 3. 相对面积过小（避免行内小符号、页眉横线）
            area_ratio = (w * h) / (width * height)
            if area_ratio < 0.001:
                safe_print(f"跳过面积过小的区域：{bbox} -> 占比 {area_ratio:.4f}", file=sys.stderr)
                return False

            cropped = img.crop((x, y, x + w, y + h))
            cropped.save(output_path, "PNG")
            return True
    except Exception as e:
        safe_print(f"裁剪插图失败 {image_path} bbox={bbox}: {e}", file=sys.stderr)
        return False


def replace_figure_placeholders(
    markdown: str,
    figures: list,
    image_path: str,
    figures_dir: Path,
    chapter_title: str,
    page_index: int,
) -> str:
    """将 [FIGURE:index] 替换为 Markdown 图片链接"""
    for fig in figures:
        index = fig.get("index", 0)
        bbox = fig.get("bbox", [])
        description = fig.get("description", f"插图{index}")

        if not bbox or len(bbox) != 4:
            continue

        safe_chapter = re.sub(r"[^\w\u4e00-\u9fa5]+", "_", chapter_title).strip("_")[:30]
        fig_filename = f"{safe_chapter}_page{page_index:04d}_fig{index:03d}.png"
        fig_path = figures_dir / fig_filename

        if crop_figure(image_path, bbox, str(fig_path)):
            relative_path = f"figures/{fig_filename}"
            placeholder = f"[FIGURE:{index}]"
            md_link = f"\n![{description}]({relative_path})\n"
            markdown = markdown.replace(placeholder, md_link)
        else:
            # 裁剪失败时保留占位符提示
            placeholder = f"[FIGURE:{index}]"
            markdown = markdown.replace(placeholder, f"\n<!-- 插图 {index} 裁剪失败 -->\n")

    # 清理未被替换的占位符
    markdown = re.sub(r"\[FIGURE:\d+\]", "", markdown)
    return markdown


def analyze_page(
    ai_config: dict,
    image_path: str,
    page_index: int,
    figures_dir: Path,
    chapter_title: str,
) -> str:
    """分析单页，返回包含插图链接的 Markdown"""
    prompt = """请分析这张 PDF 页面图片，把它转换为 Markdown 格式。

【任务1：提取文字】
完整提取页面中的所有文字内容，保持段落结构。

【任务2：识别真正的插图】
只有在页面中看到以下视觉元素时，才在 figures 中返回：
- 照片、绘画、手绘图
- 示意图、流程图、架构图
- 数据图表（折线图、柱状图、饼图、表格截图等）
- 独立的图片类内容

【不要放入 figures 的内容】
- 纯文字段落、标题、章节名、页眉页脚
- 图注文字（例如 "图2-1 塔吊示意"）
- 行内公式、独立的数学符号
- 页面装饰线、分隔线、页码
- 表格内的纯文字（如果表格本身不是图片）
- 看起来是文字块或文字行的任何区域

【bbox 规则】
- bbox 必须紧贴图片本身的边界
- 不要包含图片周围的文字、图注、标题
- 不要包含图片外侧留白

【占位符规则】
- 占位符格式：[FIGURE:index]
- index 从 1 开始，按页面从上到下顺序编号
- 占位符必须放在图片在页面中对应的位置
- 只输出 JSON，不要任何解释

输出格式：
{
  "markdown": "页面文字内容，图片位置用 [FIGURE:1] [FIGURE:2] 占位",
  "figures": [
    {"index": 1, "description": "图片简要描述", "bbox": [0.1, 0.2, 0.3, 0.4]}
  ]
}

说明：
- bbox 是相对坐标 [x, y, width, height]，范围 0-1，x,y 是左上角坐标
- width 和 height 是相对于页面宽高的比例
- 如果一个区域内主要是文字，请不要把它加入 figures
- 每个插图只需要一个 bbox，不要重复"""

    try:
        response = call_ai_vision(
            ai_config["api_url"],
            ai_config["model"],
            ai_config["max_tokens"],
            image_path,
            prompt,
        )
    except Exception as e:
        safe_print(f"页面 {page_index} AI 调用失败", file=sys.stderr)
        return f"\n<!-- 页面 {page_index} AI 调用失败 -->\n"

    result = parse_ai_response(response)
    markdown = result.get("markdown", "")
    figures = result.get("figures", [])

    return replace_figure_placeholders(
        markdown, figures, image_path, figures_dir, chapter_title, page_index
    )


def main():
    parser = argparse.ArgumentParser(description="PDF 版面分析器：提取文字并按原位置插入插图")
    parser.add_argument("--images", required=True, help="逗号分隔的页面图片路径")
    parser.add_argument("--output-dir", required=True, help="输出目录，插图会保存到 output-dir/figures/")
    parser.add_argument("--config", required=True, help="config.json 路径")
    parser.add_argument("--chapter-title", default="章节", help="章节标题，用于插图命名")
    args = parser.parse_args()

    try:
        ai_config = load_config(args.config)
    except Exception as e:
        safe_print(f"读取配置文件失败：{e}", file=sys.stderr)
        sys.exit(1)

    output_dir = Path(args.output_dir)
    figures_dir = output_dir / "figures"
    try:
        figures_dir.mkdir(parents=True, exist_ok=True)
    except Exception as e:
        safe_print(f"创建 figures 目录失败：{e}", file=sys.stderr)
        sys.exit(1)

    image_paths = [p.strip() for p in args.images.split(",") if p.strip()]
    if not image_paths:
        safe_print("错误：未提供有效的图片路径", file=sys.stderr)
        sys.exit(1)

    safe_print(f"开始处理 {len(image_paths)} 张图片，输出目录：{output_dir}", file=sys.stderr)

    full_markdown_parts = []
    for i, image_path in enumerate(image_paths, start=1):
        if not os.path.exists(image_path):
            safe_print(f"警告：图片不存在，跳过：{image_path}", file=sys.stderr)
            continue

        try:
            page_md = analyze_page(ai_config, image_path, i, figures_dir, args.chapter_title)
            full_markdown_parts.append(page_md)
        except Exception as e:
            safe_print(f"分析页面 {image_path} 失败：{e}", file=sys.stderr)
            full_markdown_parts.append(f"\n<!-- 页面 {i} 分析失败：{e} -->\n")

    output = "\n\n".join(full_markdown_parts)
    try:
        print(output)
    except UnicodeEncodeError:
        # 最后的保险：stdout 编码错误时直接写 UTF-8 字节
        sys.stdout.buffer.write(output.encode("utf-8", errors="replace"))


if __name__ == "__main__":
    main()
