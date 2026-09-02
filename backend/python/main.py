from __future__ import annotations

from fastapi.middleware.cors import CORSMiddleware
from fastapi import FastAPI
from RAG.lifespan_manager import manager

from routers import documents
from routers import ai
from routers import rag_endpoint


app = FastAPI(lifespan = manager)


app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],          # 允许所有来源（本地测试推荐用 *，生产环境建议写具体域名）
    allow_credentials=True,       # 允许携带 Cookie 等凭证
    allow_methods=["*"],          # 允许所有 HTTP 方法（GET, POST 等）
    allow_headers=["*"],          # 允许所有请求头
)


app.include_router(documents.router)
app.include_router(ai.router)
app.include_router(rag_endpoint.router)


# --- 测试端点 ---
@app.get("/health")
async def health_check():
    return {"status": "ok"}