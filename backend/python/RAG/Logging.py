import logging
from logging.handlers import QueueHandler, QueueListener, RotatingFileHandler
from queue import Queue
import os
from typing import Optional

# 全局单例
_log_queue: Optional[Queue] = None
_log_listener: Optional[QueueListener] = None
_initialized: bool = False

def setup_logging() -> QueueListener:
    """初始化日志系统（只执行一次）"""
    global _log_queue, _log_listener, _initialized
    
    if _initialized:
        return _log_listener
    
    # 创建队列
    _log_queue = Queue(-1)
    queue_handler = QueueHandler(_log_queue)
    
    # 文件处理器（带轮转）
    log_file = os.getenv("LOG_FILE", "app.log")
    file_handler = RotatingFileHandler(
        log_file,
        maxBytes=10_000_000,  # 10MB
        backupCount=5,
        encoding='utf-8'
    )
    
    # 控制台处理器
    console_handler = logging.StreamHandler()
    
    # 统一格式
    formatter = logging.Formatter(
        '%(asctime)s - %(name)s - %(levelname)s - %(message)s',
        datefmt='%Y-%m-%d %H:%M:%S'
    )
    file_handler.setFormatter(formatter)
    console_handler.setFormatter(formatter)
    
    # 启动监听器
    _log_listener = QueueListener(_log_queue, file_handler, console_handler)
    _log_listener.start()
    
    # 配置 root logger
    root_logger = logging.getLogger()
    root_logger.addHandler(queue_handler)
    root_logger.setLevel(logging.INFO)
    
    # 降低第三方库日志级别
    logging.getLogger("redis").setLevel(logging.WARNING)
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("urllib3").setLevel(logging.WARNING)
    
    _initialized = True
    return _log_listener

def get_logger(name: str) -> logging.Logger:
    """获取日志器（确保日志系统已初始化）"""
    if not _initialized:
        setup_logging()
    return logging.getLogger(name)

def cleanup_logging():
    """清理日志系统（只在应用关闭时执行）"""
    global _log_listener, _initialized
    if _log_listener:
        _log_listener.stop()
        _initialized = False