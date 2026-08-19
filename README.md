# EPrint Android 打印上位机

EPrint 是面向 Android 设备的离线标签编辑与打印软件。当前主线为原生 Android，当前版本为 v1.5.27：

- 使用设备摄像头离线扫描二维码和常见一维条形码；
- “识别解析”按工作页提供扫描档位：普通页仅显示二维码/条形码，器件整理页显示并默认选择标签；各页选择独立持久化；
- 右侧“模型配置”覆盖标签、二维码、条形码的识别开关、制式、连续/反色/增强定位、GLM-OCR 后端与推理参数，并提供“重启应用”；
- 器件整理提供简洁、标准、完整三档技术标签：全部排除数量与追溯信息，简洁档不含立创料号，标准及完整档保留立创料号；
- GLM-OCR 使用字段语义消歧的严格 JSON 提示词，并以同图 PP-OCR 逐行文本做视觉核对与字段落地校验，避免内部编码冒充型号、规格混入 ROHS、型号拆入参数；三档模板按需取字段；码区遮盖因实拍效果不稳定改为默认关闭的实验功能；
- 三档器件模板统一采用固定前缀列和值列，字段缺失时仍保留前缀，保证不同标签视觉对齐；
- 器件模板允许按型号、品类、规格、封装、品牌、参数和立创料号逐类启停；识别底栏只显示最终完整结果，过程日志统一显示在右侧；
- 平板启动时优先进入横屏，主界面固定为左 1/3 扫码解析、右 2/3 打印机与标签画布；
- 左侧相机在识别过程中冻结并展示抓帧、标签清理与模型输入等阶段画面，全部流程结束后恢复实时预览；
- 在指定毫米尺寸和打印 DPI 的画布上添加、编辑、拖动文本；
- 通过经典蓝牙 SPP、BLE GATT 或 USB Host Bulk 发送打印任务；
- 支持 ESC/POS、TSPL/TSPL2、CPCL、ZPL 四类公开打印语言；
- 使用可离线保存的设备档案约束 DPI、有效点宽、传输分包、节流和 BLE UUID；
- 自动合并 USB、已配对 SPP 与附近 BLE，通过历史设备、USB Printer Class、名称族、蓝牙类别和服务 UUID 找出并排序候选；发现后必须由用户点击目标设备才连接；
- 使用 `connectedDevice` 前台服务保持用户选定的设备，支持 1/2/4/8/16 秒退避并持续重连，直到用户手动断开；
- 分开显示设备身份和协议档案，可识别德佟 P1 系列的制造商、203 DPI、384 点/48 mm，并按物理端点保存浓度、速度、纸张、间隙、自动出纸、提示音和关机时间；
- 已在 `P1-0009200000` 上验证德佟私有协议的 BLE 基线、流控、打印前状态门控、完成回执轮询和连续 25 × 15 mm 标签打印；RLE、离线授权、电机/语言、任务变换及厂商信息解析属于默认关闭或尚待真机复验的扩展，不能据此外推其他机型；
- 通过 SPP 输入流、BLE Notify/Indicate 或 USB Bulk IN 回读 ESC/POS 与 ZPL 硬件状态。

应用清单不申请网络权限。二维码/条形码识别、文档保存、点阵渲染和打印编码均在设备本机完成。

## 目录

```text
otool_eprint_tool/
├─ apps/
│  └─ android/              # 当前唯一主线：原生 Android 应用
├─ docs/                    # 当前架构、设备兼容和开发说明
├─ artifacts/               # 本机构建与真机验证产物（不入 Git）
├─ backup/                  # 本地归档（整体不入 Git）
│  ├─ web-capacitor/        # 已搁置的网页/Capacitor 方案（只保留参考）
│  └─ research/             # 逆向分析结果和本地 APK 样本（不入 Git）
└─ .tools/                  # 本机构建工具链（不入 Git）
```

## 本地构建

要求：JDK 17+、Android SDK 36、Gradle 8.14.3。仓库根目录执行：

```powershell
$env:JAVA_HOME = (Resolve-Path '.tools\jdk-21\PFiles64\Microsoft\jdk-21.0.12.8-hotspot').Path
$env:ANDROID_HOME = (Resolve-Path '.tools\android-sdk').Path
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
& '.tools\gradle\gradle-8.14.3\bin\gradle.bat' -p apps\android ':app:assembleDebug'
```

生成文件位于 `apps/android/app/build/outputs/apk/debug/app-debug.apk`。可交付副本放在 `artifacts/`。

## v1.5 支持边界

经典蓝牙设备需先在 Android 系统设置完成配对；BLE 设备可在应用内扫描；USB 设备由 Android 系统逐设备授权。自动识别覆盖常见 POS58/POS80、TSC TSPL、Zebra CPCL/ZPL 名称族，未知设备必须手动确认档案。

主界面以横屏平板工作站为第一目标，Android 7–15 通过 `sensorLandscape` 自动使用当前横屏方向；API 36 同时声明大屏兼容模式。Android 17 起系统可能忽略方向限制，因此仍保留可运行的竖屏回退布局，但 v1.5 的首要验收基准是横屏 1:2 布局。

德佟 P1 私有协议目前只对现场设备 `P1-0009200000` 的 203 DPI、384 点规格完成认证；P1 实打保护仍只允许现场已有的 25 × 15 mm 标签。其他固件、型号和纸张尺寸不能由该结果直接推定兼容。完整实测记录见 `docs/VALIDATION_V1.4.md`，通用设备边界见 `docs/PRINTER_COMPATIBILITY.md`。

兼容性统一按“编码已实现 / 通道已实现 / 真机已验证 / 实验性 / 未实现”分级。当前公开协议有编码与通用传输实现，但仓库内没有对应具体型号的实打认证；完整矩阵以 `docs/PRINTER_COMPATIBILITY.md` 为准。

网页方案已归档，不再参与主工程构建；后续开发请只在 `apps/android` 中进行。
