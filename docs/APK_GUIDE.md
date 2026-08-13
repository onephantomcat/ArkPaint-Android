# APK 安装与校验说明

本文说明公开安装包的版本信息、安装方式、权限用途、升级注意事项和完整性校验方法。

## 安装包信息

| 项目 | 内容 |
| --- | --- |
| 文件名 | `ArkPaint-v1.1.1-iQOO-OriginOS-calibration-fix-debug.apk` |
| 下载地址 | [GitHub Release](https://github.com/onephantomcat/ArkPaint-Android/releases/tag/android-v1.1.1) |
| 应用名称 | ArkPaint |
| 应用包名 | `com.eraser2333.arkpaint` |
| versionName | `1.1.1-android` |
| versionCode | `9` |
| 最低系统 | Android 11 / API 30 |
| 目标系统 | Android 15 / API 35 |
| 文件大小 | 13,133,151 字节（约 12.52 MiB） |
| SHA-256 | `E907AA86657015CC06E90E2269EE455EA0CEDB08C51B0597EA741C22D457BD39` |

该包优先在小米 Pad 7、HyperOS 2、3200×2136 横屏环境下适配，并加入 iQOO / OriginOS 的系统栏、安全区与截图坐标换算兼容；其他设备首次使用仍需执行五点校准。

## 签名说明

该 APK 已通过 APK Signature Scheme v2 验证，签名证书信息如下：

- 证书名称：`C=US, O=Android, CN=Android Debug`
- 证书 SHA-256：`950EBDE6B833C92284BC1D71006FCCF3DFFE23ACA7C3838C9752669900302E4E`

这是 Android Debug 证书签名的测试侧载包，不是应用商店正式发布证书。Android 只允许相同包名、相同签名的 APK 覆盖升级；如果设备上的旧版使用了不同签名，系统会提示“应用未安装”或“签名不一致”。此时需要先卸载旧版再安装，但卸载会清除应用保存的图案、参数和校准数据。

## 安装步骤

1. 从 [Release 页面](https://github.com/onephantomcat/ArkPaint-Android/releases/tag/android-v1.1.1) 下载 APK。
2. 在文件管理器或浏览器中打开 APK。
3. 如果系统拦截，按提示允许当前文件管理器或浏览器“安装未知应用”。
4. 确认应用名称为 ArkPaint 后完成安装。
5. 首次打开时先导入一张图片，确认裁剪与 24×24 预览正常，再配置悬浮绘制器。

安装完成后可以关闭文件管理器或浏览器的“安装未知应用”权限；ArkPaint 日常运行不需要该权限。

## 从旧版本升级

1. 先在系统无障碍设置中关闭旧版“ArkPaint 悬浮绘制器”。
2. 直接打开新 APK 尝试覆盖安装。
3. 如果提示签名冲突，记录当前参数后卸载旧版，再安装新版本。
4. 打开新版本，重新开启厂商系统悬浮窗备用权限和无障碍服务。
5. 若系统分辨率、显示缩放或导航栏布局发生变化，请重新执行五点校准。

## 权限用途

### 无障碍服务

由用户在系统设置中主动开启，仅用于：

- 截取当前画像册画面进行布局和逐格验证；
- 执行用户启动的固定 24×24 点击脚本；
- 滑动右侧调色板；
- 响应悬浮条的暂停、继续和停止操作。

### 显示在其他应用上层

这是小米 / HyperOS、iQOO / OriginOS 等厂商系统的备用悬浮窗通道。当系统拒绝标准无障碍悬浮层时，用于显示控制条；如果标准悬浮条正常，可以不授予。

### 网络与文件

- APK 未声明 `android.permission.INTERNET`，不会上传截图或图片。
- 图片通过 Android 系统文件选择器读取，不申请整个存储空间的广泛访问权限。
- 图案、参数、截图分析结果和校准信息保存在设备本地。

## Windows 校验 SHA-256

在 PowerShell 中运行：

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath '.\ArkPaint-v1.1.1-iQOO-OriginOS-calibration-fix-debug.apk'
```

输出必须与下面的值完全一致：

```text
E907AA86657015CC06E90E2269EE455EA0CEDB08C51B0597EA741C22D457BD39
```

如果不一致，请删除该文件并重新从 GitHub Release 下载，不要继续安装。

## 常见安装问题

### 系统提示“应用未安装”

- 检查 Android 版本是否为 11 或更高。
- 检查是否已安装同包名但不同签名的版本；必要时先卸载旧版。
- 确认 APK 的 SHA-256 与本文一致，并重新下载损坏的文件。

### 小米系统阻止安装

- 在当前文件管理器的应用信息中临时允许“安装未知应用”。
- 完成系统安全扫描后再次确认安装。
- 安装完毕后可立即关闭该临时权限。

### 覆盖安装后悬浮绘制器故障

先关闭系统中的旧无障碍开关，再重新开启；随后回到 ArkPaint 点击“显示 / 重试悬浮条”。详细步骤见 [软件使用说明](USER_GUIDE.md#悬浮条不出现或系统提示服务故障)。
