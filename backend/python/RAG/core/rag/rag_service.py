# core/rag/rag_service.py

import uuid
import logging
import asyncio
from typing import List, Dict, Any, Optional
from langchain_core.embeddings import Embeddings
from langchain_core.vectorstores import VectorStore
from .chunker import TextChunkingService,get_chunker
from .converter import DocumentConverter,get_converter
from ..embeddings_model_lifespan import get_embeddings_model
from RAG.core import get_vectorstore
from fastapi import Depends
from typing import Annotated

logger = logging.getLogger(__name__)

class RAGIngestionService:
    """RAG 数据摄入服务：负责 转换 -> 分块 -> 向量化 -> 存储 的完整链路"""
    
    def __init__(
        self,
        converter: DocumentConverter,
        chunker: TextChunkingService,
        embeddings: Embeddings,
        vectorstore: VectorStore
    ):
        self.converter = converter
        self.chunker = chunker
        self.embeddings = embeddings
        self.vectorstore = vectorstore

    async def ingest_document(
        self, 
        bucket: str, 
        oss_key: str, 
        extra_metadata: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        """
        执行完整的文档摄入流程
        """
        # 1. 转换文档
        logger.info(f"开始摄入文档: {bucket}/{oss_key}")
        markdown_text = await self.converter.convert_from_oss_async(bucket, oss_key)
        if not markdown_text:
            raise ValueError("文档转换失败或内容为空")

        # 2. 文本分块
        chunks = await asyncio.to_thread(
            self.chunker.split_text, 
            markdown_text
        )
        if not chunks:
            raise ValueError("文档分块后为空")

        # 3. 构建元数据（为每个 chunk 附加来源信息，方便后续溯源）
        metadatas = []
        ids = []
        for i, chunk in enumerate(chunks):
            meta = {
                "source": oss_key,
                "bucket": bucket,
                "chunk_index": i,
                "total_chunks": len(chunks)
            }
            if extra_metadata:
                meta.update(extra_metadata)
            metadatas.append(meta)
            # 使用 UUID 确保唯一性，防止重复插入
            ids.append(str(uuid.uuid5(uuid.NAMESPACE_DNS, f"{oss_key}-{i}")))

        # 4. 存入向量库（LangChain 会自动调用 Embeddings 模型进行向量化）
        logger.info(f"正在将 {len(chunks)} 个文本块向量化并存入向量库...")
        await self.vectorstore.aadd_texts(
            texts=chunks,
            metadatas=metadatas,
            ids=ids
        )

        return {
            "oss_key": oss_key,
            "total_chunks": len(chunks),
            "message": "文档摄入成功"
        }
    
async def get_rag_service(
    converter: Annotated[DocumentConverter, Depends(get_converter)],
    chunker: Annotated[TextChunkingService, Depends(get_chunker)],
    embeddings: Annotated[Embeddings, Depends(get_embeddings_model)],
    vectorstore: Annotated[VectorStore, Depends(get_vectorstore)]
) -> RAGIngestionService:
    """
    顶层依赖：组装并提供 RAGIngestionService 实例。
    FastAPI 会自动解析并注入其所有子依赖。
    """
    return RAGIngestionService(
        converter=converter,
        chunker=chunker,
        embeddings=embeddings,
        vectorstore=vectorstore
    )