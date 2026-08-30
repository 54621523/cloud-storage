import os
import io
from minio import Minio
from dotenv import load_dotenv
from .base_oss import BaseOSSClient
from typing import BinaryIO
from RAG.Logging import logger
load_dotenv()

class MinIOClient(BaseOSSClient):
    """MinIO 对象存储实现"""

    def __init__(self):
        self.client = Minio(
            endpoint=os.getenv("MINIO_ENDPOINT", "localhost:9000"),
            access_key=os.getenv("MINIO_ACCESS_KEY","6SzEi9BmvjcfaI1DGE7B"),
            secret_key=os.getenv("MINIO_SECRET_KEY","ZZU5eV9ZJuPZa5XQYruKsRFIllz2ZCNBk3siyHnY"),
            secure=False
        )

    async def upload_file(self, bucket_name: str, object_name: str, file_path: str) -> bool:
        # 使用 to_thread 将同步方法转为异步
        import asyncio
        try:
            await asyncio.to_thread(
                self.client.fput_object, bucket_name, object_name, file_path
            )
            return True
        except Exception as e:
            logger.info(f"MinIO Upload Error: {e}")
            return False

    async def download_file(self, bucket_name: str, object_name: str, file_path: str) -> bool:
        import asyncio
        try:
            await asyncio.to_thread(
                self.client.fget_object, bucket_name, object_name, file_path
            )
            return True
        except Exception as e:
            logger.info(f"MinIO Download Error: {e}")
            return False

    async def delete_file(self, bucket_name: str, object_name: str) -> bool:
        import asyncio
        try:
            await asyncio.to_thread(
                self.client.remove_object, bucket_name, object_name
            )
            return True
        except Exception as e:
            logger.info(f"MinIO Delete Error: {e}")
            return False
        
    
     # ✨ 实现新的抽象方法
    def get_file_stream(self, bucket_name: str, object_name: str) -> BinaryIO:
        """
        实现从 BaseOSSClient 继承的抽象方法。
        它负责从 MinIO 获取数据，并将其包装成一个 BytesIO 流返回。
        """
        try:
            # 1. 使用底层的 MinIO SDK 获取对象响应
            response = self.client.get_object(bucket_name, object_name)
            
            # 2. 读取全部数据
            file_data = response.read()
            
            # 3. 重要：立即关闭网络连接，但保留数据在内存中
            # 这样可以快速释放与 MinIO 服务器的连接，避免连接池耗尽
            response.close()
            response.release_conn()
            
            # 4. 将字节数据包装成一个内存中的文件流 (BytesIO)
            # 并设置 name 属性，方便 MarkItDown 等库根据后缀名判断文件类型
            file_stream = io.BytesIO(file_data)
            file_stream.name = object_name 
            
            return file_stream
        except Exception as e:
            # logger.error("未知错误")
            raise