from __future__ import annotations

from fastapi.middleware.cors import CORSMiddleware
from langchain_openai import OpenAIEmbeddings
from fastapi import FastAPI, Depends
from RAG.core import manager
from typing import Annotated
from RAG.core import get_vectorstore, get_embeddings_model, get_meilisearch_client, BaseOSSClient, get_oss_client
from langchain_core.vectorstores import VectorStore
import meilisearch

from routers import documents
from routers import ai

import socket
from v2.nacos import NacosNamingService, ClientConfigBuilder, NacosConfigService
from v2.nacos.naming.model.naming_param import RegisterInstanceParam, DeregisterInstanceParam
# ========== Nacos 配置 ==========
NACOS_SERVER = "http://localhost:8848"   # 确保与你的 Nacos 地址一致
NAMESPACE = "public"                     # 命名空间
SERVICE_NAME = "fastapi-service"      # 服务名称（可自定义）
SERVICE_IP = "localhost"
SERVICE_PORT = 8000

naming_service = None

def get_local_ip():
    try:
        # 连接到一个外部地址（不实际发送数据）来获取本机 IP
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
@manager.register
async def nacos():
    global naming_service
    
    # --- 启动阶段 ---
    # 1. 构建配置
    client_config = (ClientConfigBuilder()
                     .server_address('localhost:8848')
                     .namespace_id(NAMESPACE)
                     .log_level("INFO")
                     .build())
    
    # 2. 创建 Naming 服务
    naming_client = await NacosNamingService.create_naming_service(client_config)
    
    # 3. 注册实例（自动心跳由 ephemeral=True 保证）
    response = await naming_client.register_instance(
            request=RegisterInstanceParam(service_name=SERVICE_NAME, group_name='DEFAULT_GROUP', ip=get_local_ip(),
                port=SERVICE_PORT,
                enabled=True,
                healthy=True, ephemeral=True))
    print(f"✅ 服务 { SERVICE_NAME} 注册到nacos上")


    yield

    # --- 关闭阶段 ---
    # 4. 注销实例
    response = await naming_client.deregister_instance(
          request=DeregisterInstanceParam(service_name=SERVICE_NAME, group_name='DEFAULT_GROUP', ip=get_local_ip(),
                                          port=SERVICE_NAME, ephemeral=True)
      )
    print(f"✅ 服务 {SERVICE_NAME} 在nacos上销毁")

app = FastAPI(lifespan = manager)


app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],          # 允许所有来源（本地测试推荐用 *，生产环境建议写具体域名）
    allow_credentials=True,       # 允许携带 Cookie 等凭证
    allow_methods=["*"],          # 允许所有 HTTP 方法（GET, POST 等）
    allow_headers=["*"],          # 允许所有请求头
)


app.include_router(documents.router, prefix="/documents")
app.include_router(ai.router, prefix="/ai")


# --- 测试端点 ---
@app.get("/health")
async def health_check():
    return {"status": "ok"}

# --- 示例端点，展示如何使用依赖 ---
@app.get("/test")
async def test(
    embeddings_model: Annotated[OpenAIEmbeddings, Depends(get_embeddings_model)],
    meilisearch_client: Annotated[meilisearch.Client, Depends(get_meilisearch_client)],
    meilisearch_vectorstore: Annotated[VectorStore, Depends(get_vectorstore)],
    oss_client: Annotated[BaseOSSClient,Depends(get_oss_client)]
):
    return {
        "embedding": str(type(embeddings_model)),
        "meilisearch_client": str(type(meilisearch_client)),
        "meilisearch_vectorstore": str(type(meilisearch_vectorstore)),
        "oss_client": str(type(oss_client))
    }