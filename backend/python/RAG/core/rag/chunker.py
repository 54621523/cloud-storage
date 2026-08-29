# core/rag/chunker.py

from langchain_text_splitters import RecursiveCharacterTextSplitter
from typing import List, Optional
import tiktoken

class TextChunkingService:
    """
    基于 LangChain 的文本分块服务。
    专为 LLM 和 RAG 系统设计，支持按 Token 精确切分。
    """
    def __init__(
        self, 
        chunk_size: Optional[int] = None, 
        chunk_overlap: Optional[int] = None,
        encoding_name: str = "cl100k_base"
    ):
        # 使用类属性作为默认值，方便统一管理
        self.DEFAULT_CHUNK_SIZE = 500
        self.DEFAULT_CHUNK_OVERLAP = 50
        
        # 如果传入了值就用传入值，否则用默认值
        self.chunk_size = chunk_size if chunk_size is not None else self.DEFAULT_CHUNK_SIZE
        self.chunk_overlap = chunk_overlap if chunk_overlap is not None else self.DEFAULT_CHUNK_OVERLAP
        
        # 参数校验
        if self.chunk_overlap >= self.chunk_size:
            raise ValueError(f"chunk_overlap({self.chunk_overlap}) 必须小于 chunk_size({self.chunk_size})")
        
        # 使用 tiktoken 计算真实的 Token 数量，而不是简单的字符数
        def tiktoken_len(text: str) -> int:
            encoder = tiktoken.get_encoding(encoding_name)
            return len(encoder.encode(text))
            
        # 初始化 LangChain 的递归字符分割器
        self.splitter = RecursiveCharacterTextSplitter(
            chunk_size=self.chunk_size,
            chunk_overlap=self.chunk_overlap,
            length_function=tiktoken_len,
            separators=["\n\n", "\n", "。", "！", "？", "；", "，", " ", ""],
            is_separator_regex=False
        )

    def split_text(self, text: str) -> List[str]:
        """
        将长文本切分为多个语义连贯的块。
        """
        if not text or not text.strip():
            return []
        return self.splitter.split_text(text)
    
    def update_defaults(self, chunk_size: Optional[int] = None, chunk_overlap: Optional[int] = None):
        """动态更新默认参数（可选功能）"""
        if chunk_size is not None:
            self.DEFAULT_CHUNK_SIZE = chunk_size
        if chunk_overlap is not None:
            self.DEFAULT_CHUNK_OVERLAP = chunk_overlap
    

async def get_chunker(
    chunk_size: Optional[int] = None,
    chunk_overlap: Optional[int] = None,
) -> TextChunkingService:
    """获取分块服务实例"""
    return TextChunkingService(
        chunk_size=chunk_size,
        chunk_overlap=chunk_overlap
    )