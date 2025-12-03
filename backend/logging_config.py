# START OF FILE backend/logging_config.py
"""
Centralized logging configuration for NoSlop.

Provides structured logging with multiple handlers, log levels, and context injection.
Supports both development (console) and production (rotating files) modes.
"""

import logging
import logging.handlers
import sys
import json
from datetime import datetime
from pathlib import Path
from typing import Optional, Dict, Any
import os


class StructuredFormatter(logging.Formatter):
    """
    Custom formatter that outputs structured JSON logs.
    Includes timestamp, level, message, and optional context.
    """
    
    def format(self, record: logging.LogRecord) -> str:
        """Format log record as JSON."""
        log_data = {
            "timestamp": datetime.utcnow().isoformat() + "Z",
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }
        
        # Add context if available
        if hasattr(record, "context"):
            log_data["context"] = record.context
        
        # Add exception info if present
        if record.exc_info:
            log_data["exception"] = self.formatException(record.exc_info)
        
        # Add extra fields
        for key, value in record.__dict__.items():
            if key not in ["name", "msg", "args", "created", "filename", "funcName",
                          "levelname", "levelno", "lineno", "module", "msecs",
                          "message", "pathname", "process", "processName",
                          "relativeCreated", "thread", "threadName", "exc_info",
                          "exc_text", "stack_info", "context"]:
                log_data[key] = value
        
        return json.dumps(log_data)


class ColoredConsoleFormatter(logging.Formatter):
    """
    Colored console formatter for development.
    Makes logs easier to read in terminal.
    """
    
    # ANSI color codes
    COLORS = {
        "DEBUG": "\033[36m",      # Cyan
        "INFO": "\033[32m",       # Green
        "WARNING": "\033[33m",    # Yellow
        "ERROR": "\033[31m",      # Red
        "CRITICAL": "\033[35m",   # Magenta
    }
    RESET = "\033[0m"
    
    def format(self, record: logging.LogRecord) -> str:
        """Format log record with colors."""
        color = self.COLORS.get(record.levelname, self.RESET)
        
        # Format: [TIMESTAMP] LEVEL - logger - message
        timestamp = datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S")
        formatted = f"{color}[{timestamp}] {record.levelname:<8}{self.RESET} - {record.name} - {record.getMessage()}"
        
        # Add context if available
        if hasattr(record, "context"):
            formatted += f"\n  Context: {json.dumps(record.context, indent=2)}"
        
        # Add exception if present
        if record.exc_info:
            formatted += f"\n{self.formatException(record.exc_info)}"
        
        return formatted


class ContextLogger(logging.LoggerAdapter):
    """
    Logger adapter that adds context to all log messages.
    Useful for adding request_id, session_id, agent_type, etc.
    """
    
    def process(self, msg: str, kwargs: Dict[str, Any]) -> tuple:
        """Add context to log record."""
        # Merge context from adapter and kwargs
        extra = kwargs.get("extra", {})
        if self.extra:
            extra["context"] = {**self.extra, **extra.get("context", {})}
        else:
            if "context" in extra:
                extra["context"] = extra["context"]
        
        kwargs["extra"] = extra
        return msg, kwargs


def setup_logging(
    log_level: str = "INFO",
    log_dir: str = "logs",
    enable_console: bool = True,
    enable_file: bool = True,
    enable_json: bool = False,
    max_bytes: int = 10 * 1024 * 1024,  # 10MB
    backup_count: int = 5,
    use_dated_files: bool = True,
    module_name: str = "backend"
) -> Optional[Path]:
    """
    Setup logging configuration for the application.
    
    Args:
        log_level: Minimum log level (DEBUG, INFO, WARNING, ERROR, CRITICAL)
        log_dir: Directory for log files
        enable_console: Enable console output
        enable_file: Enable file output
        enable_json: Use JSON formatting for file logs
        max_bytes: Maximum size of log file before rotation
        backup_count: Number of backup files to keep
        use_dated_files: Use dated filenames (module_name_YYYYMMDD_HHMMSS.log)
        module_name: Name of the module for log file naming
        
    Returns:
        Path to main log file if file logging is enabled, None otherwise
    """
    # Create logs directory
    log_path = Path(log_dir)
    log_path.mkdir(exist_ok=True)
    
    # Get root logger
    root_logger = logging.getLogger()
    root_logger.setLevel(logging.DEBUG)  # Set to DEBUG to capture everything
    
    # Remove existing handlers
    root_logger.handlers.clear()
    
    # Console handler (colored, human-readable)
    if enable_console:
        console_handler = logging.StreamHandler(sys.stdout)
        console_handler.setLevel(getattr(logging, log_level.upper()))
        console_handler.setFormatter(ColoredConsoleFormatter())
        root_logger.addHandler(console_handler)
    
    # Determine log filenames
    if use_dated_files:
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        main_log_file = log_path / f"{module_name}_{timestamp}.log"
        error_log_file = log_path / f"{module_name}_errors_{timestamp}.log"
    else:
        main_log_file = log_path / f"{module_name}.log"
        error_log_file = log_path / f"{module_name}_errors.log"

    
    # File handler (always DEBUG to capture everything)
    if enable_file:
        if use_dated_files:
            # Use regular FileHandler for dated files (no rotation needed)
            file_handler = logging.FileHandler(
                filename=main_log_file,
                encoding="utf-8"
            )
        else:
            # Use RotatingFileHandler for non-dated files
            file_handler = logging.handlers.RotatingFileHandler(
                filename=main_log_file,
                maxBytes=max_bytes,
                backupCount=backup_count,
                encoding="utf-8"
            )
        
        file_handler.setLevel(logging.DEBUG)  # Always capture DEBUG to file
        
        if enable_json:
            file_handler.setFormatter(StructuredFormatter())
        else:
            file_handler.setFormatter(
                logging.Formatter(
                    "[%(asctime)s] %(levelname)-8s - %(name)s - [%(filename)s:%(lineno)d] - %(message)s",
                    datefmt="%Y-%m-%d %H:%M:%S"
                )
            )
        root_logger.addHandler(file_handler)
    
    # Error file handler (only errors and above)
    if enable_file:
        if use_dated_files:
            error_handler = logging.FileHandler(
                filename=error_log_file,
                encoding="utf-8"
            )
        else:
            error_handler = logging.handlers.RotatingFileHandler(
                filename=error_log_file,
                maxBytes=max_bytes,
                backupCount=backup_count,
                encoding="utf-8"
            )
        
        error_handler.setLevel(logging.ERROR)
        
        if enable_json:
            error_handler.setFormatter(StructuredFormatter())
        else:
            error_handler.setFormatter(
                logging.Formatter(
                    "[%(asctime)s] %(levelname)-8s - %(name)s - [%(filename)s:%(lineno)d] - %(message)s\n%(pathname)s:%(lineno)d",
                    datefmt="%Y-%m-%d %H:%M:%S"
                )
            )
        root_logger.addHandler(error_handler)
    
    # Log initial message
    root_logger.info("Logging system initialized", extra={"context": {"log_level": log_level, "log_file": str(main_log_file) if enable_file else "console only"}})
    
    return main_log_file if enable_file else None


def get_logger(name: str, context: Optional[Dict[str, Any]] = None) -> logging.Logger:
    """
    Get a logger with optional context.
    
    Args:
        name: Logger name (usually __name__)
        context: Optional context dictionary to add to all logs
        
    Returns:
        Logger or ContextLogger instance
    """
    logger = logging.getLogger(name)
    
    if context:
        return ContextLogger(logger, context)
    
    return logger


# Convenience function for adding context to existing logger
def add_context(logger: logging.Logger, **context) -> ContextLogger:
    """
    Add context to an existing logger.
    
    Args:
        logger: Logger instance
        **context: Context key-value pairs
        
    Returns:
        ContextLogger with added context
    """
    return ContextLogger(logger, context)
