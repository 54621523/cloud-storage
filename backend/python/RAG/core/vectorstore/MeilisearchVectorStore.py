import os
import asyncio
import uuid
from typing import Annotated, Any, Dict, List, Optional, Tuple, Type, Iterable

import meilisearch
from dotenv import load_dotenv
from fastapi import Depends
from langchain_core.documents import Document
from langchain_core.embeddings import Embeddings
from langchain_core.vectorstores import VectorStore

from ..lifespan_manager import manager
from ..embeddings_model_lifespan import get_embeddings_model

load_dotenv()


class CustomMeilisearch(VectorStore):
    """
    自定义 Meilisearch VectorStore 实现
    特点：
    1. 文本存储在顶层字段，不再嵌套在 metadata 中。
    2. 完整支持 LangChain 的 similarity_search 及其带分数 (with_score) 的变体。
    3. 支持 Meilisearch 的混合检索 (Hybrid Search)。
    """

    def __init__(
        self,
        client: meilisearch.Client,
        index_name: str,
        embedding: Embeddings,
        text_key: str = "text",
        metadata_key: str = "metadata",
        embedder_name: str = "default"
    ):
        self.client = client
        self.index_name = index_name
        self._embedding = embedding
        self.text_key = text_key
        self.metadata_key = metadata_key
        self.embedder_name = embedder_name
        
        self.index = self.client.index(self.index_name)

    @property
    def embeddings(self) -> Embeddings:
        """LangChain 规范要求返回 embedding 实例"""
        return self._embedding

    async def aadd_texts(
        self,
        texts: Iterable[str],
        metadatas: Optional[List[dict]] = None,
        ids: Optional[List[str]] = None,
        **kwargs: Any,
    ) -> List[str]:
        """异步添加文本到 Meilisearch"""
        texts_list = list(texts)
        
        # 处理 IDs
        if ids is None:
            ids = [str(uuid.uuid4()) for _ in texts_list]
        
        # 处理 Metadatas
        if metadatas is None:
            metadatas = [{} for _ in texts_list]

        # 构建 Meilisearch 文档结构：text 在顶层，metadata 在独立字段
        documents = [
            {
                "id": ids[i],
                self.text_key: texts_list[i],
                self.metadata_key: metadatas[i]
            }
            for i in range(len(texts_list))
        ]

        # 使用 asyncio.to_thread 包装同步的 Meilisearch SDK 调用
        task_info = await asyncio.to_thread(
            self.index.add_documents,
            documents,
            primary_key="id"
        )
        return ids

    def add_texts(
        self,
        texts: Iterable[str],
        metadatas: Optional[List[dict]] = None,
        ids: Optional[List[str]] = None,
        **kwargs: Any,
    ) -> List[str]:
        """同步添加文本"""
        return asyncio.run(self.aadd_texts(texts, metadatas, ids, **kwargs))

    def similarity_search_by_vector_with_scores(
        self,
        embedding: List[float],
        k: int = 4,
        filter: Optional[Dict[str, Any]] = None,
        **kwargs: Any,
    ) -> List[Tuple[Document, float]]:
        """
        核心搜索方法：基于向量搜索并返回分数
        完全对齐 langchain_community 的底层 API 调用规范
        """
        docs = []
        
        # 构建 Meilisearch 的向量搜索参数
        search_params = {
            "vector": embedding,
            "hybrid": {
                "semanticRatio": 1.0,  # 1.0 表示纯语义向量搜索
                "embedder": self.embedder_name
            },
            "limit": k,
            "showRankingScore": True,
            "attributesToRetrieve": ["*"]
        }
        
        if filter:
            meili_filter = self._convert_filter_to_meili_array(filter)
            search_params["filter"] = meili_filter

        results = self.index.search("", search_params)

        for result in results.get("hits", []):
            # ✅ 核心改进：直接从顶层获取 text，不再从 metadata 中 pop
            text = result.get(self.text_key, "")
            metadata = result.get(self.metadata_key, {})
            
            # 将 Meilisearch 返回的分数附加到 metadata 中，方便上层业务使用
            semantic_score = result.get("_rankingScore", 0.0)
            metadata["_score"] = semantic_score
            
            docs.append((Document(page_content=text, metadata=metadata), semantic_score))

        return docs
    
    def _convert_filter_to_meili_array(self, filter_dict: Dict[str, Any]) -> list:
        """
        将 LangChain 的 filter 字典转换为 Meilisearch 支持的数组格式
        注意：数组格式的 filter 不需要对字段名加反引号
        """
        meili_conditions = []
        for key, value in filter_dict.items():
            # 数组格式中，带点号的字段名不需要反引号
            # 直接使用原始 key
            field_key = key
        
            if isinstance(value, dict):
                if "$in" in value:
                    # 处理值列表
                    value_strs = []
                    for v in value["$in"]:
                        if isinstance(v, str):
                            # 字符串值用单引号包裹
                            value_strs.append(f"'{v}'")
                        else:
                            # 数字、布尔值等直接转换
                            value_strs.append(str(v))
                    # 生成不带反引号的条件
                    meili_conditions.append(f"{field_key} IN [{', '.join(value_strs)}]")
                
                elif "$eq" in value:
                    val = value["$eq"]
                    if isinstance(val, str):
                        meili_conditions.append(f"{field_key} = '{val}'")
                    else:
                        meili_conditions.append(f"{field_key} = {val}")
            else:
                # 简单键值对
                if isinstance(value, str):
                    meili_conditions.append(f"{field_key} = '{value}'")
                else:
                    meili_conditions.append(f"{field_key} = {value}")
    
        return meili_conditions

    def similarity_search_with_score(
        self,
        query: str,
        k: int = 4,
        filter: Optional[Dict[str, str]] = None,
        **kwargs: Any,
    ) -> List[Tuple[Document, float]]:
        """
        文本查询搜索并返回分数
        1. 使用客户端 Embedding 模型将 query 转为向量
        2. 调用向量搜索
        """
        query_vector = self._embedding.embed_query(query)
        return self.similarity_search_by_vector_with_scores(
            embedding=query_vector,
            k=k,
            filter=filter,
            **kwargs
        )

    def similarity_search(
        self,
        query: str,
        k: int = 4,
        filter: Optional[Dict[str, str]] = None,
        **kwargs: Any,
    ) -> List[Document]:
        """标准语义搜索入口，仅返回文档列表"""
        docs_and_scores = self.similarity_search_with_score(
            query=query,
            k=k,
            filter=filter,
            **kwargs
        )   
        return [doc for doc, _ in docs_and_scores]
    

    @classmethod
    def from_texts(
        cls: Type["CustomMeilisearch"],
        texts: List[str],
        embedding: Embeddings,
        metadatas: Optional[List[dict]] = None,
        ids: Optional[List[str]] = None,
        client: Optional[meilisearch.Client] = None,
        index_name: str = "document_index",
        text_key: str = "text",
        metadata_key: str = "metadata",
        embedder_name: str = "default",
        **kwargs: Any,
    ) -> "CustomMeilisearch":
        """
        从文本列表快速创建 CustomMeilisearch 实例。
        这是 LangChain VectorStore 基类强制要求实现的方法。
        """
        if not client:
            raise ValueError("Meilisearch client is required to use from_texts")
            
        # 1. 实例化当前的 VectorStore
        vectorstore = cls(
            client=client,
            index_name=index_name,
            embedding=embedding,
            text_key=text_key,
            metadata_key=metadata_key,
            embedder_name=embedder_name
        )
        
        # 2. 调用 add_texts 将数据写入 Meilisearch
        vectorstore.add_texts(texts=texts, metadatas=metadatas, ids=ids, **kwargs)
        
        return vectorstore


# ================= FastAPI 依赖注入配置 =================

@manager.register
async def get_meilisearch_client():
    print("正在初始化 meilisearch 客户端")
    client = meilisearch.Client(
        url=os.getenv("MEILISEARCH_URL", "localhost:7700"),
        api_key=os.getenv("MEILISEARCH_API_KEY", "oEkwo3F1GozGwD7lkeDevr_PMMNx5fgku1W1XOwDlW4")
    )
    yield client
    print("关闭 meilisearch")


@manager.register
async def get_vectorstore(
    embeddings_model: Annotated[Embeddings, Depends(get_embeddings_model)],
    meilisearch_client: Annotated[meilisearch.Client, Depends(get_meilisearch_client)]
):
    # 配置 Meilisearch 服务端的 Embedders
    embedders = {
        "default": {
            "source": "rest",
            "url": os.getenv("LLM_BASE_URL") + "/embeddings",
            "apiKey": os.getenv("LLM_API_KEY"),
            "dimensions": 1024,
            "request": {
                "model": "text-embedding-v3",
                "input": ["{{text}}"],
                "dimensions": 1024,
                "encoding_format": "float"
            },
            "response": {
                "data": [
                    {
                        "embedding": "{{embedding}}"
                    }
                ]
            },
            "headers": {
                "Content-Type": "application/json"
            }
        }
    }

    # 确保 Meilisearch 索引配置了正确的 embedders
    index_name = os.getenv("VECTOR_DOCUMENT_INDEX", "document_index")
    try:
        index = meilisearch_client.index(index_name)
        index.update_embedders(embedders)
    except Exception as e:
        print(f"更新 Meilisearch embedders 设置时出错（可能已存在）: {e}")

    # 实例化自定义的 VectorStore
    vector_store = CustomMeilisearch(
        client=meilisearch_client,
        index_name=index_name,
        embedding=embeddings_model,  # 传入客户端 Embedding 模型，用于处理 query
        text_key="text",
        metadata_key="metadata",
        embedder_name="default"
    )
    
    yield vector_store