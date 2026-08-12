# ArkPaint Android

ArkPaint 的原生 Android 适配版，可在设备本地把图片转换为 24×24 图案，并通过用户主动启用的悬浮绘制器完成校准、验证、绘制、暂停续画和逐格核对。

本版本优先兼容 **小米 Pad 7 / HyperOS 2（Android 15）**，不需要电脑、ADB 或 root，也不申请网络权限。

当前源码版为 `1.1.0-android`（versionCode `8`）：主界面已改为适配平板横屏的 Material 3 双栏工作台，新增游戏调色板顺序色号、用色统计和逐格手动像素编辑器。完整改动见 [v1.1.0 版本说明](docs/releases/android-v1.1.0.md)。

## 直接下载

- **APK：** [ArkPaint-v1.1.0-XiaomiPad7-pixel-editor-debug.apk](https://github.com/onephantomcat/ArkPaint-Android/releases/download/android-v1.1.0/ArkPaint-v1.1.0-XiaomiPad7-pixel-editor-debug.apk)
- **版本：** `1.1.0-android`（versionCode `8`）
- **系统要求：** Android 11（API 30）及以上
- **SHA-256：** `520CC5498AC3108CD5859E275EF7DB9F70E664205A0922C1C22793A529D32A75`

> 当前公开 APK 使用 Android Debug 证书和 APK Signature Scheme v2 签名，定位为测试侧载包。安装、升级和签名注意事项见 [APK 安装与校验说明](docs/APK_GUIDE.md)。

## 快速使用

1. 安装 APK，打开 ArkPaint 并点击“导入图片”。
2. 在裁剪器中单指拖动、双指缩放，点击“使用此区域”。
3. 调整清晰度和颜色参数；需要时进入“手动修整”逐格修改，再点击“保存给绘制器”。
4. 小米设备先开启“悬浮窗权限（备用）”，再开启“ArkPaint 悬浮绘制器”无障碍服务。
5. 进入横屏画像册创作页，把 24×24 画布缩到最小并把调色板滚到顶部。
6. 在悬浮条依次执行“校准”→“验证”→“绘制”。绘制中可暂停、继续或停止。
7. 核对完成后，由用户自行点击游戏中的“保存”或“完成并发布”。

完整步骤、推荐参数和故障排查见 [软件使用说明](docs/USER_GUIDE.md)。

## 文档

- [APK 安装、升级、签名与 SHA-256 校验](docs/APK_GUIDE.md)
- [软件完整使用说明](docs/USER_GUIDE.md)
- [Android 源码构建说明](android/README.md)
- [v1.1.0 版本说明](docs/releases/android-v1.1.0.md)
- [v1.0.5 发布说明](docs/releases/android-v1.0.5.md)

## Android 版能力

- 系统文件选择器导入图片，自动处理 EXIF 方向
- 可拖动和双指缩放的方形裁剪，也可选择整图拉伸
- 分级 Lanczos 清晰缩放、可调锐化、亮度、对比度和饱和度
- RGB、加权 RGB、CIE Lab、CIEDE2000、OKLab 五种 40 色映射
- 按游戏内四列调色板顺序显示 `01`～`40` 色号与每色用量
- 手动逐格上色、拖动连续绘制、吸色、撤销/重做和恢复自动转换结果
- 1×1～4×4 像素合并、透明底色和 Floyd–Steinberg 抖动
- 五点坐标校准、截图验证、调色板页识别和白色格跳过
- 真正的暂停/继续、独立停止按钮与“音量减”紧急停止
- 停止后续画、最终逐格核对和最多两轮有限补画
- 小米 HyperOS 悬浮窗备用权限、启动重试与故障状态提示
- 截图、原图和图案只保存在本机，不声明 `INTERNET` 权限

## 清晰度建议

- 照片或普通插画：使用“Lanczos 清晰缩放（推荐）”，锐化建议 `35～55`。
- 原生像素画：使用“邻近采样（像素画）”，通常关闭抖动。
- 想保留最多细节：选择“1×1 原始格”；2×2～4×4 合并会主动减少细节。
- 色块插画：优先尝试默认的 CIE Lab；渐变较多时可比较 CIEDE2000 与 OKLab。

## Android 源码构建

需要 JDK 17、Android SDK Platform 35，以及 Build Tools 34.0.0 或 35.0.0。

在仓库根目录运行：

```powershell
.\scripts\build_android.ps1 -AndroidSdk "C:\path\to\Android\Sdk"
```

也可以创建 `android/local.properties` 后执行：

```powershell
cd android
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

## 桌面版源码

仓库仍保留原 PySide6 / TCP ADB 桌面版源码，便于追溯和继续开发。运行环境为 Python 3.11 或更高版本：

```powershell
python -m pip install -r requirements.txt
python .\main.py
```

构建便携 EXE：

```powershell
python -m pip install -r requirements-dev.txt
python .\scripts\build_exe.py
```

## 项目结构

```text
android/                  原生 Android 应用
docs/                     APK 与软件使用文档
src/arkpaint/             PySide6 桌面版源码
scripts/build_android.ps1 Android APK 构建脚本
scripts/build_exe.py      Windows 便携版构建脚本
testpic/                  测试图片
```

## 来源与致谢

本仓库以 [Eraser2333/ArkPaint](https://github.com/Eraser2333/ArkPaint) 为参考代码仓库，并在其基础上增加原生 Android 版本及小米 Pad 7 / HyperOS 2 适配。Git 历史中保留原作者提交和署名。

上游仓库当前未包含明确的 `LICENSE` 文件；公开分发或再授权前，请先向原作者确认许可。明日方舟及相关素材、名称和商标归各自权利人所有。本工具不会自动点击游戏中的“保存”“完成”或“发布”；使用自动化前请自行确认游戏规则与账号风险。
