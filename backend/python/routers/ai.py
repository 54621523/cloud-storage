import os
import json
import asyncio
from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse
from langchain_openai import ChatOpenAI
from langchain_core.messages import HumanMessage
from langchain_core.vectorstores import VectorStore
from langgraph.checkpoint.base import BaseCheckpointSaver
from typing import Annotated, Optional, List

from pydantic import BaseModel
from RAG.core.llm_lifespan import get_llm
from RAG.core.redis_lifespan import get_redis_checkpointer
from RAG.core.vectorstore.MeilisearchVectorStore import get_vectorstore
from langgraph.checkpoint.memory import InMemorySaver
from RAG.RagAgent import build_agent_graph

router = APIRouter(tags=["Ai"])

class ChatRequest(BaseModel):
    session_id: str
    message: str
    allowed_doc_ids: Optional[List[int]] = None

def format_sse(data: dict, event: str = None) -> str:
    """
    将字典序列化为标准的 SSE 数据格式
    """
    json_str = json.dumps(data, ensure_ascii=False)
    sse_message = ""
    if event:
        sse_message += f"event: {event}\n"
    sse_message += f"data: {json_str}\n\n"
    return sse_message

@router.post("/chat", summary="聊天入口")
async def chat_endpoint(
    request: ChatRequest,
    llm: Annotated[ChatOpenAI, Depends(get_llm)],
    vectorstore: Annotated[VectorStore, Depends(get_vectorstore)],
    checkpointer: Annotated[BaseCheckpointSaver, Depends(get_redis_checkpointer)],
):
    use_mock = os.getenv("USE_MOCK_AI", "true").lower() == "true"
    
    if use_mock:
        generator = mock_event_generator(request)
    else:
        generator = real_event_generator(request, llm, vectorstore, checkpointer)
    
    return StreamingResponse(generator, media_type="text/event-stream")

async def real_event_generator(
    request: ChatRequest,
    llm: ChatOpenAI,
    vectorstore: VectorStore,
    checkpointer: BaseCheckpointSaver,
):
    allowed_doc_ids = request.allowed_doc_ids
    thread_id = request.session_id
    message = request.message

    use_memory = os.getenv("USE_MEMORY_CHECKPOINT", "false").lower() == "true"
    active_checkpointer = InMemorySaver() if use_memory else checkpointer

    app = build_agent_graph(llm, vectorstore, active_checkpointer, allowed_doc_ids=allowed_doc_ids)
    
    config = {
        "configurable": {
            "thread_id": thread_id,
            "vectorstore": vectorstore,
            "allowed_doc_ids": allowed_doc_ids
        }
    }
    
    inputs = {
        "messages": [HumanMessage(content=message)],
        "original_question": message
    }

    streamed_nodes = set()
    try:
        async for event in app.astream_events(inputs, config, version="v2"):
            kind = event.get("event")
            metadata = event.get("metadata", {})
            langgraph_node = metadata.get("langgraph_node")

            if kind == "on_chat_model_stream" and langgraph_node == "merge":
                chunk = event.get("data", {}).get("chunk")
                if chunk and hasattr(chunk, "content") and chunk.content:
                    streamed_nodes.add(langgraph_node)
                    yield format_sse(data={'content': chunk.content}, event='stream_chunk')

            if kind == "on_tool_start":
                tool_name = event.get("name")
                yield format_sse(data={'tool_name': tool_name}, event='tool_call')
            
            if kind == "on_custom_event":
                custom_data = event.get("data", {})
                yield format_sse(data=custom_data, event='custom')

        # 流式结束后，获取最终状态
        final_state = await app.aget_state(config)
        state_values = final_state.values
    
        all_chunks = state_values.get("all_retrieved_chunks", [])
        if all_chunks:
            yield format_sse(data={'data': all_chunks}, event='references')
        
        final_answer = final_state.values.get("final_answer")
        if final_answer:
            yield format_sse(data={'content': final_answer}, event='final_answer')

    except Exception as e:
        import traceback
        traceback.print_exc()
        yield format_sse(data={'message': str(e)}, event='error')
    finally:
        yield format_sse(data={}, event='done')
async def mock_event_generator(request: ChatRequest):
    """
    模拟 SSE 事件流，用于前端调试。
    包含 session_created（字段为 data）、流式 chunk、references、final_answer、done。
    每个事件间有适当延时，模拟真实流。
    """

    # 2. 模拟流式输出（分块）
    mock_answer = f"你好！你发送了：{request.message}。这是模拟回复内容。"
    chunk_size = 3  # 每块3个字符
    for i in range(0, len(mock_answer), chunk_size):
        chunk = mock_answer[i:i+chunk_size]
        yield format_sse(data={"content": chunk}, event="stream_chunk")
        await asyncio.sleep(0.05)  # 模拟延迟

    # 3. 模拟 references
    yield format_sse(
        data={"data": [{"filename": "test.pdf", "page_number": 1, "text": "示例引用"}]},
        event="references"
    )
    await asyncio.sleep(0.1)

    # 4. 发送 final_answer（完整答案）
    yield format_sse(data={"content": mock_answer}, event="final_answer")
    await asyncio.sleep(0.1)

    # 5. 发送 done（结束标记）
    yield format_sse(data={}, event="done")