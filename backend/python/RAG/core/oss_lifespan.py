# core/oss_lifespan.py
from typing import AsyncGenerator
from .lifespan_manager import manager
from .oss.minio_client import MinIOClient
from .oss.base_oss import BaseOSSClient
from RAG.Logging import logger



@manager.register
async def get_oss_client() -> AsyncGenerator[BaseOSSClient, None]:
    """ 管理 OSS 客户端生命周期 """
    logger.info("🔗 正在初始化 OSS 客户端...")
    oss_client = MinIOClient()
    yield oss_client
    logger.info("✅ OSS 客户端已关闭")

