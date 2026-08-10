# ArkPaint

PySide6 desktop tool for importing an image, mapping it to ArkPaint's 40-color palette, and painting the result on a 24×24 Android canvas through TCP ADB.

## Run From Source

Python 3.11 or newer is required.

```powershell
python -m pip install -r requirements.txt
python .\main.py
```

The default connection is `127.0.0.1:5555`. The address field also accepts an ADB serial such as `emulator-5554`; serial targets are selected through the local ADB Server using the built-in Python transport, so no external `adb.exe` is required. Its port defaults to `5037` and can be changed in `settings.toml` when needed. Connection settings are saved beside the application.

## Workflow

1. Click `连接并验证`. The application captures the Android screen and checks for a landscape ArkPaint layout with a square canvas, a 24×24 grid, and the four-column palette.
2. Click `导入图片`, or click `截图` and drag over any screen region to import it directly. For non-square images, use the embedded 1:1 crop box or select full-image stretch.
3. Choose one of the six Pillow resampling methods, one of the RGB/weighted-RGB/Lab mapping methods, the transparent-pixel palette color, and optional Floyd-Steinberg dithering. The mapped 24×24 result updates as settings change.
4. Click `开始绘制`. After confirmation, all 576 cells are painted, including white cells. `取消` leaves the partially painted canvas in place. `重新绘制` starts the current image from the beginning.

The application never clicks ArkPaint's `保存` or `完成并发布` controls. Use `导出映射图` to save the mapped PNG locally.

## Configuration

`settings.toml` stores the ADB host and port, night mode, the operation-log switch, and the drawing delay shown in the GUI. Image-processing controls reset to their built-in defaults whenever an image is imported. When operation logging is enabled, ArkPaint writes a date-stamped `ArkPaintYYYY-MM-DD.log` file beside the executable. The settings file is created beside the executable on first launch.

## Build A Portable EXE

Install the development dependencies and run the independent build script:

```powershell
python -m pip install -r requirements-dev.txt
python .\scripts\build_exe.py
```

The script creates the single-file `dist/ArkPaint.exe` with `ico.png` as its application icon. On first launch it creates `settings.toml` beside the executable. Temporary PyInstaller directories are removed, and the build fails if the executable exceeds 200 MB. No system `adb.exe` is required.

## Project Layout

```text
src/arkpaint/adb/         Direct TCP ADB transport
src/arkpaint/imaging/     Palette mapping and screen detection
src/arkpaint/drawing/     Cancellable canvas painter
src/arkpaint/gui/         PySide6 widgets, workers, and main window
scripts/build_exe.py      Reproducible portable build
tests/                    Processing, protocol, and detector tests
```
