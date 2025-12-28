# START OF FILE shared/logging_utils.py
"""
Centralized logging configuration for all NoSlop modules.

Provides consistent logging setup across backend, seed, and all other modules.
All logs are saved to dated files in the main logs/ folder with the pattern:
{module_name}_{YYYYMMDD_HHMMSS}.log

Usage:
    from shared.logging_utils import setup_module_logging
    
    # Setup logging for a module
    log_file = setup_module_logging("backend", log_level="INFO")
    
    # Get logger in your module
    import logging
    logger = logging.getLogger(__name__)
    logger.info("Module started")
"""

import logging
import logging.handlers
import sys
from datetime import datetime
from pathlib import Path
from typing import Optional


class StructuredFormatter(logging.Formatter):
    """
    Custom formatter that outputs structured JSON logs.
    Includes timestamp, level, message, and optional context.
    """
    
    def format(self, record: logging.LogRecord) -> str:
        """Format log record as JSON."""
        # Base log data
        log_data = {
            "timestamp": datetime.utcnow().isoformat() + "Z",
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
            "module": record.module,
            "line": record.lineno
        }
        
        # Add context if available
        if hasattr(record, "context"):
            log_data["context"] = record.context
        
        # Add exception info if present
        if record.exc_info:
            log_data["exception"] = self.formatException(record.exc_info)
        
        # Add extra fields (excluding standard attributes)
        for key, value in record.__dict__.items():
            if key not in ["name", "msg", "args", "created", "filename", "funcName",
                          "levelname", "levelno", "lineno", "module", "msecs",
                          "message", "pathname", "process", "processName",
                          "relativeCreated", "thread", "threadName", "exc_info",
                          "exc_text", "stack_info", "context"]:
                try:
                    # Only add if serializable
                    import json
                    json.dumps(value)
                    log_data[key] = value
                except (TypeError, OverflowError):
                    pass
        
        import json
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

        # Format: [TIMESTAMP] LEVEL - logger [filename:lineno] - message
        timestamp = datetime.fromtimestamp(record.created).strftime("%H:%M:%S")
        log_message = f"[{timestamp}] {record.levelname:<8} - {record.name} - {record.getMessage()}"
        formatted = f"{color}{log_message}{self.RESET}"

        # Add exception if present
        if record.exc_info:
            # Color exception as well
            formatted += f"\n{color}{self.formatException(record.exc_info)}{self.RESET}"

        return formatted


def setup_module_logging(
    module_name: str,
    log_level: str = "INFO",
    log_dir: str = "logs",
    enable_console: bool = True,
    enable_file: bool = True,
    max_bytes: int = 10 * 1024 * 1024,  # 10MB
    backup_count: int = 5,
) -> Optional[Path]:
    """
    Setup logging configuration for a NoSlop module.
    
    Args:
        module_name: Name of the module (e.g., "backend", "seed_installer")
        log_level: Minimum log level for console output
        log_dir: Directory for log files
        enable_console: Enable console output
        enable_file: Enable file output
        max_bytes: Max size for log files (unused for dated files)
        backup_count: Backup count (unused for dated files)
        
    Returns:
        Path to main log file if file logging is enabled, None otherwise
    """
    # Create logs directory
    log_path = Path(log_dir)
    log_path.mkdir(exist_ok=True)
    
    # Generate dated log filename
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    main_log_file = log_path / f"{module_name}_{timestamp}.log"
    error_log_file = log_path / f"{module_name}_errors_{timestamp}.log"
    
    # Get root logger
    root_logger = logging.getLogger()
    root_logger.setLevel(logging.DEBUG)  # Set to DEBUG to capture everything
    
    # Remove existing handlers to avoid duplicates
    root_logger.handlers.clear()
    
    # Convert log level string to logging constant
    console_level = getattr(logging, log_level.upper(), logging.INFO)
    
    # Create formatters
    file_formatter = logging.Formatter(
        '[%(asctime)s] %(levelname)-8s - %(name)s - [%(filename)s:%(lineno)d] - %(message)s',
        datefmt='%Y-%m-%d %H:%M:%S'
    )
    
    # Console handler (respects user's log level)
    if enable_console:
        console_handler = logging.StreamHandler(sys.stdout)
        console_handler.setLevel(console_level)
        console_handler.setFormatter(ColoredConsoleFormatter())
        root_logger.addHandler(console_handler)
    
    # File handler (always DEBUG to capture everything)
    if enable_file:
        # Use regular FileHandler for dated files (no rotation needed)
        file_handler = logging.FileHandler(
            filename=main_log_file,
            encoding='utf-8'
        )
        file_handler.setLevel(logging.DEBUG)  # Always capture DEBUG to file
        file_handler.setFormatter(file_formatter)
        root_logger.addHandler(file_handler)
        
        # Error file handler (only errors and above)
        error_handler = logging.FileHandler(
            filename=error_log_file,
            encoding='utf-8'
        )
        error_handler.setLevel(logging.ERROR)
        error_handler.setFormatter(file_formatter)
        root_logger.addHandler(error_handler)
    
    # Log initial message
    logger = logging.getLogger(__name__)
    logger.info(f"Logging initialized for module: {module_name}")
    
    if enable_file:
        logger.info(f"Log file: {main_log_file}")
    
    return main_log_file if enable_file else None


def get_module_logger(name: str) -> logging.Logger:
    """
    Get a logger for a specific module or class.
    
    This is a convenience function that wraps logging.getLogger().
    Use this after calling setup_module_logging() to get loggers in your modules.
    
    Args:
        name: Logger name (usually __name__)
        
    Returns:
        Logger instance
        
    Example:
        >>> logger = get_module_logger(__name__)
        >>> logger.debug("Debug message")
        >>> logger.info("Info message")
    """
    return logging.getLogger(name)


def add_file_handler(
    module_name: str,
    log_dir: str = "logs",
    log_level: str = "DEBUG"
) -> Path:
    """
    Add a file handler to a module-specific logger.
    
    Creates a dedicated logger for the module to prevent log contamination.
    This is useful for installer components that need their own log files.
    
    Args:
        module_name: Name for the log file and logger
        log_dir: Directory for log files
        log_level: Minimum log level for this handler
        
    Returns:
        Path to the created log file
        
    Example:
        >>> log_file = add_file_handler("comfyui_installer")
        >>> logger = logging.getLogger("installer.comfyui")
        >>> logger.info("ComfyUI installation started")
    """
    # Create logs directory
    log_path = Path(log_dir)
    log_path.mkdir(exist_ok=True)
    
    # Generate dated log filename
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    log_file = log_path / f"{module_name}_{timestamp}.log"
    
    # Create formatter
    formatter = logging.Formatter(
        '[%(asctime)s] %(levelname)-8s - %(name)s - [%(filename)s:%(lineno)d] - %(message)s',
        datefmt='%Y-%m-%d %H:%M:%S'
    )
    
    # Create and configure handler
    handler = logging.FileHandler(filename=log_file, encoding='utf-8')
    handler.setLevel(getattr(logging, log_level.upper(), logging.DEBUG))
    handler.setFormatter(formatter)
    
    # Create a module-specific logger (not root logger!)
    # This prevents contamination across different modules
    module_logger_name = f"installer.{module_name}"
    module_logger = logging.getLogger(module_logger_name)
    module_logger.setLevel(logging.DEBUG)
    module_logger.addHandler(handler)
    
    # Also add to root logger so console output still works
    # But this way each module has its own file handler
    logging.getLogger().addHandler(handler)
    
    logger = logging.getLogger(__name__)
    logger.info(f"Added file handler: {log_file}")
    
    return log_file
