# core/oss_lifespan.py
from typing import AsyncGenerator

from .oss.minio_client import MinIOClient
from .oss.base_oss import BaseOSSClient
from RAG.Logging import logger
from fastapi import FastAPI, Request

from contextlib import asynccontextmanager



@asynccontextmanager
async def oss_client(app:FastAPI):
    """ 管理 OSS 客户端生命周期 """
    logger.info("🔗 正在初始化 OSS 客户端...")
    oss_client = MinIOClient()
    app.state.oss_client = oss_client
    yield oss_client
    logger.info("✅ OSS 客户端已关闭")


async def get_oss_client(request: Request) -> BaseOSSClient:
    """获取 OSS 客户端 实例"""
    return request.app.state.oss_client
