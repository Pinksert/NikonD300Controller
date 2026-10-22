# Nikon D300 Controller

通过 **USB OTG** 用 **PTP（Picture Transfer Protocol）** 协议控制尼康 **D300** 单反相机的 Android 应用。无需相机厂商 SDK，直接走标准 PTP / 尼康私有扩展指令，在手机上完成实时取景、曝光参数调节、个人设定（CSM）查看与修改、相册浏览与导出。

> English: An Android app that drives a Nikon D300 over USB OTG via the PTP protocol — live view (with 51-point AF overlay), exposure controls, custom settings (CSM) menu, on-camera gallery browser, NEF preview, and shutter-count readout.

---

## 功能特性

- **USB OTG 连接**：插入 OTG 线即自动请求相机 USB 权限并建立 PTP 会话（`openSession` 后自动预设录制介质状态）。
- **实时取景（Live View）**：发送 `0x9201` 启动、`0x9202` 关闭，实时拉取取景画面；支持手持 / 三脚架取景模式与取景放大倍率调节。
- **51 点对焦框（AF 叠加层）**：`FocusBoxView` 在取景画面上绘制 51 点相位检测对焦点拓扑，支持点选对焦点与放大状态下坐标双向换算。
- **快门释放**：触发相机拍摄（标准 `0x100E` / 尼康 `0x90C0` 取景内直拍），取景帧读取让路避免管道冲突。
- **曝光参数调节**：
  - ISO（含 ISO 自动开关、自动上限 / 最小快门设置）
  - 光圈（F 值，按机身支持的档位动态生成）
  - 快门速度（自动探测机身支持的快门列表，含 B 门 / 闪光同步）
  - 曝光补偿、曝光程序模式（M / P / A / S）
  - 白平衡（含色温、预设）
- **包围曝光**：光圈包围 / 曝光包围，可连续拍摄多张。
- **个人设定菜单（CSM）**：`CsmMenu` 分组呈现 a1–a10 / b1–b6 / c1–c4 / d1–d11 / e1–e7 / f1–f10 自定义设定项，按机身实际返回的量程只读查看或写入；未适配项灰显，不写入。
- **拍摄菜单**：色彩空间、动态 D-Lighting、JPEG 压缩、NEF 位深、长曝降噪、高 ISO 降噪、取景驱动模式、放大倍率等机内拍摄项。
- **相册（GalleryActivity）**：浏览相机存储卡照片，两阶段异步加载（先铺缩略图占位、再逐张补图），查看原图、保存到本机相册、分享、删除；支持多选导出（含 NEF → JPG 转换）。
- **NEF → JPG 真转档**：`NefDecoder` 借助 **LibRaw（原生 C++ / JNI 模块，源码在 `app/src/main/cpp`）** 真正解码 D300 的 Bayer RAW 数据（霍夫曼解码 → 线性化 → 机身白平衡 → AHD 彩色插值 → sRGB），输出几 MB 的正常 JPEG，而不是机身内嵌的那张 239 KB 预览。`NefPreview` 负责从原 NEF 把 EXIF 原样搬进转出图，保证拍摄日期 / 曝光参数不丢。
- **EXIF 摘要**：`PhotoExif` 解析快门 / 光圈 / ISO / 焦距 / 补偿 / 白平衡 / 测光，渲染为三行信息条。
- **快门数读取**：`ShutterCountReader` 读取机身拍摄张数（EXIF MakerNote）。
- **取景录制**：`LiveViewRecorder` 支持把取景画面录制保存。
- **状态轮询**：电量（标准 + 尼康私有）、焦距、对焦点等实时刷新。

---

## 硬件与系统要求

| 项目 | 要求 |
| --- | --- |
| 手机 / 平板 | 支持 USB OTG 的 Android 设备 |
| 系统版本 | Android 7.0（API 24）及以上（`minSdk = 24`） |
| 相机 | 尼康 D300（其他尼康机型未验证） |
| 线材 | USB OTG 转接线 / 转接头 |
| 相机 USB 模式 | 设为 PTP / MTP（非“大容量存储”）|

> **实时取景说明**：无需拨动机身顶部的快门释放模式拨盘到 **[Lv]** 档。在任意曝光模式（**M / P / A / S**）下，直接点击 App 内的「LV」按钮即可通过 `0x9201` 启动实时取景；前置条件为相机 `RecordingMedia` 已置为 1，App 在 `openSession` 后自动预设。

---

## 安装

最简单的方式是下载已构建的调试包：

1. 打开 [Releases](https://github.com/Pinksert/NikonD300Controller/releases)。
2. 下载 `app-debug.apk`。
3. 在手机上允许「未知来源」安装，安装 APK。

> 本应用的 `applicationId` 为 **`com.example.nikond300controller2`**，会与同名的旧版安装包作为两个独立 App 共存，不会互相覆盖。
>
> 当前发布包为 **debug 签名**，仅供测试与侧载，并非上架用的正式签名包。

---

## 从源码构建

需要：Android Studio（或仅命令行）、JDK 17+、Android SDK（含对应平台与构建工具）。NEF 真转档依赖原生代码，需额外安装 **NDK** 与 **CMake**（Android Studio 的 SDK Manager 中勾选），由 CMake 把 `app/src/main/cpp` 下的 LibRaw 编为 `libnefdecoder.so`。

```bash
# 在项目根目录
export JAVA_HOME="/path/to/Android Studio/jbr"
export ANDROID_HOME="$HOME/Android/Sdk"

./gradlew assembleDebug          # 调试包
# ./gradlew assembleRelease      # 正式包（需自行配置签名 keystore）
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

---

## 使用说明

1. 用 OTG 线连接手机与相机，相机开机并进入 PTP 模式。
2. 手机弹出 USB 权限请求时点击允许。
3. 点击 **连接**，状态栏显示相机型号与电量即表示会话建立。
4. 进入 **实时取景** 预览画面；用各档位按钮调节 ISO / 光圈 / 快门 / 白平衡，可在画面上点选 51 点对焦点。
5. **相册** 中可浏览、查看原图、保存 / 分享 / 删除相机内的照片，长按进入多选可批量导出（支持 NEF 转 JPG）。
6. **个人设定 / 拍摄菜单** 可查看并修改 a–f 组自定义菜单项与机内拍摄设定。

---

## 项目结构

```
app/src/main/java/com/example/nikond300controller/
├── MainActivity.kt        # UI 与业务流程编排（连接、取景、参数、包围、CSM、相册入口）
├── PtpUsbConnection.kt    # PTP/USB 底层：建链、命令/数据/响应收发、事件清理、管道救援
├── PtpConstants.kt        # PTP 操作码、响应码、设备属性码（含尼康 D300 CSM 码）
├── FocusBoxView.kt        # 51 点对焦框叠加层（取景画面上的对焦点绘制与坐标换算）
├── GalleryActivity.kt     # 相册：缩略图、原图、保存/分享/删除、多选导出
├── CsmMenu.kt             # 个人设定（CSM）菜单分组与读数/写值
├── ShootingMenu.kt        # 拍摄菜单（色彩空间、D-Lighting、NEF 位深、降噪、取景模式等）
├── SheetUi.kt             # 底部抽屉 / 网格选择器构件（各参数页复用）
├── NefDecoder.kt          # NEF → JPG 真转档（JNI 调 LibRaw 解 RAW，非抠预览）
├── NefPreview.kt          # NEF 内嵌预览定位 + 把原 NEF 的 EXIF 原样搬进转出 JPG
├── PhotoExif.kt           # EXIF 解析与三行信息条渲染
├── LiveViewRecorder.kt    # 取景画面录制
└── ShutterCountReader.kt  # 读取机身快门数（EXIF MakerNote）

app/src/main/res/layout/
├── activity_main.xml      # 主界面
├── activity_gallery.xml   # 相册界面
├── item_gallery_photo.xml # 相册缩略图单元
└── item_menu_row.xml      # 菜单行单元
```

> 原生模块 `app/src/main/cpp/`：内含 **LibRaw**（dcraw 的库化版本）与 JNI 桥 `nef_jni.cpp`，经 CMake 编译为 `libnefdecoder.so`（目标 ABI：`arm64-v8a`、`armeabi-v7a`），由 `NefDecoder` 通过 JNI 调用完成 NEF → PPM 解码。

核心通信封装在 `PtpUsbConnection`：所有对 `UsbDeviceConnection` 的读写都经 `usbLock` 串行化，避免轮询 / 取景 / 相册线程并发抢占同一 Bulk 端点导致响应错乱或管道挂死；并在每次事务后消费残留响应、必要时 `clearHalt` 救援。

---

## PTP 与尼康私有指令

- 标准 PTP：会话 `0x1002`/`0x1003`、取设备信息 `0x1001`、取属性 `0x1015`、设属性 `0x1016`、触发捕获 `0x100E` 等。
- 尼康扩展：实时取景 `0x9201`/`0x9202`/`0x9203`、取景模式 `0xD1A0`、取景放大 `0xD1A3`、AF 驱动 `0x90C1`、取景内直拍 `0x90C0`、设备就绪 `0x90C8`、事件 `0x90C7` 等。
- 个人设定（CSM）与拍摄属性码位于 `0xD000`–`0xD1FF` 区间（如 `a1=0xD048`、`b1=0xD055`），读取以机身实际返回的量程为准；未适配项灰显，不写入。

---

## 已知问题 / 限制

- 仅针对 **尼康 D300** 真机调试，其它机型未验证，部分私有属性码可能不适用。
- 当前发布包为 debug 签名，无法上架商店。
- 大文件（RAW / 大 JPEG）传输若被中断会导致相机 USB 端点 STALL，需重启相机或重新插拔 OTG 恢复。
- 相册多选导出中的 NEF → JPG 转换目前为简化实现（直接取对象落盘），内嵌预览转 JPG 的完整路径待后续完善。
- 个人设定菜单中仍有部分属性码未完全适配，以 App 内实际可操作为准。

---

## 许可证

本项目当前 **未声明许可证（unlicensed）**。如需用于分发或修改，请先添加合适的开源许可证。
