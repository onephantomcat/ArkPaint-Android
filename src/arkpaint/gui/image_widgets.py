from __future__ import annotations

from PIL import Image
from PySide6.QtCore import QPointF, QRectF, QSizeF, Qt, Signal
from PySide6.QtGui import QColor, QImage, QPainter, QPen
from PySide6.QtWidgets import QWidget


def pil_to_qimage(image: Image.Image) -> QImage:
    rgba = image.convert("RGBA")
    return QImage(
        rgba.tobytes(),
        rgba.width,
        rgba.height,
        rgba.width * 4,
        QImage.Format.Format_RGBA8888,
    ).copy()


class ImagePreview(QWidget):
    def __init__(
        self,
        *,
        pixelated: bool = False,
        show_grid: bool = False,
        background: str = "#20252b",
        parent: QWidget | None = None,
    ) -> None:
        super().__init__(parent)
        self._image: QImage | None = None
        self._pixelated = pixelated
        self._show_grid = show_grid
        self._grid_size = 24
        self._background = QColor(background)
        self.setMinimumSize(180, 180)

    def set_image(self, image: Image.Image | None) -> None:
        self._image = pil_to_qimage(image) if image is not None else None
        self.update()

    def set_background(self, color: str) -> None:
        self._background = QColor(color)
        self.update()

    def set_grid_size(self, size: int) -> None:
        self._grid_size = max(1, min(24, int(size)))
        self.update()

    def _image_destination(self) -> QRectF:
        if self._image is None or self._image.isNull():
            return QRectF()
        target = QRectF(self.rect()).adjusted(10, 10, -10, -10)
        scale = min(
            target.width() / self._image.width(),
            target.height() / self._image.height(),
        )
        size = QSizeF(self._image.size()) * scale
        return QRectF(
            target.center().x() - size.width() / 2,
            target.center().y() - size.height() / 2,
            size.width(),
            size.height(),
        )

    def paintEvent(self, event: object) -> None:
        del event
        painter = QPainter(self)
        try:
            painter.fillRect(self.rect(), self._background)
            if self._image is None or self._image.isNull():
                painter.setPen(QColor("#9aa4ad"))
                painter.drawText(
                    self.rect(), Qt.AlignmentFlag.AlignCenter, "暂无预览"
                )
                return
            destination = self._image_destination()
            mode = (
                Qt.TransformationMode.FastTransformation
                if self._pixelated
                else Qt.TransformationMode.SmoothTransformation
            )
            painter.setRenderHint(
                QPainter.RenderHint.SmoothPixmapTransform, not self._pixelated
            )
            scaled = self._image.scaled(
                destination.size().toSize(),
                Qt.AspectRatioMode.KeepAspectRatio,
                mode,
            )
            painter.drawImage(
                QPointF(
                    destination.center().x() - scaled.width() / 2,
                    destination.center().y() - scaled.height() / 2,
                ),
                scaled,
            )
            if self._show_grid:
                self._draw_grid(painter, destination, self._grid_size)
        finally:
            painter.end()

    @staticmethod
    def _draw_grid(painter: QPainter, rect: QRectF, grid_size: int = 24) -> None:
        """Draw the logical pixel grid over a rendered image."""
        if rect.width() < grid_size or rect.height() < grid_size:
            return
        painter.save()
        painter.setClipRect(rect)
        center = grid_size // 2
        for index in range(1, grid_size):
            is_center = index == center
            painter.setPen(
                QPen(
                    QColor(55, 59, 64, 220) if is_center else QColor(105, 110, 116, 145),
                    2.0 if is_center else 0.75,
                )
            )
            x = rect.left() + rect.width() * index / grid_size
            y = rect.top() + rect.height() * index / grid_size
            painter.drawLine(QPointF(x, rect.top()), QPointF(x, rect.bottom()))
            painter.drawLine(QPointF(rect.left(), y), QPointF(rect.right(), y))
        painter.restore()


class CropPreview(ImagePreview):
    cropChanged = Signal(object)

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(pixelated=True, background="#f7f9fb", parent=parent)
        self._source: Image.Image | None = None
        self._crop_box: tuple[int, int, int, int] | None = None
        self._interactive = True
        self._zoom = 1.0
        self._drag_mode: str | None = None
        self._drag_start = QPointF()
        self._drag_box: tuple[int, int, int, int] | None = None

    def set_source(self, image: Image.Image | None, crop_box: tuple[int, int, int, int] | None) -> None:
        self._source = image
        self._crop_box = crop_box
        super().set_image(image)

    def set_interactive(self, enabled: bool) -> None:
        self._interactive = enabled
        self.update()

    def set_zoom(self, zoom: float) -> None:
        self._zoom = max(0.25, min(4.0, float(zoom)))
        self.update()

    def crop_box(self) -> tuple[int, int, int, int] | None:
        return self._crop_box

    def set_crop_box(self, crop_box: tuple[int, int, int, int]) -> None:
        if self._source is None:
            return
        self._crop_box = _clamp_square(crop_box, self._source.size)
        self.cropChanged.emit(self._crop_box)
        self.update()

    def _view_geometry(self) -> tuple[QRectF, float]:
        if self._source is None:
            return QRectF(), 1.0
        target = QRectF(self.rect()).adjusted(10, 10, -10, -10)
        scale = min(
            target.width() / self._source.width,
            target.height() / self._source.height,
        ) * self._zoom
        size = QSizeF(float(self._source.width), float(self._source.height)) * scale
        rect = QRectF(
            target.center().x() - size.width() / 2,
            target.center().y() - size.height() / 2,
            size.width(),
            size.height(),
        )
        return rect, scale

    def _image_destination(self) -> QRectF:
        return self._view_geometry()[0]

    def _widget_to_image(self, position: QPointF) -> tuple[float, float]:
        rect, scale = self._view_geometry()
        if self._source is None:
            return 0.0, 0.0
        return ((position.x() - rect.left()) / scale, (position.y() - rect.top()) / scale)

    def _crop_geometry(self) -> QRectF:
        if self._source is None or self._crop_box is None:
            return QRectF()
        view, scale = self._view_geometry()
        left, top, right, bottom = self._crop_box
        return QRectF(
            view.left() + left * scale,
            view.top() + top * scale,
            (right - left) * scale,
            (bottom - top) * scale,
        )

    def _resize_corner_at(self, position: QPointF) -> str | None:
        crop = self._crop_geometry()
        if crop.isEmpty():
            return None
        radius = max(10.0, min(18.0, crop.width() * 0.04))
        corners = (
            ("top_left", crop.topLeft()),
            ("top_right", crop.topRight()),
            ("bottom_left", crop.bottomLeft()),
            ("bottom_right", crop.bottomRight()),
        )
        for name, point in corners:
            if abs(position.x() - point.x()) <= radius and abs(position.y() - point.y()) <= radius:
                return name
        return None

    def paintEvent(self, event: object) -> None:
        # Reuse the tested base preview renderer so the source image remains
        # visible; this widget only adds the crop overlay and handles.
        super().paintEvent(event)
        if self._source is None:
            return

        view, scale = self._view_geometry()
        crop = view
        if self._interactive and self._crop_box is not None:
            left, top, right, bottom = self._crop_box
            crop = QRectF(
                view.left() + left * scale,
                view.top() + top * scale,
                (right - left) * scale,
                (bottom - top) * scale,
            )
        painter = QPainter(self)
        try:
            if self._interactive and self._crop_box is not None:
                overlay = QColor(0, 0, 0, 105)
                painter.setBrush(overlay)
                painter.setPen(Qt.PenStyle.NoPen)
                visible_crop = crop.intersected(view)
                if visible_crop.isEmpty():
                    painter.drawRect(view)
                else:
                    painter.drawRect(
                        QRectF(
                            view.left(),
                            view.top(),
                            view.width(),
                            visible_crop.top() - view.top(),
                        )
                    )
                    painter.drawRect(
                        QRectF(
                            view.left(),
                            visible_crop.bottom(),
                            view.width(),
                            view.bottom() - visible_crop.bottom(),
                        )
                    )
                    painter.drawRect(
                        QRectF(
                            view.left(),
                            visible_crop.top(),
                            visible_crop.left() - view.left(),
                            visible_crop.height(),
                        )
                    )
                    painter.drawRect(
                        QRectF(
                            visible_crop.right(),
                            visible_crop.top(),
                            view.right() - visible_crop.right(),
                            visible_crop.height(),
                        )
                    )
            self._draw_grid(painter, crop, self._grid_size)
            painter.setBrush(Qt.BrushStyle.NoBrush)
            painter.setPen(QPen(QColor(112, 117, 123), 2, Qt.PenStyle.SolidLine))
            painter.drawRect(crop)
            if self._interactive and self._crop_box is not None:
                painter.setBrush(QColor(112, 117, 123))
                painter.setPen(Qt.PenStyle.NoPen)
                handle = max(8.0, min(16.0, crop.width() * 0.03))
                for point in (
                    crop.topLeft(),
                    crop.topRight(),
                    crop.bottomLeft(),
                    crop.bottomRight(),
                ):
                    painter.drawEllipse(point, handle / 2, handle / 2)
        finally:
            painter.end()

    def mousePressEvent(self, event: object) -> None:
        if self._source is None or not self._interactive or self._crop_box is None:
            return
        mouse_event = event
        position = mouse_event.position()  # type: ignore[attr-defined]
        image_x, image_y = self._widget_to_image(position)
        left, top, right, bottom = self._crop_box
        corner = self._resize_corner_at(position)
        if corner is not None:
            self._drag_mode = f"resize_{corner}"
        elif left <= image_x <= right and top <= image_y <= bottom:
            self._drag_mode = "move"
        else:
            self._drag_mode = None
        if self._drag_mode:
            self._drag_start = position
            self._drag_box = self._crop_box
            mouse_event.accept()  # type: ignore[attr-defined]

    def mouseMoveEvent(self, event: object) -> None:
        position = event.position()  # type: ignore[attr-defined]
        if not self._drag_mode:
            corner = self._resize_corner_at(position)
            if corner in {"top_left", "bottom_right"}:
                self.setCursor(Qt.CursorShape.SizeFDiagCursor)
            elif corner in {"top_right", "bottom_left"}:
                self.setCursor(Qt.CursorShape.SizeBDiagCursor)
            elif self._crop_geometry().contains(position):
                self.setCursor(Qt.CursorShape.SizeAllCursor)
            else:
                self.unsetCursor()
            return
        if self._source is None or self._drag_box is None:
            return
        _, scale = self._view_geometry()
        delta_x = (position.x() - self._drag_start.x()) / scale
        delta_y = (position.y() - self._drag_start.y()) / scale
        left, top, right, bottom = self._drag_box
        if self._drag_mode == "move":
            proposed = (round(left + delta_x), round(top + delta_y), round(right + delta_x), round(bottom + delta_y))
        else:
            proposed = _resize_square(
                self._drag_box,
                self._drag_mode.removeprefix("resize_"),
                delta_x,
                delta_y,
            )
        self.set_crop_box(proposed)

    def mouseReleaseEvent(self, event: object) -> None:
        self._drag_mode = None
        self._drag_box = None
        self.unsetCursor()
        event.accept()  # type: ignore[attr-defined]

    def leaveEvent(self, event: object) -> None:
        if self._drag_mode is None:
            self.unsetCursor()
        super().leaveEvent(event)  # type: ignore[arg-type]


def _resize_square(
    box: tuple[int, int, int, int],
    corner: str,
    delta_x: float,
    delta_y: float,
) -> tuple[int, int, int, int]:
    left, top, right, bottom = box
    side = right - left
    if corner == "top_left":
        change = _dominant_delta(-delta_x, -delta_y)
        new_side = max(8, round(side + change))
        return right - new_side, bottom - new_side, right, bottom
    if corner == "top_right":
        change = _dominant_delta(delta_x, -delta_y)
        new_side = max(8, round(side + change))
        return left, bottom - new_side, left + new_side, bottom
    if corner == "bottom_left":
        change = _dominant_delta(-delta_x, delta_y)
        new_side = max(8, round(side + change))
        return right - new_side, top, right, top + new_side
    if corner == "bottom_right":
        change = _dominant_delta(delta_x, delta_y)
        new_side = max(8, round(side + change))
        return left, top, left + new_side, top + new_side
    return box


def _dominant_delta(first: float, second: float) -> float:
    return first if abs(first) >= abs(second) else second


def _clamp_square(
    box: tuple[int, int, int, int], image_size: tuple[int, int]
) -> tuple[int, int, int, int]:
    del image_size
    left, top, right, bottom = box
    side = max(8, min(right - left, bottom - top))
    return left, top, left + side, top + side
