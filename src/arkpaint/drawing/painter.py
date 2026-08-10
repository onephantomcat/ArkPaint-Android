from __future__ import annotations

import io
from collections.abc import Callable
from dataclasses import dataclass
from threading import Event

import numpy as np
from PIL import Image

from arkpaint.adb import (
    AdbError,
    AdbServerClient,
    AdbTcpClient,
    is_adb_serial_target,
    normalize_adb_serial,
)
from arkpaint.config import DrawingSettings
from arkpaint.imaging.palette import PALETTE
from arkpaint.imaging.screen_detector import ScreenDetectionError, ScreenDetector


class DrawingError(RuntimeError):
    """Raised when the canvas cannot be safely painted."""


class DrawingCancelled(RuntimeError):
    """Raised when the user cancels a drawing task."""


@dataclass(frozen=True)
class DrawingProgress:
    completed: int
    total: int
    palette_index: int
    color_completed: int = 0
    color_total: int = 0


ProgressCallback = Callable[[DrawingProgress], None]
WHITE_PALETTE_INDEX = next(color.index for color in PALETTE if color.hex_value == "#FFFFFF")


class Painter:
    def __init__(
        self,
        host: str,
        port: int,
        timeout: float,
        settings: DrawingSettings,
        detector: ScreenDetector | None = None,
        *,
        adb_server_port: int = 5037,
    ) -> None:
        self.host = host
        self.port = port
        self.timeout = timeout
        self.settings = settings
        self.detector = detector or ScreenDetector()
        self.adb_server_port = adb_server_port

    def paint(
        self,
        palette_indices: np.ndarray,
        *,
        cancel_event: Event,
        progress: ProgressCallback | None = None,
    ) -> None:
        if palette_indices.shape != (24, 24):
            raise DrawingError("待绘制图像必须是 24×24 色板索引")
        try:
            if is_adb_serial_target(self.host):
                client = AdbServerClient(
                    normalize_adb_serial(self.host),
                    self.timeout,
                    server_port=self.adb_server_port,
                )
            else:
                client = AdbTcpClient(self.host, self.port, self.timeout)
            with client:
                screenshot = client.capture_png()
                layout = self._detect_screenshot(screenshot)
                existing_white = _white_canvas_mask(screenshot, layout)
                paint_mask = ~(
                    (palette_indices == WHITE_PALETTE_INDEX) & existing_white
                )
                total = int(np.count_nonzero(paint_mask))
                completed = 0
                if progress is not None:
                    progress(DrawingProgress(0, total, -1))
                if total == 0:
                    return
                used_colors = {
                    int(value) for value in np.unique(palette_indices[paint_mask])
                }
                if any(index < 16 for index in used_colors):
                    layout = self._scroll_to(
                        client, layout, target_start=0, cancel_event=cancel_event
                    )
                    for palette_index in range(16):
                        completed = self._paint_color(
                            client,
                            layout,
                            palette_indices,
                            paint_mask,
                            palette_index,
                            completed,
                            total,
                            cancel_event,
                            progress,
                        )

                if any(index >= 16 for index in used_colors):
                    layout = self._scroll_to(
                        client, layout, target_start=4, cancel_event=cancel_event
                    )
                    for palette_index in range(16, 40):
                        completed = self._paint_color(
                            client,
                            layout,
                            palette_indices,
                            paint_mask,
                            palette_index,
                            completed,
                            total,
                            cancel_event,
                            progress,
                        )
        except DrawingCancelled:
            raise
        except (AdbError, ScreenDetectionError, OSError, ValueError) as exc:
            raise DrawingError(str(exc)) from exc

    def _detect_connected_layout(self, client):
        return self._detect_screenshot(client.capture_png())

    def _detect_screenshot(self, screenshot: bytes):
        try:
            return self.detector.detect_png(screenshot)
        except ScreenDetectionError as exc:
            raise DrawingError(f"当前不是 ArkPaint 画布界面：{exc}") from exc

    def _scroll_to(self, client, layout, *, target_start: int, cancel_event: Event):
        for _ in range(3):
            self._check_cancel(cancel_event)
            if layout.palette_start_row == target_start:
                return layout
            if target_start == 0:
                start_y, end_y = layout.palette_rows[0], layout.palette_rows[-1]
            else:
                start_y, end_y = layout.palette_rows[-1], layout.palette_rows[0]
            x = layout.palette_columns[-1]
            client.execute(
                f"input swipe {x} {start_y} {x} {end_y} "
                f"{int(self.settings.palette_scroll_duration_ms)}"
            )
            if cancel_event.wait(self.settings.palette_settle_delay_ms / 1000.0):
                raise DrawingCancelled
            layout = self._detect_connected_layout(client)
        raise DrawingError(f"无法将调色板滚动到第 {target_start + 1} 行")

    def _paint_color(
        self,
        client,
        layout,
        palette_indices: np.ndarray,
        paint_mask: np.ndarray,
        palette_index: int,
        completed: int,
        total: int,
        cancel_event: Event,
        progress: ProgressCallback | None,
    ) -> int:
        coordinates = np.argwhere(
            (palette_indices == palette_index) & paint_mask
        )
        if len(coordinates) == 0:
            return completed
        try:
            palette_x, palette_y = layout.palette_center(palette_index)
        except ValueError as exc:
            raise DrawingError(f"色板颜色 {palette_index + 1} 当前不可见") from exc

        self._check_cancel(cancel_event)
        # Keep the previous color at 100% while the next palette color is
        # selected. The next callback arrives after its first batch is drawn.
        if progress is not None and completed == 0:
            progress(
                DrawingProgress(
                    completed,
                    total,
                    palette_index,
                    0,
                    len(coordinates),
                )
            )
        client.execute(f"input tap {palette_x} {palette_y}")
        if cancel_event.wait(self.settings.color_select_delay_ms / 1000.0):
            raise DrawingCancelled

        batch_size = max(1, self.settings.tap_batch_size)
        for start in range(0, len(coordinates), batch_size):
            self._check_cancel(cancel_event)
            batch = coordinates[start:start + batch_size]
            commands: list[str] = []
            for row, column in batch:
                x, y = layout.canvas_cell_center(int(column), int(row))
                commands.append(f"input tap {x} {y}")
                if self.settings.tap_delay_ms:
                    commands.append(f"sleep {self.settings.tap_delay_ms / 1000:g}")
            client.execute("; ".join(commands))
            completed += len(batch)
            if progress is not None:
                progress(
                    DrawingProgress(
                        completed,
                        total,
                        palette_index,
                        start + len(batch),
                        len(coordinates),
                    )
                )
        return completed

    @staticmethod
    def _check_cancel(cancel_event: Event) -> None:
        if cancel_event.is_set():
            raise DrawingCancelled


def _white_canvas_mask(screenshot: bytes, layout) -> np.ndarray:
    with Image.open(io.BytesIO(screenshot)) as image:
        rgb = np.asarray(image.convert("RGB"), dtype=np.uint8)
    mask = np.zeros((24, 24), dtype=bool)
    cell_width = layout.canvas.width / 24
    cell_height = layout.canvas.height / 24
    sample_radius = max(1, round(min(cell_width, cell_height) * 0.12))
    for row in range(24):
        for column in range(24):
            center_x, center_y = layout.canvas_cell_center(column, row)
            left = max(0, center_x - sample_radius)
            right = min(rgb.shape[1], center_x + sample_radius + 1)
            top = max(0, center_y - sample_radius)
            bottom = min(rgb.shape[0], center_y + sample_radius + 1)
            sample = rgb[top:bottom, left:right]
            if sample.size:
                median = np.median(sample.reshape(-1, 3), axis=0)
                mask[row, column] = bool(np.all(median >= 245))
    return mask
