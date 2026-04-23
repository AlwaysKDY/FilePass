import asyncio
import os
import socket
import logging
import aiofiles
from pathlib import Path
from fastapi import FastAPI, UploadFile, File, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, FileResponse
from pydantic import BaseModel

from config import AppConfig
from clipboard import copy_to_clipboard, get_clipboard
from notifier import notify
from utils import sanitize_filename

logger = logging.getLogger("filepass")


class TextPayload(BaseModel):
    content: str


def create_app(config: AppConfig) -> FastAPI:
    app = FastAPI(title="FilePass", version="1.0.0", docs_url=None, redoc_url=None)

    # 允许局域网跨域访问
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @app.exception_handler(Exception)
    async def global_exception_handler(request: Request, exc: Exception):
        logger.error(f"未处理异常: {exc}", exc_info=True)
        return JSONResponse(status_code=500, content={"detail": "服务器内部错误"})

    save_dir = Path(os.path.expanduser(config.save_dir))
    save_dir.mkdir(parents=True, exist_ok=True)

    # 电脑→手机 文件推送目录（用户把文件放这里，手机 App 可浏览下载）
    push_dir = Path(os.path.expanduser(config.send_to_phone_dir))
    push_dir.mkdir(parents=True, exist_ok=True)

    @app.get("/api/ping")
    async def ping():
        return {
            "status": "ok",
            "name": socket.gethostname(),
            "version": "1.0.0",
            "max_file_mb": config.max_file_size_mb,
        }

    @app.post("/api/text")
    async def receive_text(payload: TextPayload):
        text = payload.content
        if not text:
            raise HTTPException(400, "文本内容为空")
        await asyncio.to_thread(copy_to_clipboard, text)
        length = len(text)
        if not config.silent_mode:
            asyncio.ensure_future(asyncio.to_thread(notify, "FilePass", f"已接收文本 ({length}字)"))
        logger.info(f"已接收文本 ({length}字)")
        return {"status": "ok", "length": length}

    @app.post("/api/file")
    async def receive_file(
        file: UploadFile = File(...),
    ):
        if not file.filename:
            raise HTTPException(400, "缺少文件名")

        safe_name = sanitize_filename(file.filename)
        dest = save_dir / safe_name

        # 避免覆盖：同名文件自动追加序号
        if dest.exists():
            stem = dest.stem
            suffix = dest.suffix
            i = 1
            while dest.exists():
                dest = save_dir / f"{stem}_{i}{suffix}"
                i += 1

        total_size = 0
        size_exceeded = False
        async with aiofiles.open(dest, "wb") as f:
            while True:
                chunk = await file.read(1024 * 1024)  # 1 MB chunks
                if not chunk:
                    break
                total_size += len(chunk)
                if total_size > config.max_file_size_bytes:
                    size_exceeded = True
                    break
                await f.write(chunk)

        if size_exceeded:
            dest.unlink(missing_ok=True)
            raise HTTPException(413, f"文件超过大小限制 ({config.max_file_size_mb}MB)")

        size_display = f"{total_size / 1024 / 1024:.1f}MB" if total_size > 1024 * 1024 else f"{total_size / 1024:.1f}KB"
        if not config.silent_mode:
            asyncio.ensure_future(asyncio.to_thread(notify, "FilePass", f"已接收 {safe_name} ({size_display})"))
        logger.info(f"已接收文件 {safe_name} ({size_display}) -> {dest}")
        return {
            "status": "ok",
            "filename": safe_name,
            "size": total_size,
            "saved_to": str(dest),
        }

    @app.get("/api/clipboard")
    async def get_pc_clipboard():
        try:
            text = await asyncio.to_thread(get_clipboard)
            return {"content": text}
        except Exception as e:
            logger.error(f"读取剪贴板失败: {e}")
            raise HTTPException(500, "读取剪贴板失败")

    MAX_PUSH_FILES = 200  # 最多返回文件数

    @app.get("/api/push/files")
    async def list_push_files():
        """递归列出推送目录中所有文件（含子目录），最多 MAX_PUSH_FILES 条。"""
        files = []
        try:
            for f in sorted(push_dir.rglob("*")):
                if not f.is_file():
                    continue
                # 相对路径作为 name，保留子目录结构，供下载接口使用
                rel = f.relative_to(push_dir).as_posix()
                files.append({"name": rel, "size": f.stat().st_size})
                if len(files) >= MAX_PUSH_FILES:
                    break
        except Exception as e:
            logger.error(f"列出推送文件失败: {e}")
        return {"files": files, "dir": str(push_dir), "total": len(files), "capped": len(files) >= MAX_PUSH_FILES}

    @app.get("/api/push/download/{filepath:path}")
    async def download_push_file(filepath: str):
        """下载推送目录中的文件（支持子目录路径，防路径穿越）。"""
        # 规范化，移除多余分隔符、不允许绝对路径
        clean = Path(filepath.replace("\\", "/"))
        if clean.is_absolute() or ".." in clean.parts:
            raise HTTPException(403, "禁止访问")
        path = (push_dir / clean).resolve()
        if not path.is_relative_to(push_dir.resolve()):
            raise HTTPException(403, "禁止访问")
        if not path.exists() or not path.is_file():
            raise HTTPException(404, "文件不存在")
        logger.info(f"推送文件到手机: {clean}")
        return FileResponse(str(path), filename=path.name,
                            media_type="application/octet-stream")

    return app
