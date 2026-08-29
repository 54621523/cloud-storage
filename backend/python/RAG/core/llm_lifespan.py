import os



from langchain_openai import ChatOpenAI
from dotenv import load_dotenv
from .lifespan_manager import manager

load_dotenv()
api_key = os.getenv("LLM_API_KEY")
base_url = os.getenv("LLM_BASE_URL")
model_name = os.getenv("LLM_MODEL_ID")

@manager.register
async def get_llm():
    llm = ChatOpenAI(
        model=model_name,
        api_key=api_key,
        base_url=base_url
    )
    print("✅ LLM 客户端已启动")
    yield llm
    # ChatOpenAI 通常没有 close 方法，但如果有需要可以清理
    print("✅ LLM 客户端已关闭")