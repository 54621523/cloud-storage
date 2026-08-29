import os


import redis.asyncio as redis
from langgraph.checkpoint.redis.aio import AsyncRedisSaver
from dotenv import load_dotenv
from typing import Annotated
from .lifespan_manager import manager
from fastapi import Depends

load_dotenv()


# 1. 专门管理底层 Redis 连接的生命周期
@manager.register
async def get_redis_client():
    """管理底层 Redis 连接"""
    print("🔌 正在初始化 Redis 连接...")
    
    redis_client = redis.Redis(
        host=os.getenv("REDIS_HOST", "localhost"),
        port=int(os.getenv("REDIS_PORT", 6379)),
        db=int(os.getenv("REDIS_DB", 1)),
        decode_responses=True
    )
    
    await redis_client.ping()
    print("✅ Redis 连接成功")
    
    # 直接 yield 客户端对象本身
    yield redis_client 
    
    print("🔄 正在关闭 Redis 连接...")
    await redis_client.aclose()  # 推荐使用 aclose() 进行异步关闭
    print("✅ Redis 连接已关闭")
# 2. 专门管理 LangGraph Checkpointer 的生命周期（它依赖于底层的 Redis 连接）
@manager.register
async def get_redis_checkpointer(
    redis_client: Annotated[redis.Redis, Depends(get_redis_client)]
):
    """管理 LangGraph Redis Checkpointer 的生命周期"""
    print("🗄️ 正在初始化 Redis Checkpointer...")
    
    redis_checkpointer = AsyncRedisSaver(redis_client=redis_client)
    await redis_checkpointer.setup()
    
    print("✅ Redis Checkpointer 已初始化")
    
    # 直接 yield checkpointer 对象本身
    yield redis_checkpointer 
    
    print("✅ Redis Checkpointer 资源已清理")