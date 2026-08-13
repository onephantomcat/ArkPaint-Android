# ArkPaint Android v1.1.1

构建日期：2026-08-13

本版本修复 iQOO / OriginOS 设备在完成五点校准后反复提示“屏幕方向或比例已改变”的问题，同时保留小米 Pad 7 / HyperOS 2 的优先适配。

## 修复内容

- 校准点改用触摸事件的原始屏幕坐标，不再把安全区内的悬浮层尺寸误当成完整系统截图尺寸。
- 第五点完成后立即截取当前画面，把校准点映射到无障碍截图的真实像素空间后再保存。
- 全屏校准层覆盖状态栏、导航栏与挖孔区域，减少 OriginOS 厂商窗口裁切造成的坐标偏差。
- 打开校准层后的前 300ms 忽略抬手事件，避免点击“校准”的同一次触摸被误记为第一个校准点。
- 首页设备标识改为 `ANDROID 11+ READY`，备用权限入口改为“厂商系统悬浮窗权限（备用）”。

## 安装包

- 文件：[ArkPaint-v1.1.1-iQOO-OriginOS-calibration-fix-debug.apk](https://github.com/onephantomcat/ArkPaint-Android/releases/download/android-v1.1.1/ArkPaint-v1.1.1-iQOO-OriginOS-calibration-fix-debug.apk)
- 版本：`1.1.1-android`（versionCode `9`）
- 应用包名：`com.eraser2333.arkpaint`
- 最低系统：Android 11（API 30）
- 文件大小：13,133,151 字节
- SHA-256：`E907AA86657015CC06E90E2269EE455EA0CEDB08C51B0597EA741C22D457BD39`
- 签名：Android Debug 证书；APK Signature Scheme v2 已验证

## 升级后操作

1. 安装修复版并重新开启“ArkPaint 悬浮绘制器”无障碍服务。
2. iQOO / OriginOS 如果未显示控制条，再开启“厂商系统悬浮窗权限（备用）”。
3. 保持画像册完整横屏，重新执行一次五点校准。
4. 点击第五点后等待“正在匹配当前设备的截图比例…”变为“校准完成”，再点“验证”。

## 验证记录

- `clean testDebugUnitTest lintDebug assembleDebug` 构建成功。
- 30 个单元测试全部通过，0 个失败、错误或跳过。
- Android Lint：0 个错误；5 条警告均为依赖版本更新提示。
- Android 15、2400×1080 横屏、三键导航模拟环境中，校准保存尺寸与系统截图均为 `2400×1080`。
- 校准后的“验证”进入正常网格识别流程，不再触发比例变动错误。

此 APK 使用 Android Debug 证书签名，适合测试侧载，不是应用商店正式发布包。
