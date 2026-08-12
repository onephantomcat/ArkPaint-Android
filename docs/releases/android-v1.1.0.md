# ArkPaint Android v1.1.0

发布日期：2026-08-12

本版本继续优先适配小米 Pad 7 / HyperOS 2（Android 15），重点加入平板横屏工作台、游戏调色板顺序色号和可撤销的逐格像素编辑能力。

## 安装包

- 文件：[ArkPaint-v1.1.0-XiaomiPad7-pixel-editor-debug.apk](https://github.com/onephantomcat/ArkPaint-Android/releases/download/android-v1.1.0/ArkPaint-v1.1.0-XiaomiPad7-pixel-editor-debug.apk)
- 版本：`1.1.0-android`（versionCode `8`）
- 应用包名：`com.eraser2333.arkpaint`
- 最低系统：Android 11（API 30）
- 文件大小：13,130,397 字节
- SHA-256：`520CC5498AC3108CD5859E275EF7DB9F70E664205A0922C1C22793A529D32A75`
- 签名：Android Debug 证书，APK Signature Scheme v2 已验证

## 本版重点

- 主界面改为 Material 3 深色双栏工作台，针对小米 Pad 7 横屏和系统栏安全区域调整布局。
- 预览按游戏内四列调色板的可见顺序显示 `01`～`40` 色号，并统计每个色号的像素用量。
- 新增逐格像素编辑器，支持点按上色、拖动连续绘制、吸色、撤销、重做和恢复自动转换结果。
- 手动修改可同步回主预览、本地保存图案和悬浮绘制器，不需要重新导入图片。
- 修复设备旋转后重复打开裁剪器、覆盖手动修改结果的问题。
- 加入自适应单色启动图标，并完善 Android 15 的 edge-to-edge 显示。

## 色号说明

`01`～`40` 表示明日方舟画像册创作页中四列调色板的可见位置顺序：每行从左到右，再从上到下。程序不会为这些颜色编造官方名称；如果游戏后续调整调色板顺序，需要同步更新应用中的颜色表。

## 验证记录

- 执行 `clean testDebugUnitTest lintDebug assembleDebug`，构建通过。
- 共运行 27 个单元测试，0 个失败。
- Android Lint：0 个错误；保留 4 条仅提示依赖可升级的警告。
- 在 Android 15、3200×2136 横屏模拟器中验证图片导入、裁剪、色号预览、用色统计、逐格修改、撤销/重做、吸色、应用修改和旋转状态保持。
- APK 已完成安装与启动验证。

## 安装前必读

本 APK 使用 Android Debug 证书签名，适合测试侧载，不是应用商店正式发布包。若设备上的旧版本签名不同，需要先卸载旧版；卸载会清除应用参数与校准数据。

首次安装或升级后，建议重新检查“显示在其他应用上层”备用权限和“ArkPaint 悬浮绘制器”无障碍服务。系统分辨率、显示缩放或导航栏布局发生变化时，应重新执行五点校准。

## 使用文档

- [APK 安装、升级、签名与完整性校验](https://github.com/onephantomcat/ArkPaint-Android/blob/main/docs/APK_GUIDE.md)
- [软件完整使用说明](https://github.com/onephantomcat/ArkPaint-Android/blob/main/docs/USER_GUIDE.md)
- [Android 源码构建说明](https://github.com/onephantomcat/ArkPaint-Android/blob/main/android/README.md)

## 来源说明

本项目参考 [Eraser2333/ArkPaint](https://github.com/Eraser2333/ArkPaint)，Git 历史保留原作者署名。明日方舟及相关素材、名称和商标归各自权利人所有。
