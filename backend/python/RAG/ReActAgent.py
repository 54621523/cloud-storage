import os
from typing import Annotated, List, Callable

from dotenv import load_dotenv
from typing_extensions import TypedDict

from langchain_core.tools import tool
from langchain_core.runnables import RunnableConfig
from langchain_core.vectorstores import VectorStore
from langchain_core.messages import HumanMessage, AIMessage, ToolMessage, BaseMessage
from langchain_openai import ChatOpenAI
from langgraph.graph import StateGraph, START, END
from langgraph.graph.message import add_messages
from langgraph.prebuilt import ToolNode
from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder
from langgraph.checkpoint.base import BaseCheckpointSaver

from RAG.Logging import logger
load_dotenv()

# ================= 1. 定义 ReAct 工具集 =================

@tool
def search_knowledge_base(
    query: str,
    config: RunnableConfig
) -> str:
    """根据用户的问题，从本地知识库中检索相关的背景信息或事实。当需要回答特定领域问题或查询资料时使用此工具。"""
    try:
        vectorstore = config["configurable"].get("vectorstore")
        if not vectorstore:
            return "错误：系统未正确注入 vectorstore，请检查配置。"
        docs = vectorstore.similarity_search(query=query, k=3)

        
        if not docs:
            return "未在知识库中找到相关信息。"
            
        # 提取 Document 的 page_content 并拼接
        results = [doc.page_content for doc in docs]

        return "\n\n---\n\n".join(results)
        
    except Exception as e:
        return f"知识库检索失败: {str(e)}"


# ================= 2. 定义 LangGraph 状态与节点 =================
class AgentState(TypedDict):
    messages: Annotated[List[BaseMessage], add_messages]

SYSTEM_PROMPT = """你是一个游戏数据库智能助手。你的所有回答必须**绝对、完全**基于 `search_knowledge_base` 工具返回的知识库内容。

可用工具：
- search_knowledge_base(query): 从知识库检索信息

【核心行为准则】
1. 绝对服从知识库：知识库中的设定（哪怕是虚构的、违背现实物理定律的）就是唯一的“真理”。你必须完全按照知识库的内容进行回答。
2. 严禁使用外部常识：绝对不要使用你自身预训练的现实世界知识、科学常识或历史事实来纠正、质疑或补充知识库的内容。
3. 严禁出戏：不要在回答中提及“这是游戏设定”、“在现实中并非如此”、“科幻作品”等字眼。你必须完全沉浸在这个世界观中。
4. 诚实原则：如果知识库中没有相关信息，请直接回答“知识库中未找到相关信息”，不要试图用现实知识去编造答案。

【工具使用指南】
1. 专业问题/名词解释 → 必须优先使用 `search_knowledge_base` 检索信息。
2. 不确定时 → 诚实说明。
"""

# 将 prompt 和 graph 构建封装为工厂函数，接收外部注入的依赖
def build_agent_graph(
    llm: ChatOpenAI, 
    vectorstore: VectorStore, 
    checkpointer: BaseCheckpointSaver
):
    """
    构建并编译 ReAct Agent 图。
    所有外部依赖（LLM、VectorStore、Checkpointer）均通过参数传入。
    """
    prompt_template = ChatPromptTemplate.from_messages([
        ("system", SYSTEM_PROMPT),
        MessagesPlaceholder(variable_name="messages"),
    ])

    # 绑定工具到 LLM
    tools = [search_knowledge_base]
    model_with_tools = llm.bind_tools(tools)


    # LLM 节点
    def llm_node(state: AgentState):
        chain = prompt_template | model_with_tools
        response = chain.invoke({"messages": state["messages"]})
        logger.info("LLM 原始工具调用意图:", response.tool_calls)
        return {"messages": [response]}

    # 工具节点
    tool_node = ToolNode(tools)

    # 条件边：判断是否需要继续调用工具
    def should_continue(state: AgentState):
        last_message = state["messages"][-1]
        if last_message.tool_calls:
            return "tools"
        return END

    # 构建图
    graph = StateGraph(AgentState)
    graph.add_node("llm", llm_node)
    graph.add_node("tools", tool_node)

    graph.add_edge(START, "llm")
    graph.add_edge("tools", "llm")
    graph.add_conditional_edges("llm", should_continue)

    # 编译图，并传入 checkpointer
    return graph.compile(checkpointer=checkpointer)