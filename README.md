# Nikon D300 Controller

通过 **USB OTG** 用 **PTP（Picture Transfer Protocol）** 协议控制尼康 **D300** 单反相机的 Android 应用。无需相机厂商 SDK，直接走标准 PTP / 尼康私有扩展指令，在手机上完成实时取景、拍摄参数调节、个人设定（CSM）查看与相册管理。

> English: An Android app that drives a Nikon D300 over USB OTG via the PTP protocol — live view, exposure controls, custom settings (CSM) menu, and an on-camera gallery browser.

---

## 功能特性

- **USB OTG 连接**：插入 OTG 线即自动请求相机 USB 权限并建立 PTP 会话。
- **实时取景（Live View）**：发送 `0x9201` 启动、`0x9202` 关闭，实时拉取取景画面。
- **快门释放**：触发相机拍摄（`0x100E` / 尼康 `0x90C0`）。
- **曝光参数调节**：
  - ISO（含 ISO 自动上限 / 最小快门设置）
  - 光圈（F 值，按机身支持的档位动态生成）
  - 快门速度（自动探测机身支持的快门列表）
  - 曝光补偿、曝光程序模式
  - 白平衡（含色温、预设）
- **包围曝光**：光圈包围 / 曝光包围对话框，可连续拍摄多张。
- **个人设定菜单（CSM）**：a1–a10 / b1–b6 / c1–c4 / d1–d11 / e1–e7 / f1–f10 分组，只读查看与写入（以机身实际支持的值域为准）。
- **相册管理**：浏览相机存储卡照片、异步加载缩略图、查看原图、保存到本机相册、分享、删除。
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

> **实时取景前置条件**：必须把机身左上方顶部的 **快门释放模式拨盘** 旋转到 **[Lv]** 档位，且电量充足，`0x9201` 才会返回成功。

---

## 安装

最简单的方式是下载已构建的调试包：

1. 打开 [Releases](https://github.com/Pinksert/NikonD300Controller/releases)。
2. 下载 `app-debug.apk`。
3. 在手机上允许「未知来源」安装，安装 APK。

> 当前发布包为 **debug 签名**，仅供测试与侧载，并非上架用的正式签名包。

---

## 从源码构建

需要：Android Studio（或仅命令行）、JDK 17+、Android SDK（含对应平台与构建工具）。

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
4. 进入 **实时取景** 预览画面；用各档位按钮调节 ISO / 光圈 / 快门 / 白平衡。
5. **相册** 中可浏览、保存、分享、删除相机内的照片。
6. **个人设定** 可查看并修改 a–f 组自定义菜单项。

---

## 项目结构

```
app/src/main/java/com/example/nikond300controller/
├── MainActivity.kt        # UI 与业务流程编排（连接、取景、参数、包围、CSM、相册）
├── PtpUsbConnection.kt    # PTP/USB 底层：建链、命令/数据/响应收发、事件清理、管道救援
├── PtpConstants.kt        # PTP 操作码、响应码、设备属性码（含尼康 D300 CSM 码）
└── GalleryAdapter.kt      # 相册 RecyclerView 适配器
```

核心通信封装在 `PtpUsbConnection`：所有对 `UsbDeviceConnection` 的读写都经 `usbLock` 串行化，避免轮询 / 取景 / 相册线程并发抢占同一 Bulk 端点导致响应错乱或管道挂死；并在每次事务后消费残留响应、必要时 `clearHalt` 救援。

---

## PTP 与尼康私有指令

- 标准 PTP：会话 `0x1002`/`0x1003`、取设备信息 `0x1001`、取属性 `0x1015`、设属性 `0x1016` 等。
- 尼康扩展：实时取景 `0x9201`/`0x9202`/`0x9203`、AF 驱动 `0x90C1`、设备就绪 `0x90C8`、事件 `0x90C7` 等。
- 个人设定（CSM）属性码位于 `0xD000`–`0xD1FF` 区间（如 `a1=0xD048`、`b1=0xD055`），读取以机身实际返回的量程为准；未适配项灰显，不写入。

---

## 已知问题 / 限制

- 仅针对 **尼康 D300** 真机调试，其它机型未验证，部分私有属性码可能不适用。
- 当前发布包为 debug 签名，无法上架商店。
- 大文件（RAW / 大 JPEG）传输若被中断会导致相机 USB 端点 STALL，需重启相机或重新插拔 OTG 恢复。
- 个人设定菜单中仍有部分属性码未完全适配，以 App 内实际可操作为准。

---

## 许可证

本项目当前 **未声明许可证（unlicensed）**。如需用于分发或修改，请先添加合适的开源许可证。
