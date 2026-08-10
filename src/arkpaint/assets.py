from __future__ import annotations

import sys
from pathlib import Path

from PySide6.QtGui import QIcon


def runtime_root() -> Path:
    if getattr(sys, "frozen", False):
        return Path(getattr(sys, "_MEIPASS"))
    return Path(__file__).resolve().parents[2]


def assets_root() -> Path:
    return runtime_root() / "assets"


def application_icon() -> QIcon:
    path = runtime_root() / "ico.png"
    return QIcon(str(path)) if path.is_file() else QIcon()


def icon(name: str) -> QIcon:
    path = assets_root() / "icons" / f"{name}.svg"
    return QIcon(str(path)) if path.is_file() else QIcon()
