import os
from typing import Annotated, Literal, TypedDict, List, Optional
import operator

from dotenv import load_dotenv
from typing_extensions import TypedDict

from RAG.Logging import logger



from langchain.chat_models import init_chat_model
from langgraph.config import get_stream_writer
from langgraph.types import Send,Command
from pydantic import BaseModel, Field
from langchain_core.tools import tool
from langchain_core.runnables import RunnableConfig
from langchain_core.vectorstores import VectorStore
from langchain_core.messages import HumanMessage, AIMessage, ToolMessage, BaseMessage, SystemMessage
from langchain_openai import ChatOpenAI
from langgraph.graph import StateGraph, START, END
from langgraph.graph.message import add_messages
from langgraph.prebuilt import ToolNode
from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder
from langgraph.checkpoint.base import BaseCheckpointSaver

load_dotenv()

API_KEY = os.getenv("LLM_API_KEY")
BASE_URL = os.getenv("LLM_BASE_URL")


MODEL = os.getenv("LLM_MODEL")
GRADE_MODEL = os.getenv("GRADE_MODEL", "gpt-4.1")
FAST_MODEL = os.getenv("FAST_MODEL") or MODEL



_grader_model = None
_router_model = None
_complexity_model = None


def _get_grader_model():
    global _grader_model
    if not API_KEY or not GRADE_MODEL:
        return None
    if _grader_model is None:
        _grader_model = init_chat_model(
            model=GRADE_MODEL,
            model_provider="openai",
            api_key=API_KEY,
            base_url=BASE_URL,
            temperature=0,
            stream_usage=True,
        )
    return _grader_model

def _get_router_model():
    global _router_model
    if not API_KEY or not MODEL:
        return None
    if _router_model is None:
        _router_model = init_chat_model(
            model=MODEL,
            model_provider="openai",
            api_key=API_KEY,
            base_url=BASE_URL,
            temperature=0,
            stream_usage=True,
        )
    return _router_model


def _get_complexity_model():
    """FAST_MODEL 用于问题复杂度分类和子问题分解。"""
    global _complexity_model
    if not API_KEY or not FAST_MODEL:
        return None
    if _complexity_model is None:
        _complexity_model = init_chat_model(
            model=FAST_MODEL,
            model_provider="openai",
            api_key=API_KEY,
            base_url=BASE_URL,
            temperature=0,
            stream_usage=True,
        )
    return _complexity_model

class GradeDocuments(BaseModel):
    """评估文档相关性，相关|不相关"""

    binary_score: str = Field(
        description="Relevance score: 'yes' if relevant, or 'no' if not relevant"
    )

class RewriteStrategy(BaseModel):
    """选择重写策略，重新生成|hyDE|复杂问题"""

    strategy: Literal["step_back", "hyde", "complex"]

class ComplexityResult(BaseModel):
    """问题复杂度分类结果。"""

    complexity: Literal["simple", "complex"] = Field(
        description="问题复杂度：'simple' 为简单问题，'complex' 为复杂问题"
    )
    reason: str = Field(default="", description="分类理由")


class SubQuestions(BaseModel):
    """复杂问题分解后的子问题列表。"""

    sub_questions: List[str] = Field(
        description="2-4 个独立子问题，每个聚焦原问题的一个方面",
        min_length=1,
        max_length=4,
    )

def deduplicate_chunks_reducer(prev_chunks: List[dict], new_chunks: List[dict]) -> List[dict]:
    """
    这个函数会在子图状态合并到主图时被调用。
    prev_chunks: 主图中已存在的 chunks (来自之前已完成的其他子图)
    new_chunks: 当前正在合并的子图返回的 chunks
    """
    # 将新旧 chunks 合并
    combined_chunks = prev_chunks + new_chunks
    
    # 使用一个集合来记录已见过的 doc_id，实现去重
    seen_doc_ids = set()
    unique_chunks = []
    
    for chunk in combined_chunks:
        doc_id = chunk.get("doc_id")
        if doc_id is not None and doc_id not in seen_doc_ids:
            seen_doc_ids.add(doc_id)
            unique_chunks.append(chunk)
            
    return unique_chunks

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
# 主图状态
class AgentState(TypedDict):
    messages: Annotated[List[BaseMessage], add_messages]
    intent: Literal["chit_chat", "knowledge_qa"]    #存储意图
    complexity: str  # 'simple' 或 'complex'
    sub_questions: List[str]  # 复杂问题拆分后的子问题列表
    sub_answers: Annotated[List[str], operator.add] #子问题的回答
    original_question: str  #原始问题
    final_answer: str  # 复杂问题合并后的最终答案
    allowed_doc_ids: Annotated[Optional[List[int]], operator.add]
    all_retrieved_chunks: Annotated[List[dict], deduplicate_chunks_reducer]

# 简单路径子图状态
class SubGraphState(TypedDict):
    question: str  # 当前要解决的子问题
    messages: Annotated[List[BaseMessage], add_messages]
    retry_count: int  # 重写重试次数
    is_relevant: bool
    sub_answers: str
    should_stop: bool
    allowed_doc_ids: Optional[List[int]] = None
    all_retrieved_chunks: Annotated[List[dict], deduplicate_chunks_reducer]


def build_simple_subgraph(llm: ChatOpenAI, vectorstore):
    """构建带自我修正的简单检索子图"""


    def init_subgraph_node(state: SubGraphState):
        """
        子图初始化节点：专门用于处理状态合并和参数提取。
        如果 Send 传入的 question 丢失，这里负责从 messages 中安全提取。
        """
    
        # 尝试获取 question
        question = state.get("question")
        if not question:
            logger.warn("初始化节点警告：question 缺失")
            return{
                "should_stop": True
            }
        if isinstance(question, list):
            question = question[0] if question else ""
        elif not isinstance(question, str):
            question = str(question) if question is not None else ""
        doc_ids = state.get("allowed_doc_ids")
        if not doc_ids:
            logger.warn("初始化节点警告： allowed_doc_ids 缺失")
            return{
                "should_stop": True
            }


    
        # 返回完整的状态字典, LangGraph 将其合并到子图 State 中
        return {
            "question": question,
            "messages": state.get("messages", []),
            "retry_count": state.get("retry_count", 0),
            "allowed_doc_ids": state.get("allowed_doc_ids")
        }
    
    # 1. 检索节点
    def retrieve_node(state: SubGraphState):
    
        # 1.获取 信息
        question = state["question"]
        metadata_filter = {}
        allowed_doc_ids = state["allowed_doc_ids"]
        metadata_filter = {"metadata.doc_id": {"$in": allowed_doc_ids}}

        # 2. 执行检索
        docs = vectorstore.similarity_search(query=question, k=3, filter=metadata_filter)


        retrieved_data = []
        content_parts = []
        for doc in docs:
        # 提取元数据，防止为空
            doc_id = doc.metadata.get("doc_id")
            # page = doc.metadata.get("page", 0)
            content_parts.append(doc.page_content)
        
            retrieved_data.append({
                "doc_id": doc_id,
                # "page_number": page,
                "text": doc.page_content[:200] + "..." # 只传摘要给前端展示，避免流量过大
            })

            contexts = [doc.page_content for doc in docs]
            logger.info(
                "retrieved_contexts",
                question=state["question"],
                contexts=contexts,
                doc_ids=[doc.metadata.get("doc_id") for doc in docs],
            )
    
        # 3. 返回结果
        return {
            "messages": [AIMessage(content="\n\n---\n\n".join(content_parts), name="retrieval_result")],
            "all_retrieved_chunks": retrieved_data
        }

    # 2. 子问题答案生成节点
    def generate_node(state: SubGraphState):
        prompt = ChatPromptTemplate.from_messages([
            ("system", "你是一个游戏数据库智能助手。请绝对、完全基于以下知识库内容回答问题。如果知识库没有相关信息，请诚实说明。严禁使用外部常识。\n\n知识库内容：\n{context}"),
            ("human", "{question}")
        ])
        chain = prompt | llm
        response = chain.invoke({"context": state["messages"][-1].content, "question": state["question"]})
        return {"messages": [response],
                "sub_answers": [response.content]
            }

    # 3. 相关性打分节点
    def grade_node(state: SubGraphState):
        context = state["messages"][-2].content  # 检索到的文档
        answer = state["messages"][-1].content   # 生成的回答
        grade_prompt = ChatPromptTemplate.from_template(
            "你是一个评估专家。请判断以下回答是否完全基于提供的文档内容，且准确回答了问题。\n"
            "文档：{context}\n回答：{answer}\n"
            "请仅返回 'yes' 或 'no'。"
        )
        chain = grade_prompt | llm
        result = chain.invoke({"context": context, "answer": answer}).content.lower()
        return {"is_relevant": "yes" in result}

    # 4. 重写节点
    def rewrite_node(state: SubGraphState):
        rewrite_prompt = ChatPromptTemplate.from_template(
            "原始问题未能从知识库中找到有效答案。请重写这个问题，使其更具体或换个角度，以便更好地检索游戏数据库。\n"
            "原始问题：{question}\n重写后的问题："
        )
        chain = rewrite_prompt | llm
        new_question = chain.invoke({"question": state["question"]}).content
        return {"question": new_question, "retry_count": state.get("retry_count", 0) + 1}

    # 路由逻辑
    def should_continue_or_rewrite(state: SubGraphState):
        if state.get("retry_count", 0) >= 2:  # 最多重写2次
            return "end" 
        return "rewrite" if not state.get("is_relevant") else "end"

    # 构建子图
    sub_graph = StateGraph(SubGraphState)
    sub_graph.add_node("init",init_subgraph_node)
    sub_graph.add_node("retrieve", retrieve_node)
    sub_graph.add_node("generate", generate_node)
    sub_graph.add_node("grade", grade_node)
    sub_graph.add_node("rewrite", rewrite_node)

    sub_graph.add_edge(START,"init")
    sub_graph.add_edge("init", "retrieve")
    sub_graph.add_edge("retrieve", "generate")
    sub_graph.add_edge("generate", "grade")
    sub_graph.add_conditional_edges("grade", should_continue_or_rewrite, {
        "rewrite": "rewrite",
        "end": END
    })
    sub_graph.add_edge("rewrite", "retrieve")

    return sub_graph.compile()

def build_agent_graph(llm: ChatOpenAI, vectorstore, checkpointer: BaseCheckpointSaver, allowed_doc_ids: Optional[List[int]]):
    simple_subgraph = build_simple_subgraph(llm, vectorstore)


    def intent_node(state: AgentState):
        """
        意图识别
        """
        prompt = ChatPromptTemplate.from_template(
            "你是一个意图识别专家。请分析用户的问题，并按以下JSON格式返回决策结果：\n"
            "{{\n"
            '  "intent": "chit_chat" 或 "knowledge_qa",\n'
            '  "complexity": "simple" 或 "complex" (仅在 intent 为 knowledge_qa 时需要)\n'
            "}}\n"
            "判断标准：\n"
            "- 闲聊 (chit_chat): 问候、日常对话、与知识库无关的话题。\n"
            "- 简单问答 (knowledge_qa + simple): 意图单一，可以直接在知识库中找到答案。\n"
            "- 复杂问答 (knowledge_qa + complex): 包含多个独立意图、需要多步推理或对比。\n"
            "\n用户问题：{question}"
        )
        
        # 使用快速模型进行路由决策
        router_model = _get_router_model() or llm
        response = (prompt | router_model).invoke({"question": state["original_question"]})
        
        # 解析 LLM 返回的 JSON
        import json
        try:
            decision = json.loads(response.content)
            intent = decision.get("intent", "knowledge_qa")
            complexity = decision.get("complexity", "simple")
        except json.JSONDecodeError:
            # 解析失败时，安全兜底为知识问答-简单路径
            intent = "knowledge_qa"
            complexity = "simple"

        # 将决策和复杂度信息存入 state，供后续节点使用
        return {
            "intent": intent,
            "complexity": complexity,
            "allowed_doc_ids": allowed_doc_ids
        }
    
    # 拆分子问题
    def split_complex_question_node(state: AgentState):
        """
        专门用于拆分复杂问题。它假设自己只会在 complexity 为 complex 时被调用。
        """
        prompt = ChatPromptTemplate.from_template(
            "请将以下复杂问题拆分为多个独立的、相同语义的、可以在知识库中单独检索的子问题。不要扩充不在原本语义的内容。请以 JSON 列表格式返回。\n"
            "问题：{question}\n"
            "子问题列表："
        )
        # 使用快速模型进行拆分
        fast_model = _get_complexity_model() or llm
        res = (prompt | fast_model).invoke({"question": state["original_question"]}).content
        
        import json
        try:
            sub_questions = json.loads(res)
        except:
            sub_questions = [state["original_question"]]
            
        return {"sub_questions": sub_questions}

    
    # --- 闲聊回复节点 ---
    def chit_chat_node(state: AgentState):
        """
        直接调用 LLM 处理闲聊，不进行任何检索。
        """
        prompt = ChatPromptTemplate.from_messages([
            ("system", "你是一个友好、乐于助人的AI助手。请用自然、亲切的语气与用户进行日常交流。"),
            ("human", "{question}")
        ])
        response = (prompt | llm).invoke({"question": state["original_question"]}).content
        return {"sub_answers": [response]}


    # 2. 结果合并节点
    async def merge_results_node(state: AgentState):
        sub_answers = state.get("sub_answers", [])
        original_question = state.get("original_question","原始问题已丢失")
        

        # 防御性检查
        if not sub_answers:
            return {"messages": [AIMessage(content="未能获取到有效的子问题解答。")]}

        # 如果只有一个答案，直接返回
        if len(sub_answers) == 1:
            final_answer = sub_answers[0]
            return {"messages": [AIMessage(content=final_answer)],
                    "final_answer": final_answer
                    }
        else:
            # 将子答案拼接，并用 XML 标签包裹，防止模型混淆指令与内容
            formatted_answers = "\n---\n".join(sub_answers)
            
            merge_prompt = ChatPromptTemplate.from_template(
                """你是一个严谨的客观事实总结助手。请严格根据以下 <sub_answers> 标签内的【子任务回答】，来回答用户的【原始问题】。

                【原始问题】: {original_question}

                【绝对红线与严格规则】：
                1. 绝对忠于原文：仅使用 <sub_answers> 中明确出现的信息，严禁使用任何外部知识、常识或进行逻辑推测。
                2. 禁止脑补细节：严禁编造或推测任何具体的数值（如温度、比例、增产百分比）、物理原理或设备名称。
                3. 强制兜底话术：如果 <sub_answers> 中未提供某个问题的完整信息，你**必须**回复：“知识库中未提供该信息的具体细节”，**绝对禁止**用其他内容填补空白。
                4. 禁止过度升华：语言风格必须平实、客观。严禁使用“高维技术”、“协议层”、“战略耦合节点”等夸张、修辞或过度总结的词汇。

                <sub_answers>
                {answers}
                </sub_answers>
                """
            )
            
            # 低 Temperature，抑制模型的创造力和发散性
            structured_llm = llm.bind(temperature=0.0) 

            # 收集完整的回答，以便最后更新 State
            full_content = ""        
            # 使用 astream 逐字获取大模型输出
            async for chunk in (merge_prompt | structured_llm).astream({
                "original_question": original_question,
                "answers": formatted_answers
            }):
                if hasattr(chunk, "content") and chunk.content:
                    full_content += chunk.content
        return {"messages": [AIMessage(content=full_content)],
                "final_answer": full_content}
    def route_by_complexity(state: AgentState):
        """根据复杂度决定是走简单路径，还是扇出多个子任务"""
        if state["intent"] == "chit_chat":
            return "chit_chat"
        else:
            if state["complexity"] == "simple":
                # 简单问题：返回单个 Send
                return [
                    Send("simple_subgraph", {
                        "question": state["original_question"], 
                        "messages": [state["messages"][-1]], 
                        "retry_count": 0,
                        "allowed_doc_ids": state["allowed_doc_ids"]
                    })
                ]
            else:
                # 复杂问题：先进行拆分
                return "split_complex"
            
    def fan_out_to_subgraphs(state: AgentState):
        """
        条件边路由函数：读取拆分后的子问题，动态生成 Send 指令并行分发。
        """
        sub_questions = state.get("sub_questions", [])
        if not sub_questions:
            # 兜底逻辑：如果没有拆分出子问题，直接走合并节点或结束
            return "merge" 

        # 返回 Send 列表，触发并行执行
        return [
            Send(
                "simple_subgraph",
                {
                    "question": sub_q,
                    "messages": [],
                    "retry_count": 0,
                    "allowed_doc_ids": state["allowed_doc_ids"]
                }
            )
            for sub_q in sub_questions
        ]

    # 构建主图
    graph = StateGraph(AgentState)
    graph.add_node("intent", intent_node)
    graph.add_node("chit_chat", chit_chat_node)
    graph.add_node("split_complex", split_complex_question_node)
    graph.add_node("simple_subgraph", simple_subgraph)
    graph.add_node("merge", merge_results_node)

    graph.add_edge(START, "intent")
    graph.add_conditional_edges("intent", route_by_complexity, {
        "chit_chat": "chit_chat",
        "split_complex": "split_complex",
    })
    graph.add_conditional_edges("split_complex", fan_out_to_subgraphs)
    
    
    graph.add_edge("simple_subgraph", "merge")
    graph.add_edge("chit_chat", "merge")
    graph.add_edge("merge", END)

    return graph.compile(checkpointer=checkpointer)