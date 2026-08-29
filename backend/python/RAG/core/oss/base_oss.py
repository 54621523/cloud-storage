from abc import ABC, abstractmethod
from typing import BinaryIO, Dict, Any

class BaseOSSClient(ABC):
    """对象存储抽象基类，统一接口规范"""

    @abstractmethod
    async def upload_file(self, bucket_name: str, object_name: str, file_path: str) -> bool:
        """上传文件到存储桶"""
        pass

    @abstractmethod
    async def download_file(self, bucket_name: str, object_name: str, file_path: str) -> bool:
        """从存储桶下载文件"""
        pass

    @abstractmethod
    async def delete_file(self, bucket_name: str, object_name: str) -> bool:
        """从存储桶删除文件"""
        pass


    @abstractmethod
    def get_file_stream(self, bucket_name: str, object_name: str) -> BinaryIO:
        """
        从 OSS 获取一个文件的二进制流。
        这个方法应该是同步的，因为它返回的是一个文件流对象，后续的读取操作
        将由调用方（如 MarkItDown）在同步上下文中处理。
        
        Args:
            bucket_name: 存储桶名称
            object_name: 对象名称（文件路径）
            
        Returns:
            一个支持 read() 方法的二进制流对象 (BinaryIO)。
            调用方有责任在使用完毕后关闭这个流。
        """
        pass