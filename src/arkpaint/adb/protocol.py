from __future__ import annotations

import struct
from dataclasses import dataclass


ADB_VERSION = 0x01000001
DEFAULT_MAX_PAYLOAD = 1024 * 1024
MAX_PACKET_SIZE = 16 * 1024 * 1024
MAX_STREAM_SIZE = 128 * 1024 * 1024


def command_id(name: bytes) -> int:
    if len(name) != 4:
        raise ValueError("ADB command names must be four bytes")
    return int.from_bytes(name, "little")


CNXN = command_id(b"CNXN")
AUTH = command_id(b"AUTH")
OPEN = command_id(b"OPEN")
OKAY = command_id(b"OKAY")
WRTE = command_id(b"WRTE")
CLSE = command_id(b"CLSE")


@dataclass(frozen=True)
class AdbPacket:
    command: int
    arg0: int
    arg1: int
    payload: bytes

    @property
    def command_name(self) -> str:
        return self.command.to_bytes(4, "little").decode("ascii", errors="replace")


def encode_packet(
    command: int, arg0: int, arg1: int, payload: bytes = b""
) -> bytes:
    checksum = sum(payload) & 0xFFFFFFFF
    header = struct.pack(
        "<6I",
        command,
        arg0,
        arg1,
        len(payload),
        checksum,
        command ^ 0xFFFFFFFF,
    )
    return header + payload


def decode_header(header: bytes) -> tuple[int, int, int, int, int]:
    if len(header) != 24:
        raise ValueError("ADB packet header must contain 24 bytes")
    command, arg0, arg1, length, checksum, magic = struct.unpack("<6I", header)
    if magic != command ^ 0xFFFFFFFF:
        raise ValueError("Corrupt ADB packet header")
    if length > MAX_PACKET_SIZE:
        raise ValueError(f"ADB packet is too large: {length} bytes")
    return command, arg0, arg1, length, checksum
