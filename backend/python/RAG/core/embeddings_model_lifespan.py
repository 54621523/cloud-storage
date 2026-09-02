import os
from langchain_openai import OpenAIEmbeddings
from dotenv import load_dotenv


from RAG.Logging import logger
from contextlib import asynccontextmanager
from fastapi import FastAPI, Request
load_dotenv()
api_key = os.getenv("LLM_API_KEY")
base_url = os.getenv("LLM_BASE_URL")
model_name = os.getenv("EMBEDDING_MODEL_ID")

@asynccontextmanager
async def embeddings_model(app:FastAPI):
    """管理 嵌入模型 的生命周期"""
    embeddings_model = OpenAIEmbeddings(
        api_key=api_key,
        base_url=base_url,
        model=model_name,
        chunk_size=10,
        tiktoken_enabled=False,
        check_embedding_ctx_length=False,
    )

    logger.info("✅ 嵌入模型已初始化")
    app.state.embeddings_model = embeddings_model
    yield embeddings_model
    # 清理资源（如果有的话）
    logger.info("✅ 嵌入模型已清理")


async def get_embeddings_model(request: Request) -> OpenAIEmbeddings:
    """获取  实例"""
    return request.app.state.embeddings_model