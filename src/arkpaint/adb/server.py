from __future__ import annotations

import socket
from typing import Self

from arkpaint.adb.client import PNG_SIGNATURE, AdbError
from arkpaint.adb.protocol import MAX_STREAM_SIZE


class AdbServerClient:
    """Select an ADB device by serial through the local ADB Server."""

    def __init__(
        self,
        serial: str,
        timeout: float = 10.0,
        server_host: str = "127.0.0.1",
        server_port: int = 5037,
    ) -> None:
        self.serial = serial.strip()
        self.timeout = timeout
        self.server_host = server_host
        self.server_port = server_port
        self.device_banner = ""

    def __enter__(self) -> Self:
        self.connect()
        return self

    def __exit__(self, *args: object) -> None:
        del args
        self.close()

    def connect(self) -> None:
        with self._open_transport():
            pass
        self.device_banner = f"ADB Server device {self.serial}"

    def close(self) -> None:
        return

    def capture_png(self) -> bytes:
        image = self.execute("screencap -p")
        if not image.startswith(PNG_SIGNATURE):
            detail = image[:200].decode("utf-8", errors="replace").strip()
            raise AdbError(
                f"设备未返回有效 PNG 截图{f'：{detail}' if detail else ''}"
            )
        return image

    def execute(self, command: str) -> bytes:
        if "\0" in command:
            raise ValueError("ADB shell commands cannot contain NUL bytes")
        with self._open_transport() as sock:
            self._send_service(sock, f"exec:{command}")
            return self._receive_stream(sock)

    def _open_transport(self) -> socket.socket:
        if not self.serial or "\0" in self.serial:
            raise AdbError("ADB 设备序列号不能为空")
        try:
            sock = socket.create_connection(
                (self.server_host, self.server_port), timeout=self.timeout
            )
            sock.settimeout(self.timeout)
        except OSError as exc:
            raise AdbError(
                "无法连接本机 ADB Server "
                f"{self.server_host}:{self.server_port}：{exc}。"
                "请确认模拟器已启动并开启 ADB 调试"
            ) from exc
        try:
            self._send_service(sock, f"host:transport:{self.serial}")
        except (AdbError, OSError):
            sock.close()
            raise
        return sock

    def _send_service(self, sock: socket.socket, service: str) -> None:
        payload = service.encode("utf-8")
        if len(payload) > 0xFFFF:
            raise ValueError("ADB Server request is too large")
        try:
            sock.sendall(f"{len(payload):04x}".encode("ascii") + payload)
            status = self._receive_exact(sock, 4)
            if status == b"OKAY":
                return
            if status == b"FAIL":
                length_bytes = self._receive_exact(sock, 4)
                try:
                    length = int(length_bytes, 16)
                except ValueError as exc:
                    raise AdbError("ADB Server 返回了无效的错误长度") from exc
                detail = self._receive_exact(sock, length).decode(
                    "utf-8", errors="replace"
                )
                raise AdbError(f"ADB Server 拒绝设备 {self.serial}：{detail}")
            raise AdbError(
                f"ADB Server 返回了未知状态：{status.decode('ascii', errors='replace')}"
            )
        except OSError as exc:
            raise AdbError(f"ADB Server 通信失败：{exc}") from exc

    def _receive_stream(self, sock: socket.socket) -> bytes:
        output = bytearray()
        try:
            while True:
                chunk = sock.recv(65536)
                if not chunk:
                    return bytes(output)
                output.extend(chunk)
                if len(output) > MAX_STREAM_SIZE:
                    raise AdbError("ADB 命令输出超过 128 MiB 限制")
        except OSError as exc:
            raise AdbError(f"读取 ADB Server 数据失败：{exc}") from exc

    @staticmethod
    def _receive_exact(sock: socket.socket, size: int) -> bytes:
        data = bytearray()
        while len(data) < size:
            chunk = sock.recv(size - len(data))
            if not chunk:
                raise AdbError("ADB Server 提前关闭了连接")
            data.extend(chunk)
        return bytes(data)


def is_adb_serial_target(target: str) -> bool:
    value = target.strip().lower()
    return value.startswith(("emulator-", "serial:"))


def normalize_adb_serial(target: str) -> str:
    value = target.strip()
    if value.lower().startswith("serial:"):
        return value[len("serial:") :]
    return value
