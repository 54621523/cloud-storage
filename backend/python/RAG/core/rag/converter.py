# core/rag/converter.py


import logging
from pathlib import Path
from typing import Optional, Dict, Any, BinaryIO
from markitdown import MarkItDown
from ..oss.base_oss import BaseOSSClient
from concurrent.futures import ThreadPoolExecutor
import asyncio
from ..oss_lifespan import get_oss_client
from fastapi import Depends
from typing import Annotated


# 创建一个专门的线程池用于 CPU/IO 密集型任务
_executor = ThreadPoolExecutor(max_workers=4)

logger = logging.getLogger(__name__)

class DocumentConverter:
    """文档转Markdown转换器"""
    
    def __init__(
        self,
        oss_client: BaseOSSClient,
        markitdown_config: Optional[Dict[str, Any]] = None,
        cache_enabled: bool = True,
        cache_maxsize: int = 128
    ):
        """
        初始化转换器
        
        Args:
            oss_client: oss客户端实例
            markitdown_config: MarkItDown配置参数
            cache_enabled: 是否启用缓存
            cache_maxsize: 缓存最大大小
        """
        self.oss_client = oss_client
        self.markitdown_config = markitdown_config or {}
        self.cache_enabled = cache_enabled
        self.cache_maxsize = cache_maxsize
        
        self._md_instance: Optional[MarkItDown] = None
        self._supported_extensions = {
            '.pdf', '.docx', '.pptx', '.xlsx', 
            '.html', '.txt', '.md', '.csv', '.json'
        }
        
        # 配置缓存
        self._cache = {}
        self._cache_access_order = []
    
    @property
    def md_instance(self) -> MarkItDown:
        """懒加载MarkItDown实例"""
        if self._md_instance is None:
            self._md_instance = MarkItDown(**self.markitdown_config)
        return self._md_instance
    
    def is_supported(self, filename: str) -> bool:
        """检查文件格式是否支持"""
        ext = Path(filename).suffix.lower()
        return ext in self._supported_extensions
    

    
    def _get_cache_key(self, bucket_name: str, object_name: str) -> str:
        """生成缓存键"""
        return f"{bucket_name}/{object_name}"
    
    def _get_from_cache(self, bucket_name: str, object_name: str) -> Optional[str]:
        """从缓存获取转换结果"""
        if not self.cache_enabled:
            return None
            
        key = self._get_cache_key(bucket_name, object_name)
        return self._cache.get(key)
    
    def _set_cache(self, bucket_name: str, object_name: str, content: str):
        """设置缓存"""
        if not self.cache_enabled or not content:
            return
            
        key = self._get_cache_key(bucket_name, object_name)
        
        # LRU缓存管理
        if key in self._cache:
            self._cache_access_order.remove(key)
        elif len(self._cache) >= self.cache_maxsize:
            # 移除最旧的条目
            oldest_key = self._cache_access_order.pop(0)
            del self._cache[oldest_key]
        
        self._cache[key] = content
        self._cache_access_order.append(key)

    def _get_file_from_oss(self, bucket_name: str, object_name: str) -> BinaryIO:
        """
        从 OSS 获取文件流。
        现在它只调用抽象方法，完全不依赖任何具体的 SDK。
        """
        # 直接调用抽象基类定义的方法，干净利落！
        return self.oss_client.get_file_stream(bucket_name, object_name)

    def convert_from_oss(
        self,
        bucket_name: str,
        object_name: str,
        use_cache: bool = True
    ) -> str:
        """
        从 OSS 转换文档为 Markdown
        """
        try:
            # 1. 检查缓存
            if use_cache and self.cache_enabled:
                cached_content = self._get_from_cache(bucket_name, object_name)
                if cached_content is not None:
                    logger.info(f"命中缓存: {bucket_name}/{object_name}")
                    return cached_content

            # 2. 获取文件流 (核心变化！)
            # 我们得到了一个标准的 BinaryIO 对象，后续操作与具体 OSS 实现无关
            file_stream = self._get_file_from_oss(bucket_name, object_name)
            
            try:
                # 3. 执行转换
                # 注意：这里需要确保 file_stream.name 已设置，以便 MarkItDown 识别类型
                markdown_text = self.convert_from_stream(file_stream, Path(object_name).name)
                
                # 4. 缓存结果
                if markdown_text and use_cache:
                    self._set_cache(bucket_name, object_name, markdown_text)
                    
                return markdown_text
            finally:
                # 5. 关键：确保文件流被关闭，释放内存
                file_stream.close()

        except Exception as e:
            logger.exception(f"从 OSS 转换文档失败 {bucket_name}/{object_name}: {e}")
            return ""
        
    async def convert_from_oss_async(
        self, 
        bucket_name: str, 
        object_name: str, 
        use_cache: bool = True
    ) -> str:
        """
        异步包装器：防止阻塞主事件循环
        """
        loop = asyncio.get_event_loop()
        # 将耗时的同步操作放入线程池执行
        return await loop.run_in_executor(
            _executor, 
            self.convert_from_oss, 
            bucket_name, 
            object_name, 
            use_cache
        )

    def convert_from_stream(
        self,
        file_stream: BinaryIO,
        filename: str,
        metadata: Optional[Dict[str, Any]] = None
    ) -> str:
        """
        从文件流转换文档为Markdown。
        这是执行实际转换的核心方法。

        Args:
            file_stream: 文件流对象
            filename: 文件名（用于判断格式）
            metadata: 文件元数据（可选）

        Returns:
            转换后的Markdown文本
        """
        try:
            # 1. 检查格式是否支持
            if not self.is_supported(filename):
                logger.warning(f"不支持的文件格式: {Path(filename).suffix}")
                return ""

            # 2. 确保文件流的指针在开头
            # 这是一个很重要的细节，防止传入的流指针不在起始位置
            file_stream.seek(0)

            # 3. 确保 MarkItDown 能识别文件类型
            # 很多库（包括 MarkItDown）会根据文件对象的 .name 属性来判断文件类型
            if not hasattr(file_stream, 'name'):
                file_stream.name = filename # type: ignore

            logger.info(f"开始转换: {filename}")

            # 4. 执行转换
            # 注意：这里假设 MarkItDown 的 convert 方法返回一个有 .text_content 属性的对象
            result = self.md_instance.convert(file_stream)
            markdown_text = getattr(result, "text_content", "")

            if isinstance(markdown_text, str) and markdown_text.strip():
                # 5. 清理并返回结果
                cleaned_text = self._clean_markdown(markdown_text)
                logger.info(f"转换成功: {filename} -> {len(cleaned_text)} 字符")
                return cleaned_text
            else:
                logger.warning(f"转换结果为空或无效: {filename}")
                return ""

        except Exception as e:
            logger.exception(f"处理文件流失败 {filename}: {e}")
            return ""    
    
    
    
    
    def _clean_markdown(self, text: str) -> str:
        """
        文本清理：为 LLM 提供干净的输入
        """
        if not text:
            return ""
        
        # 1. 统一换行符：将 Windows 的 \r\n 和旧 Mac 的 \r 全部转为标准的 \n
        text = text.replace('\r\n', '\n').replace('\r', '\n')
        
        # 2. 移除不可见字符（如零宽空格、BOM头等），这些对 LLM 是纯噪音
        # 注意：这里保留了 \n 和 \t，只移除其他控制字符
        import re
        text = re.sub(r'[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]', '', text)
        
        # 3. 智能压缩空行：将连续的 3 个及以上换行符，压缩为 2 个（保留一个空行）
        text = re.sub(r'\n{3,}', '\n\n', text)
        
        # 4. 去除每行首尾的多余空格，但保留段落缩进结构
        lines = text.splitlines()
        cleaned_lines = [line.rstrip() for line in lines]
        
        return "\n".join(cleaned_lines).strip()
    
    def clear_cache(self):
        """清空转换缓存"""
        self._cache.clear()
        self._cache_access_order.clear()
        logger.info("缓存已清空")
    
    def delete_from_cache(self, bucket_name: str, object_name: str):
        """删除指定文件的缓存"""
        key = self._get_cache_key(bucket_name, object_name)
        if key in self._cache:
            del self._cache[key]
            self._cache_access_order.remove(key)
            logger.info(f"已删除缓存: {key}")


async def get_converter(
        oss_client: Annotated[BaseOSSClient,Depends(get_oss_client)]
) -> DocumentConverter:
    return DocumentConverter(oss_client=oss_client)