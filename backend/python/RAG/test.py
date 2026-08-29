import os
from typing import List, Annotated, Literal
from typing_extensions import TypedDict

from dotenv import load_dotenv
#读取环境变量
load_dotenv()
api_key = os.getenv("LLM_API_KEY")
base_url = os.getenv("LLM_BASE_URL")
model_name = os.getenv("LLM_MODEL_ID")

from langchain_core.messages import HumanMessage, AIMessage, BaseMessage
from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder
from langchain_core.output_parsers import StrOutputParser
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from langgraph.graph import StateGraph, END
from langgraph.graph.message import add_messages
from langgraph.checkpoint.redis import RedisSaver
from redis import Redis
from core import embeddings_model

# 引入 Meilisearch 官方客户端
import meilisearch

# ==========================================
# 1. Meilisearch 初始化
# ==========================================
MEILISEARCH_URL = "http://localhost:7700"
MEILISEARCH_API_KEY = "oEkwo3F1GozGwD7lkeDevr_PMMNx5fgku1W1XOwDlW4"
INDEX_NAME = "document_index"

# 初始化 Meilisearch 客户端
client = meilisearch.Client(MEILISEARCH_URL, MEILISEARCH_API_KEY)

# 1. 安全地创建或获取索引
try:
    # create_index 返回的是 TaskInfo，需要等待它执行完成
    task_info = client.create_index(INDEX_NAME, {'primaryKey': 'chunkId'})
    client.wait_for_task(task_info.task_uid)
    print(f"索引 '{INDEX_NAME}' 创建成功。")
except meilisearch.errors.MeilisearchApiError as e:
    if e.code == 'index_already_exists':
        print(f"索引 '{INDEX_NAME}' 已存在，直接使用。")
    else:
        raise e

index = client.index(INDEX_NAME)
# --- 新增代码：配置 Meilisearch 的嵌入器 ---
# 这里的键名 'text-embedding-v3' 必须和你在 retrieve_node 中 search_params 里指定的名称完全一致
embedder_settings = {
    "text-embedding-v3": {
        "source": "userProvided",
        "dimensions": 1024, 
    }
}

# 更新索引设置
task_info = index.update_embedders(embedder_settings)
client.wait_for_task(task_info.task_uid)
print("Meilisearch 嵌入器配置成功。")
# --- 新增代码结束 ---

llm = ChatOpenAI(model_name=model_name, api_key=api_key, base_url=base_url)

# ==========================================
# 2. 定义 Agentic RAG 状态 (State)
# ==========================================
class AgentState(TypedDict):
    messages: Annotated[List[BaseMessage], add_messages]
    documents: List[dict]
    generation: str
    iterations: int

# ==========================================
# 3. 定义核心智能体节点 (Nodes)
# ==========================================
def retrieve_node(state: AgentState) -> AgentState:
    last_message = state["messages"][-1]
    query_text = last_message.content
    
    # 1. 使用 OpenAI Embeddings 将问题转换为向量
    query_vector = embeddings_model.embed_query(query_text)
    
    # 2. 直接调用 Meilisearch SDK 进行向量搜索
    search_params = {
        "vector": query_vector,
        "limit": 3,
        # 指定使用哪个 embedder 进行向量计算
        "hybrid": {
            "embedder": "text-embedding-v3"
        }
    }
    response = index.search("", search_params)
    
    # 3. 解析结果
    documents = [{"content": hit['text']} for hit in response['hits']]
    
    return {
        "documents": documents,
        "iterations": state.get("iterations", 0)
    }

def generate_node(state: AgentState) -> AgentState:
    context = "\n\n".join([doc["content"] for doc in state["documents"]])
    template = """你是一个问答助手。请仅根据以下检索到的上下文和历史对话回答问题。
如果上下文中没有答案，请礼貌地说明你不知道。
上下文：{context}
"""
    prompt = ChatPromptTemplate.from_messages([
        ("system", template),
        MessagesPlaceholder(variable_name="messages"),
    ])
    rag_chain = prompt | llm | StrOutputParser()
    generation = rag_chain.invoke({"messages": state["messages"], "context": context})
    return {"generation": generation, "messages": [AIMessage(content=generation)]}

def rewrite_query_node(state: AgentState) -> AgentState:
    template = """你是一个查询重写专家。请根据以下历史对话和原始问题，生成一个更适合向量检索的新问题。
原始问题：{question}
"""
    prompt = ChatPromptTemplate.from_template(template)
    rewrite_chain = prompt | llm | StrOutputParser()
    new_query = rewrite_chain.invoke({"question": state["messages"][-1].content})
    return {"messages": [HumanMessage(content=new_query)]}

def hallucination_check_node(state: AgentState) -> AgentState:
    template = """你是一个事实核查员。请判断以下答案是否完全由提供的上下文支撑。
上下文：{context}
答案：{answer}
如果答案有支撑，请仅回复 'SUPPORTED'，否则回复 'NOT_SUPPORTED'。
"""
    prompt = ChatPromptTemplate.from_template(template)
    check_chain = prompt | llm | StrOutputParser()
    context = "\n\n".join([doc["content"] for doc in state["documents"]])
    result = check_chain.invoke({"context": context, "answer": state["generation"]})
    return {"generation": result.strip()}

# ==========================================
# 4. 定义路由逻辑 (Routing Functions)
# ==========================================
def grade_documents(state: AgentState) -> Literal["generate", "rewrite_query"]:
    if not state["documents"] or state.get("iterations", 0) >= 2:
        return "generate"
    return "generate"

def check_hallucination(state: AgentState) -> Literal["end", "generate"]:
    if state["generation"] == "SUPPORTED":
        return "end"
    else:
        if state.get("iterations", 0) < 3:
            return "generate"
        return "end"

# ==========================================
# 5. 构建并编译 Agentic Graph
# ==========================================
workflow = StateGraph(AgentState)

workflow.add_node("retrieve", retrieve_node)
workflow.add_node("rewrite_query", rewrite_query_node)
workflow.add_node("generate", generate_node)
workflow.add_node("hallucination_check", hallucination_check_node)

workflow.set_entry_point("retrieve")
workflow.add_edge("retrieve", "rewrite_query")
workflow.add_conditional_edges("rewrite_query", grade_documents, {"generate": "generate", "rewrite_query": "rewrite_query"})
workflow.add_edge("generate", "hallucination_check")
workflow.add_conditional_edges("hallucination_check", check_hallucination, {"end": END, "generate": "generate"})

# 初始化 Redis Checkpointer 并编译图
redis_client = Redis(
    host="localhost",
    port=6379,
    decode_responses=True
)
checkpointer = RedisSaver(redis_client = redis_client)
checkpointer.setup()
app = workflow.compile(checkpointer=checkpointer)

# ==========================================
# 6. 运行测试
# ==========================================
if __name__ == "__main__":
    config = {"configurable": {"thread_id": "agent-session-meili-direct-001"}}
    inputs = {"messages": [HumanMessage(content="氡有什么用")]}
    
    for event in app.stream(inputs, config):
        for node_name, node_state in event.items():
            print(f"--- 正在执行节点: {node_name} ---")
            if node_name == "generate":
                print("AI 回答:", node_state["messages"][-1].content)
            elif node_name == "hallucination_check":
                print("事实核查结果:", node_state["generation"])