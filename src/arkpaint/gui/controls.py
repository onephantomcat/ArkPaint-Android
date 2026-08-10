from __future__ import annotations

from typing import Any

from PySide6.QtCore import QRectF, Qt, Signal
from PySide6.QtGui import QColor, QPainter
from PySide6.QtWidgets import (
    QAbstractButton,
    QHBoxLayout,
    QPushButton,
    QSizePolicy,
    QWidget,
)


class SegmentedControl(QWidget):
    """Compact, mutually exclusive buttons with a combo-box-like data API."""

    currentIndexChanged = Signal(int)

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.setObjectName("segmentedControl")
        self._buttons: list[QPushButton] = []
        self._data: list[Any] = []
        self._current_index = -1
        layout = QHBoxLayout(self)
        layout.setContentsMargins(3, 3, 3, 3)
        layout.setSpacing(2)
        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)

    def addItem(self, text: str, data: Any) -> None:
        index = len(self._buttons)
        button = QPushButton(text)
        button.setProperty("segmentButton", True)
        button.setCheckable(True)
        button.setAutoExclusive(True)
        button.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        button.clicked.connect(lambda checked=False, item=index: self.setCurrentIndex(item))
        self.layout().addWidget(button)  # type: ignore[union-attr]
        self._buttons.append(button)
        self._data.append(data)
        if self._current_index < 0:
            self._current_index = 0
            button.setChecked(True)

    def currentData(self) -> Any:
        if 0 <= self._current_index < len(self._data):
            return self._data[self._current_index]
        return None

    def currentIndex(self) -> int:
        return self._current_index

    def findData(self, value: Any) -> int:
        try:
            return self._data.index(value)
        except ValueError:
            return -1

    def setCurrentIndex(self, index: int) -> None:
        if not 0 <= index < len(self._buttons):
            return
        changed = index != self._current_index
        self._current_index = index
        self._buttons[index].setChecked(True)
        if changed:
            self.currentIndexChanged.emit(index)


class PaletteSelector(QWidget):
    currentIndexChanged = Signal(int)

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self._buttons: list[QPushButton] = []
        self._data: list[Any] = []
        self._current_index = -1
        layout = QHBoxLayout(self)
        layout.setContentsMargins(0, 1, 0, 1)
        layout.setSpacing(2)
        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)

    def addColor(self, hex_value: str, tooltip: str, data: Any) -> None:
        index = len(self._buttons)
        button = QPushButton()
        button.setCheckable(True)
        button.setAutoExclusive(True)
        button.setFixedSize(19, 19)
        button.setToolTip(tooltip)
        button.setAccessibleName(tooltip)
        button.setStyleSheet(
            f"QPushButton {{ background: {hex_value}; border: 1px solid #7b8288; "
            f"border-radius: 2px; padding: 0; min-height: 0; }} "
            "QPushButton:hover { border: 2px solid #1599a7; } "
            "QPushButton:checked { border: 3px solid #1599a7; }"
        )
        button.clicked.connect(lambda checked=False, item=index: self.setCurrentIndex(item))
        self.layout().addWidget(button)  # type: ignore[union-attr]
        self._buttons.append(button)
        self._data.append(data)
        if self._current_index < 0:
            self._current_index = 0
            button.setChecked(True)

    def currentData(self) -> Any:
        if 0 <= self._current_index < len(self._data):
            return self._data[self._current_index]
        return None

    def findData(self, value: Any) -> int:
        try:
            return self._data.index(value)
        except ValueError:
            return -1

    def setCurrentIndex(self, index: int) -> None:
        if not 0 <= index < len(self._buttons):
            return
        changed = index != self._current_index
        self._current_index = index
        self._buttons[index].setChecked(True)
        if changed:
            self.currentIndexChanged.emit(index)


class SwitchButton(QAbstractButton):
    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self._dark_mode = False
        self.setCheckable(True)
        self.setFixedSize(38, 20)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setAccessibleName("夜间模式")
        self.setToolTip("夜间模式")

    def set_dark_mode(self, dark: bool) -> None:
        self._dark_mode = dark
        self.update()

    def paintEvent(self, event: object) -> None:
        del event
        painter = QPainter(self)
        try:
            painter.setRenderHint(QPainter.RenderHint.Antialiasing)
            track = QRectF(0, 1, self.width(), self.height() - 2)
            if self.isChecked():
                track_color = QColor("#1599a7")
            elif self._dark_mode:
                track_color = QColor("#4a5259")
            else:
                track_color = QColor("#b8c0c6")
            painter.setPen(Qt.PenStyle.NoPen)
            painter.setBrush(track_color)
            painter.drawRoundedRect(track, 9, 9)
            diameter = 14.0
            margin = 3.0
            x = self.width() - diameter - margin if self.isChecked() else margin
            painter.setBrush(QColor("#ffffff"))
            painter.drawEllipse(QRectF(x, 3, diameter, diameter))
        finally:
            painter.end()
