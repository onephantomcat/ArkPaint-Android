from __future__ import annotations

import sys

from PySide6.QtWidgets import QApplication

from arkpaint.assets import application_icon
from arkpaint.config import (
    AppSettings,
    default_config_path,
    load_settings,
    save_settings,
)
from arkpaint.gui.main_window import MainWindow


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName("ArkPaint")
    app.setApplicationVersion("0.1.0")
    app.setWindowIcon(application_icon())
    try:
        settings = load_settings()
    except (OSError, ValueError) as exc:
        settings = AppSettings()
        print(f"读取配置失败，将使用默认设置：{exc}", file=sys.stderr)
    config_path = default_config_path()
    if not config_path.is_file():
        try:
            save_settings(settings, config_path)
        except (OSError, ValueError) as exc:
            print(f"创建配置文件失败：{exc}", file=sys.stderr)
    window = MainWindow(settings)
    window.resize(1520, 860)
    window.show()
    return app.exec()
