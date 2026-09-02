import os


import redis.asyncio as redis
from langgraph.checkpoint.redis.aio import AsyncRedisSaver
from dotenv import load_dotenv
from typing import Annotated

from fastapi import Depends, FastAPI, Request
from RAG.Logging import logger
load_dotenv()
from contextlib import asynccontextmanager

# 1. 专门管理底层 Redis 连接的生命周期
@asynccontextmanager
async def redis_client(app: FastAPI):
    """管理底层 Redis 连接"""
    logger.info("🔌 正在初始化 Redis 连接...")
    
    redis_client = redis.Redis(
        host=os.getenv("REDIS_HOST", "localhost"),
        port=int(os.getenv("REDIS_PORT", 6379)),
        db=int(os.getenv("REDIS_DB", 1)),
        decode_responses=True
    )
    
    await redis_client.ping()
    logger.info("✅ Redis 连接成功")
    app.state.redis = redis_client
    # 直接 yield 客户端对象本身
    yield redis_client 
    
    logger.info("🔄 正在关闭 Redis 连接...")
    await redis_client.aclose()  # 推荐使用 aclose() 进行异步关闭
    logger.info("✅ Redis 连接已关闭")

async def get_redis_client(request: Request) -> redis.Redis:
    """获取 Redis 客户端实例"""
    return request.app.state.redis

# 2. 专门管理 LangGraph Checkpointer 的生命周期（它依赖于底层的 Redis 连接）
@asynccontextmanager
async def redis_checkpointer(
    app:FastAPI
):
    """管理 LangGraph Redis Checkpointer 的生命周期"""
    logger.info("🗄️ 正在初始化 Redis Checkpointer...")
    redis_client = app.state.redis
    redis_checkpointer = AsyncRedisSaver(redis_client=redis_client)
    await redis_checkpointer.setup()
    
    logger.info("✅ Redis Checkpointer 已初始化")
    app.state.redis_checkpointer = redis_checkpointer
    # 直接 yield checkpointer 对象本身
    yield redis_checkpointer 
    
    logger.info("✅ Redis Checkpointer 资源已清理")


async def get_checkpointer(request: Request) -> AsyncRedisSaver:
    """获取 LangGraph Checkpointer 实例"""
    return request.app.state.redis_checkpointer