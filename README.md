# Nikon D300 Controller

通过 **USB OTG** 用 **PTP（Picture Transfer Protocol）** 协议控制尼康 **D300 / D300s** 单反相机的 Android 应用。不需要相机厂商 SDK，直接走标准 PTP / 尼康私有扩展指令，在手机上完成实时取景、对焦、曝光参数调节、个人设定（CSM）查看与修改、相册浏览与导出。

> English: An Android app that drives a Nikon D300 over USB OTG via the PTP protocol — live view with 51-point AF overlay, touch AF, exposure controls, custom settings (CSM) menu, on-camera gallery browser, NEF → JPEG conversion with real RAW decoding, and shutter-count readout.

---

## 下载安装

到 [Releases](https://github.com/Pinksert/NikonD300Controller/releases) 下载最新的 `NikonD300Controller-vX.Y.apk`，在手机上允许「未知来源」安装即可。

| 项目 | 要求 |
| --- | --- |
| 手机 / 平板 | 支持 USB OTG 的 Android 设备 |
| 系统版本 | Android 7.0（API 24）及以上（`minSdk = 24`） |
| 相机 | 尼康 D300 / D300s（其他尼康机型未验证） |
| 线材 | USB OTG 转接线 / 转接头 |
| 相机 USB 模式 | 设定菜单 → USB → **MTP/PTP**（选「大容量存储」机身不接受任何指令） |

> `applicationId` 为 **`com.example.nikond300controller2`**，与同名的旧版安装包是两个独立 App，不会互相覆盖。
> Release 包与早期流传的 debug 包签名不同，**覆盖安装会失败**，请先卸载旧版。

---

## 功能特性

### 连接

- 插入 OTG 线即自动请求 USB 权限并建立 PTP 会话，连接时把机身支持的全部操作码打进日志页。
- USB 抖动（OTG 被碰一下、手机给口断电再上电）不再当作断连：延迟 1.2 秒确认设备真的没了才断开，抖动则静默重建连接。

### 实时取景与对焦

- **取景**：`0x9201` 启动 / `0x9202` 关闭 / `0x9203` 拉帧，无需把机身释放模式拨盘转到 Lv 档。
- **两种取景模式**（长按「取景」按钮切换，写 `0xD1A0`）：
  - **手持** — 51 点相位检测 AF，AF 框画面叠加层可直接点选，机身记住 51 / 11 点档位（`0xD08D`）。
  - **三脚架** — 对比度检测 AF，AF 框能停在画面**任意位置**（`0x9205` 绝对定位）。
- **触摸对焦**：点画面哪儿就对哪儿。手持下把点击吸附到最近的对焦点（写 `0xD108`）再自动补一次 `0x90C1`；三脚架下机身自己会跑对比度 AF，不再叠加。
- **取景放大** `0xD1A3`（手持最大 3 倍 / 三脚架最大 13 倍）：放大时点阵与点击坐标双向换算，放大状态下禁止挪对焦点（否则画面会跟着跑）。
- **峰值对焦**：Sobel 边缘描亮，手动对焦时看哪儿实了。
- **取景录制**：机身不会录像，录制是把取景帧在手机侧压成 MP4。
- **反光板预升**：机身拨盘转到 Mup，App 连按两次快门（第一次抬板、第二次曝光）。

### 曝光与拍摄

- ISO（含自动开关、自动上限 / 最小快门）、光圈、快门（含 B 门 / 闪光同步，档位表**每次点开都重读机身描述符**并与缓存取并集）。
- 曝光补偿（`0x5010`，1/1000 EV 精度，档位严格按机身枚举）、曝光程序模式 M / P / A / S、白平衡（含色温与预设）。
- b1 / b2 / b3 步长（1/3、1/2、1 EV）可就地切换，档位表跟着变。
- 包围曝光：光圈包围 / 快门包围，倒计时与张数可调。
- 拍摄流程：自拍延时、间隔拍摄、拍完自动下载。

### 机身菜单

- **相机设定（CSM）**：a–f 共 50 项，按机身实际返回的量程只读或写入；属性码未确认的项灰显、不写入。
- **拍摄菜单**：色彩空间、动态 D-Lighting、JPEG 压缩、NEF 位深、长曝降噪、高 ISO 降噪、取景驱动模式、放大倍率。
- **测光**机身报只读，页面只标当前档并提示用机身转盘切换。

### 相册

- 浏览存储卡照片，两阶段加载（先铺满信息再逐张补缩略图），按文件名流水号倒序。
- 单张查看器与多选模式共用一套底栏：**原图导出 / JPG 导出 / 删除 N 张 / 退出**。
- **JPG 导出**：NEF 经 **LibRaw（原生 C++/JNI）** 真解 RAW（霍夫曼解码 → 线性化 → 机身白平衡 → AHD 插值 → sRGB）再压 JPEG，可选 60/75/85/92/95/100 六档品质；全 JPEG 时不弹品质表。
- **EXIF 搬运**：把 NEF 的 IFD0 / ExifIFD / GPS / Interop 条目**重建**成新 TIFF 塞进输出 JPEG 的 APP1（MakerNote 过大时先丢它，保证不超 65533 上限），拍摄日期 / 光圈快门 ISO 全部保留。
- **删除**：标准 `0x100B DeleteObject`，操作前先问机身是否支持，不支持则明确告知。
- **EXIF 摘要**：快门 / 光圈 / ISO / 焦距 / 补偿 / 白平衡 / 测光渲染为三行信息条。
- **快门数读取**：从 EXIF 尼康 MakerNote 读（机身没有对应的 PTP 属性）。

---

## 快速上手

1. 机身 USB 设为 **MTP/PTP**，OTG 线接好手机与相机，相机开机。
2. 打开 App 点「连接相机」，首次弹 USB 授权时勾「默认」再确认。
3. 左上角显示机型名（如 D300）即已连上。
4. 点「取景」等画面出来；打开「触摸对焦」后点画面即可对焦（三脚架模式可对任意位置）。
5. 底部导航：**拍摄 / 参数 / 相册**，日志在顶栏右侧按钮里。

App 内「参数页 → 机身 → 使用说明」有完整图文步骤，首次启动会自动弹一次。

---

## 从源码构建

需要 JDK 17+、Android SDK、以及 **NDK + CMake**（NEF 真解 RAW 依赖原生代码，CMake 把 `app/src/main/cpp` 下的 LibRaw 编成 `libnefdecoder.so`，ABI：`arm64-v8a`、`armeabi-v7a`）。

```bash
export JAVA_HOME="/path/to/Android Studio/jbr"
export ANDROID_HOME="$HOME/Android/Sdk"

./gradlew assembleDebug      # 调试包
./gradlew assembleRelease    # 正式包（需自行配置签名 keystore）
```

产物：`app/build/outputs/apk/debug/app-debug.apk` / `app/build/outputs/apk/release/app-release-unsigned.apk`（签名后可用）。

> 若构建报 `app\.cxx\...\hash_key.txt (拒绝访问)`，删掉 `app/.cxx` 再重试（Windows 上 CMake 缓存偶发锁死）。

---

## 项目结构

```
app/src/main/java/com/example/nikond300controller/
├── MainActivity.kt        # UI 与业务流程编排（连接、取景、对焦、参数、包围、CSM）
├── PtpUsbConnection.kt    # PTP/USB 底层：建链、命令/数据/响应收发、事件清理、管道救援
├── PtpConstants.kt        # PTP 操作码、响应码、设备属性码（含尼康 D300 CSM 码）
├── FocusBoxView.kt        # 对焦点叠加层：点阵、AF 框、构图网格、放大坐标双向换算
├── GalleryActivity.kt     # 相册：缩略图、原图查看器、多选导出与删除
├── CsmMenu.kt             # 个人设定（CSM）菜单分组与读数/写值
├── ShootingMenu.kt        # 拍摄菜单（色彩空间、D-Lighting、NEF 位深、降噪等）
├── SheetUi.kt             # 底部抽屉 / 网格选择器构件（各参数页复用）
├── NefDecoder.kt          # NEF → JPG 真转档（JNI 调 LibRaw 解 RAW，非抠预览）
├── NefPreview.kt          # NEF 预览定位 + EXIF 重建并搬进转出 JPEG
├── PhotoExif.kt           # EXIF 解析与三行信息条渲染
├── LiveViewRecorder.kt    # 取景画面录制
├── PeakingView.kt         # 峰值对焦叠加层
├── InfoActivity.kt        # 使用说明 / 关于 / 开源许可证
└── ShutterCountReader.kt  # 读取机身快门数（EXIF MakerNote）
```

核心通信封装在 `PtpUsbConnection`：所有对 `UsbDeviceConnection` 的读写都经 `usbLock` 串行化，避免轮询 / 取景 / 相册线程并发抢占同一 Bulk 端点导致响应错乱或管道挂死；每次事务后消费残留响应，必要时 `clearHalt` 救援。

> ⚠️ 只发机身**确认支持**的命令。`GetDeviceInfo` 的 OperationsSupported 会在连接时打进日志；机身不认的操作码可能连响应都不给，`usbLock` 会一直攥着，整条 USB 通道就废了。

---

## PTP 与尼康私有指令

- 标准 PTP：会话 `0x1002`/`0x1003`、设备信息 `0x1001`、取属性 `0x1015`、设属性 `0x1016`、触发捕获 `0x100E`、删除对象 `0x100B`。
- 尼康扩展：取景 `0x9201`/`0x9202`/`0x9203`、取景模式 `0xD1A0`、取景放大 `0xD1A3`、AF 驱动 `0x90C1`、取景内直拍 `0x90C0`、设备就绪 `0x90C8`、事件 `0x90C7`、AF 区域定位 `0x9205`。
- 属性集中在 `0xD000`–`0xD1FF`：对焦点 `0xD108`、AF 点数 `0xD08D`、锁焦 `0xD104`、快门 `0xD100`（`n<<16|d`，排序必须按曝光时长而非原始值）、曝光补偿 `0x5010`、CSM `a1=0xD048` … `f6=0xD05F`。
- 常量表以 RemoteYourCam 为起点，逐条对照 libgphoto2 的 `ptp.h`、真机 DeviceInfo 与 D300 固件反编译结果核过。

---

## 已知限制

- 仅针对 **尼康 D300** 真机调试，其它机型未验证。
- 手持模式的对焦点只能落在 51 个固定点位上 —— 相位检测 AF 的硬件限制，官方软件也只能逐点选；要任意位置请切三脚架模式。
- 快门数没有 PTP 通道，只能从 EXIF MakerNote 读；私有 opcode 需抓包才能确认，**不做盲试**（有砖机风险）。
- 部分机身不给 `0x100B` 删除能力，App 会明确提示而不是假装成功。
- 大文件传输被中断会导致相机 USB 端点 STALL，需重启相机或重插 OTG。

---

## 许可证

本项目代码按 **[MIT License](LICENSE)** 开源。

第三方组件各自遵循其原始许可，汇总见 **[NOTICE](NOTICE)**：

- **LibRaw**（`app/src/main/cpp/libraw`）— LGPL-2.1 / CDDL-1.0 双许可，用于 NEF 真解 RAW。
- **RemoteYourCam-USB** — PTP 常量与 D300 实机对焦点坐标的参考来源。
- **libgphoto2**（`ptp.h`）— LGPL-2.1，仅参考其常量定义，未链接代码。
- **AndroidX / Material Components** — Apache-2.0。
