import logging

import structlog
from structlog.contextvars import merge_contextvars


import logging


file_handler = logging.handlers.RotatingFileHandler(
    "app.log",
    maxBytes=10 * 1024 * 1024,  # 10MB
    backupCount=5,
    encoding="utf-8"
)
file_handler.setFormatter(
    structlog.stdlib.ProcessorFormatter(
        processor=structlog.processors.JSONRenderer(ensure_ascii=False)  # 文件中用 JSON 格式
    )
)

# 2. 创建控制台 Handler
console_handler = logging.StreamHandler()
console_handler.setFormatter(
    structlog.stdlib.ProcessorFormatter(
        processor=structlog.dev.ConsoleRenderer()  # 控制台用彩色格式
    )
)

# 3. 添加到 root logger
root_logger = logging.getLogger()
root_logger.setLevel(logging.INFO)
root_logger.addHandler(file_handler)
root_logger.addHandler(console_handler)

# 4. 配置 structlog（注意：末尾必须有 wrap_for_formatter）
structlog.configure(
    processors=[
        merge_contextvars,
        structlog.stdlib.filter_by_level,
        structlog.stdlib.add_logger_name,
        structlog.stdlib.add_log_level,
        structlog.processors.TimeStamper(fmt="iso"),
        structlog.processors.StackInfoRenderer(),
        structlog.processors.format_exc_info,
        structlog.stdlib.ProcessorFormatter.wrap_for_formatter,  # ← 关键！必须在最后
    ],
    logger_factory=structlog.stdlib.LoggerFactory(),  # ← 关键！桥接到标准 logging
    wrapper_class=structlog.stdlib.BoundLogger,
    cache_logger_on_first_use=True,
)
logger = structlog.get_logger()