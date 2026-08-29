
from fastapi import APIRouter, Depends, HTTPException
from fastapi import Body
import logging
from typing import Annotated, Optional, Any
from RAG.core import RAGIngestionService,get_rag_service

router = APIRouter(tags=["Documents"])

logger = logging.getLogger(__name__)


@router.post("/ingest",summary="文档摄入接口", response_model=None)
async def ingest_document(
    bucket: Annotated[str, Body(..., embed=True)],
    oss_key: Annotated[str, Body(..., embed=True)],
    metadata: Annotated[Optional[dict[str, Any]], Body(embed=True)],
    rag_service: Annotated[RAGIngestionService,Depends(get_rag_service)]
):
    print('调用了摄入方法')
    try:
        # 直接调用业务方法，无需关心底层的 Converter、Chunker 等是如何创建的
        result = await rag_service.ingest_document(
            bucket=bucket, 
            oss_key=oss_key,
            extra_metadata=metadata
        )
        return {"code": 200, "data": result}
        
    except ValueError as e:
        # 捕获业务逻辑中抛出的预期异常（如：文档转换失败、分块为空）
        logger.warning(f"文档摄入业务异常: {e}")
        raise HTTPException(status_code=400, detail=str(e))
        
    except Exception as e:
        # 捕获其他未预期的系统异常（如：OSS 连接超时、向量库宕机）
        logger.exception("文档摄入发生未知系统错误")
        raise HTTPException(status_code=500, detail="内部服务器错误，请稍后重试")