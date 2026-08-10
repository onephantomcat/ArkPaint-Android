"""Build a single-file ArkPaint executable."""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parents[1]
SOURCE_DIR = PROJECT_DIR / "src"
APP_NAME = "ArkPaint"
ENTRY_SCRIPT = PROJECT_DIR / "main.py"
ARTIFACT_DIR = PROJECT_DIR / "artifacts" / "pyinstaller"
RELEASE_ROOT = PROJECT_DIR / "dist"
RELEASE_DIR = RELEASE_ROOT / APP_NAME
ICON_PATH = PROJECT_DIR / "ico.png"
MAX_RELEASE_BYTES = 200 * 1024 * 1024


def _remove_path(path: Path) -> None:
    """Remove only a build path that this script owns."""
    if path.is_dir():
        shutil.rmtree(path)
    elif path.exists():
        path.unlink()


def build() -> Path:
    if not ENTRY_SCRIPT.is_file():
        raise SystemExit(f"缺少入口文件：{ENTRY_SCRIPT}")
    if not (SOURCE_DIR / "arkpaint").is_dir():
        raise SystemExit(f"缺少源码包：{SOURCE_DIR / 'arkpaint'}")
    if not ICON_PATH.is_file():
        raise SystemExit(f"缺少应用图标：{ICON_PATH}")
    try:
        import PyInstaller  # noqa: F401
        import PySide6  # noqa: F401
        import cv2  # noqa: F401
        import PIL  # noqa: F401
    except ImportError as exc:
        raise SystemExit(
            "缺少打包依赖，请先运行：python -m pip install -r requirements-dev.txt"
        ) from exc

    work_dir = ARTIFACT_DIR / "work"
    dist_dir = ARTIFACT_DIR / "dist"
    spec_dir = ARTIFACT_DIR / "spec"
    executable_name = f"{APP_NAME}.exe" if sys.platform.startswith("win") else APP_NAME
    source_executable = dist_dir / executable_name
    target_executable = RELEASE_ROOT / executable_name

    _remove_path(ARTIFACT_DIR)
    work_dir.mkdir(parents=True)
    dist_dir.mkdir(parents=True)
    spec_dir.mkdir(parents=True)

    command = [
        sys.executable,
        "-m",
        "PyInstaller",
        "--onefile",
        "--windowed",
        "--clean",
        "--noconfirm",
        f"--name={APP_NAME}",
        f"--paths={SOURCE_DIR}",
        f"--workpath={work_dir}",
        f"--distpath={dist_dir}",
        f"--specpath={spec_dir}",
        f"--add-data={PROJECT_DIR / 'assets'}{os.pathsep}assets",
        f"--add-data={ICON_PATH}{os.pathsep}.",
        f"--icon={ICON_PATH}",
        "--exclude-module=PySide6.QtWebEngineCore",
        "--exclude-module=PySide6.QtWebEngineWidgets",
        "--exclude-module=PySide6.QtMultimedia",
        "--exclude-module=PySide6.QtQuick",
        "--exclude-module=PySide6.QtQml",
        str(ENTRY_SCRIPT),
    ]

    print(f"====== 开始打包：{APP_NAME} ======")
    try:
        subprocess.run(command, cwd=PROJECT_DIR, check=True)
        if not source_executable.is_file():
            raise RuntimeError(f"PyInstaller 未生成：{source_executable}")

        RELEASE_ROOT.mkdir(parents=True, exist_ok=True)
        _remove_path(target_executable)
        shutil.copy2(source_executable, target_executable)
        try:
            _remove_path(RELEASE_DIR)
        except PermissionError:
            print(
                f"警告：旧发布目录正在使用，暂时无法删除：{RELEASE_DIR}"
            )

        release_size = target_executable.stat().st_size
        if release_size > MAX_RELEASE_BYTES:
            raise RuntimeError(
                f"发布包超过 200 MB：{release_size / 1024 / 1024:.1f} MB"
            )
    except subprocess.CalledProcessError as exc:
        raise SystemExit(f"打包失败，退出码：{exc.returncode}") from exc
    finally:
        _remove_path(ARTIFACT_DIR)
        artifacts_root = ARTIFACT_DIR.parent
        if artifacts_root.is_dir() and not any(artifacts_root.iterdir()):
            artifacts_root.rmdir()

    print("====== 打包完成 ======")
    print(f"发布目录：{RELEASE_ROOT}")
    print(f"可执行文件：{target_executable}")
    print(f"发布包大小：{target_executable.stat().st_size / 1024 / 1024:.1f} MB")
    return target_executable


if __name__ == "__main__":
    build()
