import os
from contextlib import asynccontextmanager


from langchain_openai import ChatOpenAI
from dotenv import load_dotenv

from RAG.Logging import logger
from fastapi import FastAPI, Request
load_dotenv()
api_key = os.getenv("LLM_API_KEY")
base_url = os.getenv("LLM_BASE_URL")
model_name = os.getenv("LLM_MODEL_ID")


_llm_instance = None
@asynccontextmanager
async def llm(app:FastAPI):
    global _llm_instance
    llm = ChatOpenAI(
        model=model_name,
        api_key=api_key,
        base_url=base_url
    )
    logger.info("✅ LLM 客户端已启动")
    app.state.llm = llm
    if _llm_instance is None:
        _llm_instance = llm
    yield llm
    # ChatOpenAI 通常没有 close 方法，但如果有需要可以清理
    logger.info("✅ LLM 客户端已关闭")


async def get_llm(request: Request) -> ChatOpenAI:
    """获取 LLM 实例"""
    return request.app.state.llm


def get_llm() -> ChatOpenAI:
    """供 LangGraph 节点直接调用，无需 request 参数"""
    if _llm_instance is None:
        raise RuntimeError("LLM 尚未初始化，请先调用 init_llm()")
    return _llm_instance