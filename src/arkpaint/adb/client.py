from __future__ import annotations

import socket

from arkpaint.adb.protocol import (
    ADB_VERSION,
    AUTH,
    CLSE,
    CNXN,
    DEFAULT_MAX_PAYLOAD,
    MAX_STREAM_SIZE,
    OKAY,
    OPEN,
    WRTE,
    AdbPacket,
    decode_header,
    encode_packet,
)


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


class AdbError(RuntimeError):
    """Raised when the ADB transport or a device command fails."""


class AdbTcpClient:
    """Minimal direct TCP ADB client for emulator automation."""

    def __init__(self, host: str, port: int, timeout: float = 10.0) -> None:
        self.host = host
        self.port = port
        self.timeout = timeout
        self.device_banner = ""
        self._socket: socket.socket | None = None
        self._next_local_id = 1

    def __enter__(self) -> "AdbTcpClient":
        self.connect()
        return self

    def __exit__(self, *args: object) -> None:
        self.close()

    def connect(self) -> None:
        self.close()
        try:
            sock = socket.create_connection(
                (self.host, self.port), timeout=self.timeout
            )
            sock.settimeout(self.timeout)
            self._socket = sock
            banner = b"host::features=shell_v2,cmd,stat_v2\0"
            self._send(CNXN, ADB_VERSION, DEFAULT_MAX_PAYLOAD, banner)
            packet = self._receive()
        except OSError as exc:
            self.close()
            raise AdbError(f"无法连接 {self.host}:{self.port}：{exc}") from exc

        if packet.command == AUTH:
            self.close()
            raise AdbError("设备要求 ADB 密钥认证，当前版本暂不支持")
        if packet.command != CNXN:
            self.close()
            raise AdbError(f"ADB 握手失败：收到 {packet.command_name}")
        self.device_banner = packet.payload.rstrip(b"\0").decode(
            "utf-8", errors="replace"
        )

    def close(self) -> None:
        if self._socket is not None:
            try:
                self._socket.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            self._socket.close()
            self._socket = None

    def capture_png(self) -> bytes:
        image = self.execute("screencap -p")
        if not image.startswith(PNG_SIGNATURE):
            detail = image[:200].decode("utf-8", errors="replace").strip()
            raise AdbError(f"设备未返回有效 PNG 截图{f'：{detail}' if detail else ''}")
        return image

    def execute(self, command: str) -> bytes:
        """Execute one device shell command and return its raw output."""

        if "\0" in command:
            raise ValueError("ADB shell commands cannot contain NUL bytes")
        local_id = self._allocate_local_id()
        remote_id: int | None = None
        output = bytearray()
        service = f"exec:{command}\0".encode("utf-8")
        self._send(OPEN, local_id, 0, service)

        while True:
            packet = self._receive()
            if packet.command in {OKAY, WRTE, CLSE} and packet.arg1 != local_id:
                # Some emulator ADB daemons deliver the final acknowledgement
                # for a closed stream after the next OPEN. Commands are
                # strictly sequential here, so packets for older local IDs
                # are stale and can be discarded safely.
                continue
            if packet.command == OKAY:
                remote_id = packet.arg0
                continue
            if packet.command == WRTE:
                remote_id = packet.arg0
                output.extend(packet.payload)
                if len(output) > MAX_STREAM_SIZE:
                    raise AdbError("ADB 命令输出超过 128 MiB 限制")
                self._send(OKAY, local_id, remote_id)
                continue
            if packet.command == CLSE:
                if remote_id is not None:
                    self._send(CLSE, local_id, remote_id)
                return bytes(output)
            raise AdbError(f"ADB 命令收到意外响应：{packet.command_name}")

    def _allocate_local_id(self) -> int:
        local_id = self._next_local_id
        self._next_local_id += 1
        if self._next_local_id >= 0x7FFFFFFF:
            self._next_local_id = 1
        return local_id

    def _send(
        self, command: int, arg0: int, arg1: int, payload: bytes = b""
    ) -> None:
        try:
            self._require_socket().sendall(encode_packet(command, arg0, arg1, payload))
        except OSError as exc:
            raise AdbError(f"发送 ADB 数据失败：{exc}") from exc

    def _receive(self) -> AdbPacket:
        try:
            header = self._receive_exact(24)
            command, arg0, arg1, length, checksum = decode_header(header)
            payload = self._receive_exact(length)
        except (OSError, ValueError) as exc:
            raise AdbError(f"读取 ADB 数据失败：{exc}") from exc
        if checksum and (sum(payload) & 0xFFFFFFFF) != checksum:
            raise AdbError("ADB 数据包校验失败")
        return AdbPacket(command, arg0, arg1, payload)

    def _receive_exact(self, size: int) -> bytes:
        data = bytearray()
        sock = self._require_socket()
        while len(data) < size:
            chunk = sock.recv(size - len(data))
            if not chunk:
                raise AdbError("ADB 连接被设备提前关闭")
            data.extend(chunk)
        return bytes(data)

    def _require_socket(self) -> socket.socket:
        if self._socket is None:
            raise AdbError("ADB 尚未连接")
        return self._socket
