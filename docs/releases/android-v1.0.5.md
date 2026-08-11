# ArkPaint Android v1.0.5

小米 Pad 7 / HyperOS 2 优先适配的 Android 测试侧载包。

## 安装包

- 文件：`ArkPaint-v1.0.5-XiaomiPad7-pause-fix.apk`
- 版本：`1.0.5-android`（versionCode `7`）
- 最低系统：Android 11（API 30）
- SHA-256：`D7E3C532F567B2984A828B528D8B823E55821BCE1680722BA3E9A7323D3C9BCD`
- 签名：Android Debug 证书，APK Signature Scheme v2 已验证

## 本版重点

- 修复初次导入图片后的方形裁剪交互
- 增加小米 / HyperOS 悬浮窗备用权限、启动重试和故障状态提示
- 完善悬浮条暂停、继续、独立停止和音量减紧急停止
- 优化 Lanczos 缩放、锐化和 24×24 清晰度
- 增加调色板页识别、停止后续画、逐格核对和有限补画
- 针对 3200×2136 横屏画布优化边缘吸附与网格验证

## 安装前必读

本 APK 使用 Android Debug 证书签名，适合测试侧载，不是应用商店正式发布包。如果旧版本签名不同，需要先卸载旧版；卸载会清除应用内参数与校准数据。

首次安装或升级后，在小米设备上建议依次开启“显示在其他应用上层”备用权限和“ArkPaint 悬浮绘制器”无障碍服务。

## 使用文档

- [APK 安装、升级、签名与完整性校验](../APK_GUIDE.md)
- [软件完整使用说明](../USER_GUIDE.md)

## 来源说明

本项目参考 [Eraser2333/ArkPaint](https://github.com/Eraser2333/ArkPaint)，Git 历史保留原作者署名。明日方舟及相关素材、名称和商标归各自权利人所有。
