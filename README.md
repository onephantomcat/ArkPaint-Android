# ArkPaint

视频链接：https://www.bilibili.com/video/BV17Eu56fEgq
介绍就看视频吧，整个项目包括下面的readme都是GPT5.6-Sol极高 写的，视频是人类做的。

ArkPaint 是一款基于 PySide6 的桌面工具，可导入图片并将其映射到 ArkPaint 的 40 色调色板，然后通过 TCP ADB 将结果绘制到 Android 设备的 24×24 画布上。

## 来源与致谢

本仓库以 [Eraser2333/ArkPaint](https://github.com/Eraser2333/ArkPaint) 为参考代码仓库，并在其基础上增加可独立安装的原生 Android 版本及小米 Pad 7 / HyperOS 2 适配。上游仓库当前未包含明确的 `LICENSE` 文件；公开分发或再授权前，请先向原作者确认许可。明日方舟及相关素材、商标归其各自权利人所有。

## Android APK

仓库的 `android/` 目录包含原生 Android 版本。它不需要电脑、ADB 或 root，在设备本地完成图片映射，并通过由用户主动启用的无障碍悬浮绘制器执行固定的点击与滑动脚本。

主要能力：

- Android 11（API 30）及以上
- 系统文件选择器导入图片，支持 EXIF 方向
- 可拖动/缩放的方形裁剪或整图拉伸、分级 Lanczos 清晰缩放、可调锐化、1～4 格像素合并、透明底色和抖动
- RGB、加权 RGB、CIE Lab、CIEDE2000、OKLab 五种 40 色映射方法
- 设备内截图验证、五点坐标校准、单指顺序点击、调色板滚动和白色格跳过
- 悬浮条真正的暂停/继续、独立停止按钮与“音量减”紧急停止
- 小米 HyperOS 悬浮窗备用权限、启动重试与设备内故障诊断
- 校准误点外侧角标时自动吸附真实画布边缘，兼容 3200×2136 高分辨率网格
- 自动识别调色板页、固定宽度进度与 ETA、原任务继续、停止后续画、最终逐格核对和有限次补画
- 不申请网络权限，截图与图案只保存在本机

直接安装和使用说明见 [`android/README.md`](android/README.md)。

## 从源码运行

需要安装 Python 3.11 或更高版本。

```powershell
python -m pip install -r requirements.txt
python .\main.py
```

默认连接地址为 `127.0.0.1:5555`。地址栏也支持填写 `emulator-5554` 之类的 ADB 设备序列号。使用设备序列号时，程序会通过内置的 Python 传输模块连接本机 ADB Server，因此不需要额外提供 `adb.exe`。

ADB Server 的默认端口为 `5037`，如有需要，可以在 `settings.toml` 中修改。连接设置会保存在应用程序所在目录。

## 使用流程

1. 点击 `连接并验证`。程序会截取 Android 屏幕，并检查当前界面是否为横屏 ArkPaint 布局，包括正方形画布、24×24 网格和四列调色板。
2. 点击 `导入图片` 选择本地图片；也可以点击 `截图`，在屏幕上拖选任意区域并直接导入。对于非正方形图片，可以使用内置的 1:1 裁剪框，或者选择将整张图片拉伸到画布尺寸。
3. 选择六种 Pillow 重采样方法之一、RGB／加权 RGB／Lab 映射方法、透明像素所使用的调色板颜色，以及是否启用 Floyd-Steinberg 抖动。修改设置后，24×24 映射结果会实时更新。
4. 点击 `开始绘制`。确认后，程序会绘制全部 576 个格子，其中包括白色格子。点击 `取消` 会停止绘制并保留当前已完成的部分；点击 `重新绘制` 会从头绘制当前图片。

程序不会自动点击 ArkPaint 中的 `保存` 或 `完成并发布` 控件。可以使用 `导出映射图` 将映射后的 PNG 图片保存到本地。

## 配置说明

`settings.toml` 用于保存以下设置：

- ADB 主机地址和端口
- 夜间模式
- 操作日志开关
- 界面中设置的绘制延迟

每次导入图片时，图像处理选项都会恢复为内置默认值。启用操作日志后，ArkPaint 会在可执行文件所在目录生成按日期命名的 `ArkPaintYYYY-MM-DD.log` 文件。

首次启动时，程序会在可执行文件所在目录自动创建 `settings.toml`。

## 构建便携版 EXE

安装开发依赖，然后运行独立构建脚本：

```powershell
python -m pip install -r requirements-dev.txt
python .\scripts\build_exe.py
```

脚本会使用 `ico.png` 作为应用图标，并生成单文件可执行程序 `dist/ArkPaint.exe`。首次启动时，程序会在可执行文件旁创建 `settings.toml`。构建完成后，脚本会清理 PyInstaller 临时目录；如果可执行文件超过 200 MB，构建将失败。

运行生成的程序不需要系统中存在 `adb.exe`。

## 项目结构

```text
src/arkpaint/adb/         TCP ADB 直连模块
src/arkpaint/imaging/     调色板映射与屏幕检测
src/arkpaint/drawing/     支持取消的画布绘制模块
src/arkpaint/gui/         PySide6 界面、后台任务与主窗口
android/                  可独立构建、安装的原生 Android 应用
scripts/build_exe.py      可复现的便携版构建脚本
scripts/build_android.ps1 Android debug APK 构建脚本
testpic/                  测试图片
```
