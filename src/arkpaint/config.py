from __future__ import annotations

import json
import sys
import tomllib
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class AdbSettings:
    host: str = "127.0.0.1"
    port: int = 5555
    server_port: int = 5037
    timeout_seconds: float = 10.0


@dataclass
class DrawingSettings:
    tap_delay_ms: int = 50
    color_select_delay_ms: int = 150
    palette_scroll_duration_ms: int = 700
    palette_settle_delay_ms: int = 900
    tap_batch_size: int = 8


@dataclass
class ImageSettings:
    resize_mode: str = "crop"
    resampling: str = "lanczos"
    mapping_method: str = "lab"
    dither: bool = False
    transparent_palette_index: int = 3
    merge_pixels: int = 1
    brightness: int = 0
    contrast: int = 0
    saturation: int = 0
    color_temperature: int = 0
    hue: int = 0


@dataclass
class WindowSettings:
    width: int = 1520
    height: int = 860
    dark_mode: bool = False
    generate_log: bool = False


@dataclass
class AppSettings:
    adb: AdbSettings = field(default_factory=AdbSettings)
    drawing: DrawingSettings = field(default_factory=DrawingSettings)
    image: ImageSettings = field(default_factory=ImageSettings)
    window: WindowSettings = field(default_factory=WindowSettings)


def default_config_path() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent / "settings.toml"
    return Path(__file__).resolve().parents[2] / "settings.toml"


def load_settings(path: Path | None = None) -> AppSettings:
    config_path = path or default_config_path()
    settings = AppSettings()
    if not config_path.is_file():
        return settings

    with config_path.open("rb") as stream:
        raw = tomllib.load(stream)

    _apply_section(settings.adb, raw.get("adb", {}), ("host", "port", "server_port"))
    _apply_section(settings.drawing, raw.get("drawing", {}), ("tap_delay_ms",))
    _apply_section(settings.window, raw.get("window", {}), ("dark_mode", "generate_log"))
    _validate(settings)
    return settings


def save_settings(settings: AppSettings, path: Path | None = None) -> Path:
    _validate(settings)
    config_path = path or default_config_path()
    config_path.parent.mkdir(parents=True, exist_ok=True)
    sections = (
        (
            "adb",
            {
                "host": settings.adb.host,
                "port": settings.adb.port,
                "server_port": settings.adb.server_port,
            },
        ),
        ("drawing", {"tap_delay_ms": settings.drawing.tap_delay_ms}),
        (
            "window",
            {
                "dark_mode": settings.window.dark_mode,
                "generate_log": settings.window.generate_log,
            },
        ),
    )
    lines: list[str] = []
    for section_name, values in sections:
        lines.append(f"[{section_name}]")
        for key, value in values.items():
            lines.append(f"{key} = {_toml_value(value)}")
        lines.append("")
    config_path.write_text("\n".join(lines), encoding="utf-8")
    return config_path


def _apply_section(
    target: object, values: object, persisted_keys: tuple[str, ...]
) -> None:
    if not isinstance(values, dict):
        return
    for key in persisted_keys:
        if key in values:
            value = values[key]
            setattr(target, key, value)


def _validate(settings: AppSettings) -> None:
    if not settings.adb.host.strip():
        raise ValueError("ADB host cannot be empty")
    if not 1 <= int(settings.adb.port) <= 65535:
        raise ValueError("ADB port must be between 1 and 65535")
    if not 1 <= int(settings.adb.server_port) <= 65535:
        raise ValueError("ADB Server port must be between 1 and 65535")
    if float(settings.adb.timeout_seconds) <= 0:
        raise ValueError("ADB timeout must be positive")
    for value in (
        settings.drawing.tap_delay_ms,
        settings.drawing.color_select_delay_ms,
        settings.drawing.palette_scroll_duration_ms,
        settings.drawing.palette_settle_delay_ms,
    ):
        if int(value) < 0:
            raise ValueError("Drawing delays cannot be negative")
    if not 1 <= int(settings.drawing.tap_batch_size) <= 64:
        raise ValueError("Drawing tap batch size must be between 1 and 64")
    if not 0 <= int(settings.image.transparent_palette_index) < 40:
        raise ValueError("Transparent color index must be between 0 and 39")
    if not 1 <= int(settings.image.merge_pixels) <= 4:
        raise ValueError("Pixel merge must be between 1 and 4")
    for value in (
        settings.image.brightness,
        settings.image.contrast,
        settings.image.saturation,
        settings.image.color_temperature,
        settings.image.hue,
    ):
        if not -100 <= int(value) <= 100:
            raise ValueError("Color adjustments must be between -100 and 100")


def _toml_value(value: object) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, (int, float)):
        return str(value)
    raise TypeError(f"Unsupported TOML value: {type(value).__name__}")
