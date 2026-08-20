# 短视频图片数据采集器

> **重要说明：这是一个超级初期测试版。** 当前版本仍包含各种问题，最明显的问题是可能误判目标内容没有处于前台，或者因为前台判断、无障碍服务、截屏授权、网络连接、画面切换确认等原因意外暂停采集。采集结果必须由使用者自行检查，不能默认认为每个视频都已完整采集或每个目录都对应一条完整视频。欢迎使用者根据自己的设备、平台和采集需求自行修改、修复和完善代码。

本项目包含两部分：

- `android/`：安卓采集器。通过 MediaProjection 截屏、无障碍手势翻页、悬浮窗控制，并在确认画面已经切换后才创建下一个视频目录。
- `windows/`：Windows 接收端。接收 PNG 并原子写入 `dataset/video_XXXXXX/frame_XXXXXX.png`。

## 换电脑使用：Windows 便携版

直接使用 `ShortVideoCollectorPortable-Windows.zip`。新电脑无需安装 Python、Android Studio 或 Gradle：

1. 把 ZIP 复制到新电脑并完整解压，不能只取出单独的 EXE。
2. 双击 `START_RECEIVER.bat` 或 `ShortVideoCollectorPortable.exe`。
3. 图片默认保存在便携文件夹旁的 `dataset`；配置保存在同目录的 `receiver-config.json`。
4. 无线模式使用窗口显示的新电脑局域网 IP；USB 模式运行包内的 `enable_usb_debug.ps1`，手机地址填写 `127.0.0.1`。
5. 便携包内已附带 Android APK 和 ADB，不要求新电脑安装开发环境。USB 驱动仍由 Windows 或手机厂商提供。

如果需要继续旧电脑的视频编号，把旧的 `dataset` 整个复制到便携目录；接收端会扫描已有 `video_XXXXXX` 并从最大编号继续。

## 快速开始

### 1. Windows 接收端

双击 `windows/start_receiver.bat`。首次启动会在接收端窗口显示：

- 本机局域网 IP；
- 端口（默认 `8765`）；
- 配对码（默认首次随机生成并保存）；
- 数据集目录。

如果 Windows 防火墙询问，请仅允许“专用网络”。手机和电脑需要位于同一可信局域网。

### 2. 安装安卓端

已构建的 APK 位于 `release/ShortVideoCollector-debug.apk`（执行构建脚本后生成）。也可运行：

```powershell
.\build_android.ps1
```

手机开启 USB 调试并连接后，可运行：

```powershell
.\install_android.ps1
```

### 3. 手机配置与采集

1. 打开安卓应用，输入 Windows 接收端显示的 IP、端口和配对码。若使用 USB 数据线，先运行 `enable_usb_debug.ps1`，然后把电脑地址填写为 `127.0.0.1`，端口仍为 `8765`。
2. 依次授予“悬浮窗”“无障碍服务”“录屏/截屏”权限。
3. 点击“显示悬浮控制器”，切换到短视频应用并停留在要采集的第一个视频。
4. 点悬浮窗的“开始”。采集器会锁定当前前台应用作为目标。
5. 可随时“暂停/继续/停止”；悬浮球可拖动，点标题可折叠。

## 自动化策略

采集器不是固定等待后盲目上滑，而是使用安全状态机：

1. 仅在目标应用位于前台且屏幕解锁时采集。
2. 按采样间隔抓取画面；首帧必存，之后只有画面变化达到阈值或超过保底间隔才保存，避免大量重复帧。
3. 达到最大帧数、最长停留时间，或在最短停留时间后画面持续稳定，才请求翻页。
4. 上滑后先比较翻页前后的低分辨率视觉签名；连续两次确认变化且画面稳定后，才向 Windows 申请新的 `video_XXXXXX` 目录。
5. 目标应用离开前台、锁屏、无障碍失效、切换未确认、服务端拒绝或网络断开时，立即安全暂停，不继续截图或翻页。

通用采集器无法从所有短视频应用可靠读取“视频真正播放完毕”事件，因此这里的“采集完成”指达到可配置的覆盖条件。设置页可以调整采样间隔、每视频最大帧数和最长停留时间。

## USB 数据线模式

USB 模式使用 ADB reverse 将手机本地端口转发到电脑，不改变图片接收协议，也不依赖 Wi-Fi。

1. 手机开启开发者选项和 USB 调试。
2. 用支持数据传输的 USB 线连接电脑，并在手机上确认 RSA 调试授权。
3. 先启动 Windows 接收端（默认端口 `8765`）。
4. 在项目根目录 PowerShell 运行 `.\enable_usb_debug.ps1`。
5. Android 应用中将电脑地址填写为 `127.0.0.1`，端口填写 `8765`，配对码填写 Windows 接收端显示的值。
6. 采集期间保持 USB 线连接；结束后可运行 `.\disable_usb_debug.ps1`。

## 接收端命令行

```powershell
py windows\receiver.py --headless --host 0.0.0.0 --port 8765 --dataset D:\dataset --token <your-pair-token>
```

健康检查：`http://电脑IP:8765/api/v1/health`。

## 数据安全与恢复

- 图片先写 `.part` 临时文件，完成 `fsync` 后再原子改名，避免留下半张 PNG。
- 已存在且内容相同的重试帧会作为幂等请求接受；同名但内容不同会返回冲突，绝不覆盖。
- 接收端重启会扫描已有 `video_XXXXXX`，从最大编号继续。
- 每次重新开始任务都会创建新视频目录；停止不会删除已采集图片。
- 内部仅在 Windows 用户配置目录保存少量接收端配置，不在数据集内生成 JSON 元数据。

## 已知边界

- 某些受 DRM/安全窗口保护的应用会返回黑屏，Android 系统层面无法绕过。
- 不同短视频应用的滑动距离可能不同；默认从屏幕 78% 高度滑到 25%，可在源码常量中调整。
- 无障碍服务只用于前台应用检测和用户授权的上滑手势，不读取页面文字或内容。
- 使用前请遵守目标平台条款、内容版权和当地隐私/数据法规。

## 项目定位

这个采集器是模型训练和人工复核前的数据准备工具，不是守目人 Android 保护应用本身，也不负责 OCR、健康营销判断或模型推理。它只负责在用户明确授权后截取用户当前观看的短视频/直播画面，把截图按视频分组传到用户指定的 Windows 电脑，后续可以交给清洗、标注和训练流程使用。

采集器与模型、软件的关系是：采集器生成 `dataset/video_XXXXXX/frame_XXXXXX.png`；模型训练流程读取经过授权和清洗的数据；守目人应用使用已经导出的移动端模型。采集器不会把截图上传到模型仓库，也不会自动发送到第三方云服务。

## 组件结构

```text
Android 采集端
  -> MediaProjection 截屏
  -> 画面变化签名和采样状态机
  -> Windows 接收端 HTTP API
  -> PNG 原子写入 dataset/video_XXXXXX/
```

Android 端包含三个主要部分：`CaptureService` 管理 MediaProjection、悬浮控制器和采样状态机；`CollectorAccessibilityService` 只跟踪前台应用并执行用户授权的上滑手势；`NetworkClient` 使用配对码和会话编号向接收端发送请求。Windows 端使用 Python 标准库提供 GUI/命令行接收服务，不依赖数据库。

## 采集状态机

采集器不会按照固定时间盲目截图和上滑，而是按以下状态推进：

```text
READY -> CONNECTING -> RUNNING -> SWITCHING -> RUNNING
                         |             |
                         v             v
                      PAUSED         ERROR
```

`RUNNING` 中会保存首帧、达到画面变化阈值的帧以及定期保底帧；达到最大帧数、最长停留时间或画面稳定条件后进入 `SWITCHING`。上滑后必须连续确认画面签名发生足够变化，才向接收端申请新的视频目录。目标应用离开前台、屏幕锁定、无障碍服务失效、截屏授权结束、网络请求失败或切换无法确认时，采集器会暂停或进入错误状态，不继续把内容混入旧目录。

## Windows 接收协议

默认监听地址为 `0.0.0.0:8765`，Android 端通过 HTTP 明文连接。所有需要写入数据的请求都必须携带 `Authorization: Bearer <配对码>`；建立会话后还必须携带 `X-Session-Id`。当前 API 为：

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| `GET` | `/api/v1/health` | 检查接收端是否运行，不要求会话 |
| `POST` | `/api/v1/session` | 使用配对码建立采集会话 |
| `POST` | `/api/v1/video` | 为当前会话创建下一个 `video_XXXXXX` 目录 |
| `POST` | `/api/v1/frame?video=N&frame=M` | 上传 PNG 图片 |
| `POST` | `/api/v1/session/end` | 结束当前会话 |

接收端会验证 PNG 签名、大小和会话归属。写入过程先保存为 `.part` 临时文件，完成 `fsync` 后再原子改名；相同视频和帧编号的相同内容会返回幂等成功，不同内容不会覆盖原文件。

## 权限、网络与隐私

- Android 的 MediaProjection 权限用于系统截屏；用户可以在系统提示中随时拒绝或结束授权。
- 悬浮窗权限只用于显示开始、暂停、停止和状态信息的悬浮控制器。
- 无障碍权限只用于确认前台应用和执行固定的向上滑动，不读取页面文字，不执行 OCR。
- 网络权限用于把截图发送到用户自己配置的 Windows 接收端；本项目没有内置云端地址。
- 默认协议是局域网 HTTP，不提供 TLS。只应在可信的家庭/实验室网络中使用，不应把端口暴露到公网或不可信 Wi-Fi。
- 配对码是访问控制，不是加密。不要把配对码发布到仓库、截图或公开日志中；怀疑泄露时应停止接收端并更换配对码。
- 原始截图可能包含人脸、账户名、商品信息和其他个人数据。采集前必须确认数据来源、平台条款、版权和隐私依据，发布数据集前应重新脱敏并确认再分发权利。

## 输出和下游使用

接收端输出结构如下：

```text
dataset/
  video_000001/
    frame_000001.png
    frame_000002.png
  video_000002/
    frame_000001.png
```

目录编号由接收端扫描已有目录后继续递增。一个目录表示一次经过切换确认的采集片段，不保证它等同于平台上的完整视频；暂停后继续、连接恢复或切换确认失败后恢复可能产生新的目录，这是为了避免不同内容混组。下游训练前应执行去重、质量检查、隐私清理、人工标签冻结和数据拆分，不能直接把未经审查的原始截图发布或用于高影响决策。

## 构建、测试和便携包

Android 工程使用 Java 17、Android Gradle Plugin、`minSdk 26`、`targetSdk 36`，默认输出 arm64/通用 APK。Windows 接收端只使用 Python 标准库；便携包可通过 PyInstaller 生成，并附带 APK、ADB 工具和中文操作指南。

```powershell
.\run_tests.ps1
.\build_android.ps1
.\build_portable.ps1
```

`run_tests.ps1` 会执行 Windows 接收端的会话、配对码、编号恢复、幂等上传和便携路径测试。构建生成的 `android/**/build/`、`.gradle/`、`release/`、`dataset/`、`.portable-build/`、`__pycache__/` 和 ZIP/APK 输出属于本地产物，已由 `.gitignore` 排除，不应作为源码提交。

## 目录说明

```text
android/                 Android 采集端源码和资源
windows/                 Windows 接收端、测试和启动脚本
portable_assets/         便携包启动文件和中文说明
build_android.ps1        构建 Android Debug APK
build_portable.ps1       构建 Windows 便携包
install_android.ps1      通过 ADB 安装 APK
enable_usb_debug.ps1     建立 ADB reverse USB 转发
disable_usb_debug.ps1    移除 USB 转发
操作教程.md              面向第一次使用者的中文操作步骤
```

## 与模型仓库的关系

采集器本身不包含模型权重，也不需要 Hugging Face 账号。完成采集后，经过授权的数据可以进入模型训练和评估流程；模型结构、训练说明和移动端运行时文件见 [KeepersEye-1](https://huggingface.co/AvalonskyAfar/KeepersEye-1)。采集器与模型仓库之间没有自动上传或自动同步机制。

使用本采集器时，请同时遵守目标平台服务条款、适用的隐私和数据保护法规，以及所采集内容的版权和再分发要求。第三方组件、平台服务和用户采集数据不因本项目发布而获得额外授权。
