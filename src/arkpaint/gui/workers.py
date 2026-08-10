from __future__ import annotations

from threading import Event

import numpy as np
from PySide6.QtCore import QThread, Signal

from arkpaint.adb import (
    AdbServerClient,
    AdbTcpClient,
    is_adb_serial_target,
    normalize_adb_serial,
)
from arkpaint.drawing import DrawingCancelled, Painter
from arkpaint.imaging.screen_detector import ScreenDetector


class ConnectThread(QThread):
    succeeded = Signal(object)
    failed = Signal(str)

    def __init__(
        self,
        host: str,
        port: int,
        timeout: float,
        parent: object = None,
        *,
        adb_server_port: int = 5037,
    ) -> None:
        super().__init__(parent)
        self.host = host
        self.port = port
        self.timeout = timeout
        self.adb_server_port = adb_server_port

    def run(self) -> None:
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
                layout = ScreenDetector().detect_png(screenshot)
                self.succeeded.emit(
                    {"screenshot": screenshot, "layout": layout, "banner": client.device_banner}
                )
        except Exception as exc:  # QThread must convert all errors to a GUI signal.
            self.failed.emit(str(exc))


class DrawThread(QThread):
    progress = Signal(int, int, int, int, int)
    succeeded = Signal()
    cancelled = Signal()
    failed = Signal(str)

    def __init__(self, painter: Painter, indices: np.ndarray, parent: object = None) -> None:
        super().__init__(parent)
        self.painter = painter
        self.indices = indices.copy()
        self.cancel_event = Event()

    def cancel(self) -> None:
        self.cancel_event.set()

    def run(self) -> None:
        try:
            self.painter.paint(
                self.indices,
                cancel_event=self.cancel_event,
                progress=lambda item: self.progress.emit(
                    item.completed,
                    item.total,
                    item.palette_index,
                    item.color_completed,
                    item.color_total,
                ),
            )
        except DrawingCancelled:
            self.cancelled.emit()
        except Exception as exc:
            self.failed.emit(str(exc))
        else:
            self.succeeded.emit()
