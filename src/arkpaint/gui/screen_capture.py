from __future__ import annotations

import io
from dataclasses import dataclass

from PIL import Image
from PySide6.QtCore import QBuffer, QIODevice, QPoint, QRect, Qt, Signal
from PySide6.QtGui import (
    QColor,
    QGuiApplication,
    QImage,
    QKeyEvent,
    QMouseEvent,
    QPainter,
    QPen,
)
from PySide6.QtWidgets import QWidget


@dataclass(frozen=True)
class DesktopCapture:
    image: QImage
    geometry: QRect


def capture_virtual_desktop() -> DesktopCapture:
    """Capture every display into one image using virtual-desktop coordinates."""
    screens = QGuiApplication.screens()
    if not screens:
        raise RuntimeError("未检测到可用显示器")

    geometry = QRect(screens[0].geometry())
    for screen in screens[1:]:
        geometry = geometry.united(screen.geometry())

    image = QImage(geometry.size(), QImage.Format.Format_ARGB32)
    image.fill(QColor("#000000"))
    painter = QPainter(image)
    try:
        for screen in screens:
            pixmap = screen.grabWindow(0)
            if pixmap.isNull():
                raise RuntimeError(f"无法截取显示器：{screen.name()}")
            screen_image = pixmap.toImage()
            screen_image.setDevicePixelRatio(1.0)
            target = QRect(screen.geometry())
            target.translate(-geometry.topLeft())
            painter.drawImage(target, screen_image, screen_image.rect())
    finally:
        painter.end()
    return DesktopCapture(image=image, geometry=geometry)


def qimage_to_pillow(image: QImage) -> Image.Image:
    buffer = QBuffer()
    if not buffer.open(QIODevice.OpenModeFlag.WriteOnly):
        raise RuntimeError("无法创建截图缓冲区")
    try:
        if not image.save(buffer, "PNG"):
            raise RuntimeError("无法编码截图")
        encoded = bytes(buffer.data())
    finally:
        buffer.close()
    with Image.open(io.BytesIO(encoded)) as source:
        return source.convert("RGBA")


class ScreenCaptureOverlay(QWidget):
    imageCaptured = Signal(object)
    cancelled = Signal()

    def __init__(
        self, capture: DesktopCapture, parent: QWidget | None = None
    ) -> None:
        super().__init__(parent)
        self._image = capture.image.copy()
        self._origin: QPoint | None = None
        self._current: QPoint | None = None
        self._finished = False
        self.setWindowFlags(
            Qt.WindowType.FramelessWindowHint
            | Qt.WindowType.WindowStaysOnTopHint
            | Qt.WindowType.Tool
        )
        self.setAttribute(Qt.WidgetAttribute.WA_DeleteOnClose)
        self.setGeometry(capture.geometry)
        self.setCursor(Qt.CursorShape.CrossCursor)
        self.setMouseTracking(True)
        self.setFocusPolicy(Qt.FocusPolicy.StrongFocus)

    def selection_rect(self) -> QRect:
        if self._origin is None or self._current is None:
            return QRect()
        left = min(self._origin.x(), self._current.x())
        top = min(self._origin.y(), self._current.y())
        right = max(self._origin.x(), self._current.x())
        bottom = max(self._origin.y(), self._current.y())
        # Mouse coordinates refer to pixels, so include the release pixel.
        return QRect(left, top, right - left + 1, bottom - top + 1).intersected(
            self.rect()
        )

    def paintEvent(self, event: object) -> None:
        del event
        painter = QPainter(self)
        try:
            painter.drawImage(self.rect(), self._image)
            painter.fillRect(self.rect(), QColor(0, 0, 0, 105))
            selection = self.selection_rect()
            if selection.isEmpty():
                painter.setPen(QColor("#ffffff"))
                painter.drawText(
                    self.rect().adjusted(0, 22, 0, 0),
                    Qt.AlignmentFlag.AlignHCenter | Qt.AlignmentFlag.AlignTop,
                    "拖动选择截图区域，Esc 或右键取消",
                )
                return
            painter.drawImage(selection, self._image, selection)
            painter.setBrush(Qt.BrushStyle.NoBrush)
            painter.setPen(QPen(QColor("#19a9b8"), 2))
            painter.drawRect(selection.adjusted(0, 0, -1, -1))
        finally:
            painter.end()

    def mousePressEvent(self, event: QMouseEvent) -> None:
        if event.button() == Qt.MouseButton.RightButton:
            self._cancel()
            return
        if event.button() != Qt.MouseButton.LeftButton:
            return
        point = self._bounded_point(event.position().toPoint())
        self._origin = point
        self._current = point
        self.update()
        event.accept()

    def mouseMoveEvent(self, event: QMouseEvent) -> None:
        if self._origin is None:
            return
        self._current = self._bounded_point(event.position().toPoint())
        self.update()
        event.accept()

    def mouseReleaseEvent(self, event: QMouseEvent) -> None:
        if event.button() != Qt.MouseButton.LeftButton or self._origin is None:
            return
        self._current = self._bounded_point(event.position().toPoint())
        selection = self.selection_rect()
        if selection.width() >= 2 and selection.height() >= 2:
            self._finished = True
            self.imageCaptured.emit(qimage_to_pillow(self._image.copy(selection)))
            self.close()
        else:
            self._origin = None
            self._current = None
            self.update()
        event.accept()

    def keyPressEvent(self, event: QKeyEvent) -> None:
        if event.key() == Qt.Key.Key_Escape:
            self._cancel()
            event.accept()
            return
        super().keyPressEvent(event)

    def closeEvent(self, event: object) -> None:
        if not self._finished:
            self._finished = True
            self.cancelled.emit()
        super().closeEvent(event)  # type: ignore[arg-type]

    def _bounded_point(self, point: QPoint) -> QPoint:
        return QPoint(
            max(0, min(self.width() - 1, point.x())),
            max(0, min(self.height() - 1, point.y())),
        )

    def _cancel(self) -> None:
        if self._finished:
            return
        self._finished = True
        self.cancelled.emit()
        self.close()
