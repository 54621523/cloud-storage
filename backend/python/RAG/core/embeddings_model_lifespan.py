import os
from langchain_openai import OpenAIEmbeddings
from dotenv import load_dotenv

from .lifespan_manager import manager

load_dotenv()
api_key = os.getenv("LLM_API_KEY")
base_url = os.getenv("LLM_BASE_URL")
model_name = os.getenv("EMBEDDING_MODEL_ID")

@manager.register
async def get_embeddings_model():
    """管理 嵌入模型 的生命周期"""
    embeddings_model = OpenAIEmbeddings(
        api_key=api_key,
        base_url=base_url,
        model=model_name,
        chunk_size=10,
        tiktoken_enabled=False,
        check_embedding_ctx_length=False,
    )

    print("✅ 嵌入模型已初始化")
    yield embeddings_model
    # 清理资源（如果有的话）
    print("✅ 嵌入模型已清理")