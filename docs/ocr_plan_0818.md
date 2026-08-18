# ocr_plan_0818 — 器件整理「标签」整面识别（标签定位 + 全文本 OCR）

日期：2026-08-18 · 现状基线：v1.5.14 / versionCode 20 · 建议目标版本：v1.5.15

> **状态：方案设计（未实施）。** 本文仅给出解决思路与实施方案，不含代码改动。
> **v2 修订（2026-08-18）**：按用户约束重写 OCR 引擎选型（§3.2）——OCR 必须完全本地执行；允许并优先采用前沿端侧 OCR 小模型；算力基准线为开发测试机 **Samsung SM-X800（Galaxy Tab S8+，骁龙 8 Gen 1 / Adreno 730 / Hexagon NPU，Android 14）**，不为低于该算力的设备做妥协设计。
> **v3 修订（2026-08-18）**：用户要求引擎选型进一步前沿化并允许联网调研。§3.2 升级为**双路线架构**：路线 A（PP-OCRv6 级联，可靠主链路）+ 路线 B（GLM-OCR 0.9B 文档 VLM 经 MNN 端侧推理，前沿旗舰链路，可端到端直出结构化字段）。
> **v4 修订（2026-08-19）**：再次联网核实 2026 年年中最新格局（所有技术时间点见 §3.2 逐项备注）。重要变化：**PP-OCRv6 已随 PaddleOCR 3.7.0 正式发布（2026-06-11）**，RapidOCR v3.9.0 已收录其 ONNX 模型且有官方 Android demo；**PaddleOCR-VL-1.6（2026-06-02）**与**阿里 OvisOCR2 0.8B（2026-07-24 开源）**刷新 OmniDocBench v1.6 SOTA（96.33 / 96.58），GLM-OCR 已非榜首但仍是端侧部署链路最成熟的 VLM。路线 B 调整为「GLM-OCR 主用 + OvisOCR2 / PaddleOCR-VL-1.6 热替换候选」。

## 1. 需求重述

器件整理页面当前的识别链路是「摄像头 → ZXing 连续解码 → 读到二维码即结束」。用户要求：

1. **识别整张标签的全部信息**，而不是只读一个二维码——标签上还有料号、型号、数量、规格（如 `12pF ±5% 50V`、`0402`）、批号、流水号（`SO26063011291`）、厂商（`HRE(芯声)`）等纯文本，二维码 payload 未必覆盖全部字段。
2. **先做标签定位**：在画面中先找到标签贴纸的区域（白色圆角矩形贴纸），再对该区域做文本读出，而不是对全画面盲目 OCR。
3. **扫码模式切换控件**（`BarcodeModeSwitch`，当前为「二维码 / 条形码」两档滑块）**最左边新增一档「标签」**，顺序为：标签 · 二维码 · 条形码。
4. 器件整理模式不再是「读到二维码就结束」：在「标签」档下，流程是 定位标签 → 读出全部文本 → 解析为结构化器件记录 → 走现有模板/打印链路。

### 硬性约束（v2 用户确认）

- **C1 全本地**：OCR 推理 100% 在设备端完成，不依赖任何云端服务；维持无 `INTERNET` 权限的离线定位。
- **C2 前沿小模型优先**：不满足于 ML Kit 成品 SDK，允许（且优先）把当前前沿的开源 OCR 小模型（PP-OCRv5/v6 级别）直接部署到端侧，模型文件随 APK 打包。
- **C3 算力基准线 = SM-X800**：以 Galaxy Tab S8+（骁龙 8 Gen 1）为最低目标设备，可以大胆使用其 CPU 大核 / GPU(Vulkan) / NPU(NNAPI)；低端设备兼容性与极限包体压缩本轮不作要求。

## 2. 现状盘点（代码事实）

| 环节 | 现状 | 位置 |
| --- | --- | --- |
| 相机预览 | ZXing `DecoratedBarcodeView`（`zxing_use_texture_view="true"`），仅横屏布局内嵌 | `layout-land/activity_main.xml:71-78`，`MainActivity.java:220` |
| 解码循环 | `decodeContinuous` + `BarcodeCallback`，读到即 `consumeScannedContent` | `MainActivity.java:586-625, 2113-2123` |
| 模式滑块 | `BarcodeModeSwitch` 自定义 View，**硬编码两档**（boolean、两个文字字面量、thumb 宽度=轨道一半） | `view/BarcodeModeSwitch.java:92,102-103,118-124` |
| 格式目录 | `ScanFormatCatalog.formats(boolean barcodeMode)` 二选一 | `scan/ScanFormatCatalog.java` |
| 器件整理解析 | `ComponentLabelParser.parse(raw, supplierFormat)` → `ComponentRecord`（料号/型号/数量/溯源/字段表） | `scan/ComponentLabelParser.java`、`scan/ComponentRecord.java` |
| 下游链路 | `ComponentLabelFactory.create(...)` → `LabelDocument` → `LabelBitmapRenderer` → 点阵打印，**与输入来源无关** | `model/ComponentLabelFactory.java` |
| OCR 能力 | **零**。无 ML Kit / CameraX / OpenCV / Tesseract；无 `INTERNET` 权限（离线原则） | `apps/android/app/build.gradle` |

关键缺口：ZXing 的 `DecoratedBarcodeView` 不暴露帧回调，现有代码拿不到预览帧；模式开关是二值结构；没有从「多行 OCR 文本」到 `ComponentRecord` 的解析器。

## 3. 关键技术决策

### 3.1 预览帧获取：复用 TextureView，不引入 CameraX

`DecoratedBarcodeView` 已配置 `zxing_use_texture_view="true"`。TextureView 支持 `getBitmap()` 直接抓取当前预览帧，零新增相机依赖、零生命周期改动。

- 「标签」档下：`barcodeScanner.pause()` 停掉 ZXing 连续解码（避免二维码抢跑结束），扫描按钮语义变为「拍摄识别」：点按 → `textureView.getBitmap(width, height)` → 走 OCR 流水线。
- 切回「二维码 / 条形码」档：恢复 `decodeContinuous`。
- 取帧分辨率建议限宽 ≤1280px（OCR 精度足够，耗时与内存可控）。

备选方案（不推荐本轮做）：引入 CameraX `ImageAnalysis` 做连续自动识别。依赖多（camera-core/camera2/lifecycle/view 四个库）、与 ZXing 预览共存冲突、离线包体增大。若未来要「对准即自动出结果」再升级。

### 3.2 OCR 引擎：双路线 —— PP-OCRv6 级联（主）+ 端侧文档 VLM（前沿旗舰）（v4 重写）

按约束 C1/C2/C3 并联网调研 2026 年最新模型格局后，选型分两条技术路线。

#### 前沿格局速览（2026-08-19 联网核实，逐项备注发布时间）

- **PP-OCRv6 已正式发布**（百度 PaddleOCR 3.7.0，**2026-06-11**）：Medium 档仅 34.5M 参数，官方口径检测 +4.6%、识别 +5.1%（对 PP-OCRv5_server），号称超越 Qwen3-VL-235B、GPT-5.5 等主流 VLM；统一支持 50 语言。**RapidOCR v3.9.0（2026-06-23）已集成 PP-OCRv6 的 ONNX 模型**（tiny/small/medium 三档），v3.9.1（2026-07 起）补齐 MNN/TensorRT 等引擎；PaddleOCR 官方仓库已提供 **PP-OCRv6 Android demo（基于 ONNX Runtime）**。RapidAI 实测 rec 精度：small CharMatch 0.9515 / medium ExactMatch 0.8677，而 tiny 仅 0.8906——**tiny 识别精度明显掉队，small 应作为基准档**。注意：PP-OCRv6 未发布自有方向分类模型，沿用 v5 的 cls 模型。
- **PaddleOCR-VL-1.6**（百度，**2026-06-02**，Apache-2.0）：0.9B 架构与 1.5 完全一致（平滑替换），OmniDocBench v1.6 **96.33% SOTA**，官方口径超 Gemini-3-Pro、GPT-5.2、MinerU-2.5-Pro、GLM-OCR；同批另有 4B/8B 变体（端侧不适用）。
- **OvisOCR2**（阿里 ATH-MaaS，技术报告 **2026-07-15**、**2026-07-24** 开源，Apache-2.0）：**0.8B**（基于 Qwen3.5-0.8B 后训练，SFT+RL+在线策略蒸馏+模型融合），OmniDocBench v1.6 **96.58 登顶**，是首个端到端超越流水线方法的模型；端到端直出阅读序 Markdown，兼容 vLLM/Ollama，依托 Qwen3.5 推理生态（MNN 对 Qwen 系支持成熟，迁移阻力小于其他 VLM）。
- **GLM-OCR**（智谱，**2026-02-03** 发布，模型权重 **MIT**、代码 Apache-2.0）：0.9B（CogViT + GLM-0.5B），OmniDocBench v1.5 94.62（发布时榜首，现已被 1.6 代模型超越）；支持 Markdown/JSON 结构化输出与 KIE。**端侧部署链路最成熟**：ModelScope 上已有 MNN 官方账号发布的 **GLM-OCR-MNN 4bit 量化模型**，MNN Android AAR 提供 Java API（CPU/Vulkan/OpenCL 后端）。
- **基准版本注意**：OmniDocBench 已迭代至 v1.6（2026-04-10）/ v1.7（2026-04-30，新增 Qianfan-OCR 榜单），引用分数时需注明基准版本，v1.5 与 v1.6 分数不可直接横比。
- **仅观察（服务端绑定或过重）**：百度 Unlimited-OCR（**2026-08**，v1.5 榜 93.23%，面向 GPU 云部署）；NVIDIA Nemotron-Parse-2.0（**2026-08-03** 悄然上架 HF，无官方部署链路）；MinerU 2.5-Pro（1.2B）、dots.ocr（1.7B，2025-07）、DeepSeek-OCR（3B MoE）——官方部署均面向 vLLM/SGLang 服务器；MiniMax M3、Kimi K3 等通用大模型虽居 v1.5 榜前列（~91.x），但体量远超端侧预算。
- **关键基础设施事实**：MNN 官方 release 已支持 GLM-OCR 推理（2025-10 起）并提供 4bit 成品模型；RapidOCR/PaddleOCR 官方均已有 PP-OCRv6 的 Android ONNX 落地样例——两条路线都不需要自写 NDK 推理代码。

#### 路线 A（主链路，可靠交付）：onnxruntime-android + PP-OCRv6 级联

检测（DBNet)→方向分类→识别（CTC) 级联，输出 `OcrLine{text, confidence, rect}`，由 `LabelTextParser` 规则解析为 `ComponentRecord`。优点：包体小（30~40MB）、速度快（亚秒级）、纯 Java、模型生态成熟（v4 核实：官方 ONNX 模型 + 官方 Android demo 均已就位，落地确定性进一步上升）。作为默认链路与路线 B 的对照/兜底。模型档位默认 **small**（tiny 的 rec CharMatch 仅 0.8906，对料号/规格小字风险大，仅在 benchmark 证明耗时不可接受时才降级）。

#### 路线 B（前沿旗舰，端到端 VLM）：MNN + GLM-OCR 0.9B（int4），OvisOCR2 为热替换首选

- **架构**：整张标签图（经 §3.3 定位裁剪后）→ VLM 单次前向 → 直接输出 Markdown 或 **JSON 结构化字段**。用 prompt 引导直出 KIE 结果（如 `{"料号":"C45359894","型号":"CGA0402C0G120J500GT","数量":100,...}`），可**绕过 §3.5 的正则规则引擎**——VLM 对版式泛化（非立创标签、手写、倾斜、遮挡）天然优于规则。规则解析器保留为 VLM 输出校验/兜底。
- **部署**：MNN Android AAR（Java 层直接调），GLM-OCR 转 MNN 格式 + int4 量化（ModelScope 已有 MNN 官方 4bit 成品 `MNN/GLM-OCR-MNN`，可直接离线导入试用，量化损失用 benchmark 验证）。
- **热替换策略**：模型与引擎解耦，文件级替换即可升级。**首选候补 OvisOCR2 0.8B**（2026-07，精度更高、体量更小、Qwen3.5 生态利于 MNN 迁移，但暂无官方 MNN 成品，需自行用 llmexport 转换验证）；其次 PaddleOCR-VL-1.6（0.9B，96.33%，端侧链路目前为社区 hackathon 级）。哪个先在 SM-X800 上跑通且精度达标就用哪个，GLM-OCR 因有官方 MNN 成品而作为落地基线。
- **模型交付**：int4 后权重约 0.5~0.7GB，**不能打进 APK**。方案：模型文件经文件选择器由用户**离线导入**（U 盘/局域网/adb push 到私有目录），App 维持无 `INTERNET` 权限；检测到模型缺失时路线 B 置灰并提示导入。
- **性能预期**：0.8~0.9B int4 在骁龙 8 Gen 1 上，单张标签（输出 <300 token）预计 3~10 秒级（CPU），Vulkan 后端更快。拍摄确认式交互可接受，但明显慢于路线 A——UI 需进度提示。
- **风险**：MNN 对各模型的支持版本差异、int4 后中文小字精度、内存峰值（SM-X800 8GB RAM 充裕）——全部在 benchmark 阶段（步骤 4a/4c）用真机实测定案，不达标则路线 B 降为实验性开关。

#### 候选对比总表（2026-08-19 核实）

| 方案 | 发布时间 | 参数/包体 | 精度（注明基准） | 端侧可行性（SM-X800） | 结论 |
| --- | --- | --- | --- | --- | --- |
| PP-OCRv6 small + onnxruntime-android | 2026-06-11 | det+rec ~10-20MB | 高（rec CharMatch 0.9515，RapidAI 实测） | 高（纯 Java，官方 ONNX + 官方 Android demo） | **路线 A 主用** |
| PP-OCRv6 tiny | 2026-06-11 | ~7MB | 中（rec CharMatch 仅 0.8906） | 高 | 路线 A 降级档（benchmark 后定） |
| **GLM-OCR 0.9B + MNN int4** | 2026-02-03 | ~0.5-0.7GB 外置模型 | 高（v1.5 榜 94.62） | 中高（MNN 官方 4bit 成品模型已在 ModelScope） | **路线 B 落地基线** |
| **OvisOCR2 0.8B** | 2026-07-24 | ~0.4-0.6GB（int4 估） | 极高（v1.6 榜 96.58，端到端首个超流水线） | 中（Apache-2.0 + Qwen3.5 生态，需自行转 MNN 验证） | **路线 B 热替换首选** |
| PaddleOCR-VL-1.6 0.9B | 2026-06-02 | 同上量级 | 极高（v1.6 榜 96.33） | 中（端侧链路 hackathon 级） | 路线 B 热替换候补 |
| dots.ocr 1.7B / DeepSeek-OCR 3B / MinerU 2.5-Pro 1.2B / Unlimited-OCR / Nemotron-Parse-2.0 | 2025-07 ~ 2026-08 | 1-6GB | 高 | 低（服务端框架绑定） | 否决（仅观察） |
| ML Kit 中文 bundled | — | ~20MB | 中 | 高 | 兜底基线/对照组 |
| RapidOCR 现成 AAR / Tesseract / chineseocr_lite | — | 小 | 中低（v4 代际） | 高 | 否决 |

#### 路线 A 实现要点：onnxruntime-android + PP-OCRv6 small（降级 tiny）

- **推理引擎**：`com.microsoft.onnxruntime:onnxruntime-android`（纯 Java API，无 NDK 工程改造；支持 NNAPI 执行提供器，可在 SM-X800 上尝试路由到 Hexagon NPU / GPU，失败自动回退 CPU XNNPACK）。项目是纯 Java 无 NDK，这是接入成本最低的自托管路径。
- **模型**：PP-OCRv6 small（det + rec，必要时降级 tiny）。模型 `.onnx` 放 `assets/ocr/`，首次使用拷贝到应用私有目录后 `OrtSession` 加载。字典文件（`ppocr_keys` 字符表）随模型打包。
- **流水线**（全部端侧，后台线程执行）：
  ```
  Bitmap → 检测模型(DBNet) → 文本行多边形 → 按行裁剪+旋正
        → (可选)方向分类模型 → 识别模型(CTC) → 行文本+置信度+包围盒
  ```
  输出结构与 ML Kit 的 `Text.Block/Line` 对齐为自定义 `OcrLine { text, confidence, rect }`，§3.3/§3.5 的定位与解析层不感知引擎差异。
- **实现参考**：RapidAI/RapidOCR 的 Java 流水线（det 的 DB 后处理 unclip、rec 的 CTC greedy decode、宽高比动态 batch）全部开源，`RapidOcrAndroidOnnx` 工程有完整 onnxruntime-android 调用样例，可裁剪其核心类入库（Apache-2.0，注意保留 LICENSE 与声明）；v4 核实：RapidOCR v3.9.0（2026-06-23）官方模型库已收录 PP-OCRv6 的 ONNX 格式，PaddleOCR 官方文档亦提供 PP-OCRv6 Android（ONNX Runtime）部署示例，模型来源与调用样例均正规。注意 PP-OCRv6 未发布自有方向分类模型，若启用 cls 需沿用 PP-OCRv5 的 cls 模型。
- **性能预期**（SM-X800 基准）：PP-OCRv5 mobile 级别模型官方口径 CPU 370+ 字符/秒；一张标签 ROI（约 10~20 行文本、≤1280px）整管线含检测预计 300ms~1.5s（CPU），NNAPI/Vulkan 加速后更快。拍摄式交互（点按一次识别一次）下完全可接受。实施第一步先做一个 benchmark harness（见 §5）实测 det/rec 各阶段耗时再定 tiny/small。

#### 为什么不继续用 ML Kit 做主引擎

闭源、模型不可换、字符集与后处理不可调，对「料号、±、Ω、µ、封装码」这类元器件关键符号没有优化空间；且其中文 bundled 模型包体（~20MB）并不比 PP-OCRv6 small 大多少。ML Kit 保留为编译期可选的 fallback flavor 或对照组（benchmark 时对比精度），不进默认构建。

#### 包体影响

onnxruntime-android native 库（arm64-v8a 单 ABI 约 10~15MB）+ PP-OCRv6 small 模型（预计 10~20MB）→ APK 从 ~4.2MB 增至 **30~40MB 级**。C3 约束下可接受；建议同时配置 `abiFilters 'arm64-v8a'`（SM-X800 为 arm64，低端 32 位设备不在本轮范围）。

### 3.3 标签定位：轻量纯 Java 轮廓检测 + OCR 行几何兜底，不引入 OpenCV

标签实物是白/浅色圆角矩形贴纸，贴在深色料盘/包装上，对比度高。分三级策略，逐级兜底：

1. **引导框 ROI（必做）**：「标签」档下在预览上叠加取景引导框（约 4:3 横版贴纸比例，参考用户样品标签均为横版圆角矩形）。拍照后先按引导框裁剪，只 OCR 框内区域。这一步把「定位」问题的难度砍掉大半，且符合用户手持对准的实际操作方式。
2. **贴纸轮廓自校正（必做，纯 Java 实现）**：对裁剪后的图降采样（≤320px 宽）→ 灰度 → Otsu 自适应阈值（`protocol/MonochromeRaster.java` 已有全局阈值二值化可参照，Otsu 几十行纯 Java）→ 找最大连通亮区的外接四边形/旋转矩形 → 计算贴纸在主区域的占比与偏移 → 若贴纸区域明显偏离引导框或带小角度旋转，二次裁剪 + 透视/旋正校正后再送 OCR。
   - 纯 Java 轮廓检测（Marching Squares 或简单 flood-fill + 凸包 + 最小外接矩形）约 200~300 行，无依赖。OpenCV-android 包体 >80MB 且引入 native 复杂度，不值得。
3. **OCR 行几何过滤（兜底）**：若轮廓检测失败（占比过低/无可信四边形），直接对引导框 ROI 整图 OCR，然后利用引擎返回的每行包围盒做后处理：剔除明显在贴纸主体行簇之外的孤立行（按行簇质心与密度聚类，保留主簇）。

旋正说明：PP-OCR 检测模型（DBNet）本身输出任意四边形文本框，行级裁剪时已按框旋正，对倾斜天然鲁棒；整图级角度校正用于改善检测召回，不做完整四点透视变换（手持俯拍形变小，收益低）。透视变换列为后续增强项。

### 3.4 模式开关：BarcodeModeSwitch 重写为三档分段控件

现有实现全面硬编码两档，必须重写为通用 N 段控件（改动集中在单文件）：

- 状态由 `boolean barcodeMode` 改为 `int modeIndex`（或新枚举 `ScanInputMode { LABEL, QR, BARCODE }`，定义放 `scan/` 包）；
- `onDraw`：labels 数组 `{「标签」,「二维码」,「条形码」}` 按 `1/6、3/6、5/6` 宽度居中绘制，thumb 宽 = 轨道宽 / 3，thumbPosition 取 0/1/2 归一化；
- `onTouchEvent`：按 x ∈ [0,1/3), [1/3,2/3), [2/3,1] 分派；
- 监听器签名改为 `onModeChanged(ScanInputMode mode)`；`MainActivity.changeScanMode`、`applyDecoderFormats`、`ScanFormatCatalog.formats(boolean)` 调用点同步改造（LABEL 档下 ZXing 解码暂停，格式目录不被使用）；
- 控件宽度 184dp 容纳三个 2~3 字标签偏紧，onMeasure 期望宽度调整为 ~240dp（横屏左栏该行为权重布局，需核对 `layout-land/activity_main.xml:99-105` 与 `scanButton` 的权重分配）。

### 3.5 OCR 文本 → ComponentRecord：新增 LabelTextParser

复用现有 `ComponentRecord` 作为统一出口，**下游（模板面板、`ComponentLabelFactory`、打印）零改动**。新增 `scan/LabelTextParser.java`（纯 JVM，可单测，沿用 `ComponentLabelParserTest` 的测试模式）：

输入：引擎无关的 `OcrLine` 列表（文本 + 置信度 + 包围盒，见 §3.2）。解析规则按立创标签版式（两张样品均为此版式）：

- **内部编码**：首行 `\d{4}-[A-Z]-[A-Z0-9]{2,}-\d{3}`（如 `3417-B-D06-004`、`0250-A-E18-001`）→ 存入 `fields`，可作模板主标题候选；
- **立创料号**：`\bC\d{5,}\b`（如 `C45359894`、`C2843785`）→ `supplierPartNumber`；
- **数量**：`QTY[:：]?\s*(\d+)` → `quantity`；
- **流水/溯源号**：`\bSO\d{10,}\b`、`\d{4}-\d{4}` 行（如 `0630-0057`、`0115-0081`）→ `trackingId` / `fields`；
- **型号**：排除上述模式后最长的字母数字混合行（如 `CGA0402C0G120J500GT`、`XL-5050RGBC-WS2812B`）→ `manufacturerPartNumber`；
- **规格行**：含单位模式（`pF|nF|uF|Ω|KΩ|mA|V|%` 等）与封装码（`\b0[346]0[23]\b`、`SMD\d{4}`）→ `fields`（如 `12pF ±5% 50V`、`0402`）；
- **厂商/品类**：含中文的行 → `fields`（厂商 `HRE(芯声)`、品类 `贴片电容(MLCC)`）；
- **去重与清洗**：同一物理行被二维码遮挡/截断的残行（如样品 2 的 `B` 孤行）按包围盒合并；`±`/`φ`/`Ω` 等符号 OCR 易误识，建常见误识映射表（`O↔0`、`l↔1`、`土→±` 等）做纠正候选；
- **置信度**：沿用 `ComponentRecord.confidence`，命中料号+数量+型号三者给高分，缺项写入 `warning` 提示用户人工核对。

版式适配策略：先按「立创横版贴纸」做规则引擎；`supplierFormat == LCSC` 时启用；AUTO 档先尝试 QR 式 key-value 解析、失败再走 LabelTextParser。规则全部集中在 LabelTextParser，后续支持其他供应商只加一个规则类。

### 3.6 器件整理流程变更

「标签」档下器件整理模式的完整链路：

```
点按「拍摄识别」
  → TextureView.getBitmap() 抓帧（暂停 ZXing 解码）
  → 引导框 ROI 裁剪
  → 轮廓自校正（降采样 + Otsu + 最大亮区 + 旋正，可失败降级）
  → PP-OCRv6 端侧 OCR 流水线（后台线程，识别期间按钮转菊花）
  → LabelTextParser.parse(lines) → ComponentRecord
  → applyComponentScan 等价入口：qrParseSummary / componentRecordSummary 展示全部识别文本与解析字段
  → renderTemplatePanel() → ComponentLabelFactory.create(...) → 画布 → 打印（完全复用）
```

交互要点：

- 识别成功后**展示全部 OCR 原文 + 结构化字段**（`componentRecordSummary` 区域），用户可确认或「重拍」；确认前不写入模板/画布——这改变了「读到就结束」的语义，符合需求 4。
- 普通模式（NORMAL）下「标签」档：识别出的全文直接填入选中 `TextElement`（与现 QR 逻辑 `applyScannedContentToCanvas` 对齐），可选。
- 未命中任何字段时给出「未识别到有效字段，请对准标签重拍」提示，保留原文供查看。

## 4. 实施步骤（建议顺序）

| # | 内容 | 文件 |
| --- | --- | --- |
| 1 | 新增 `ScanInputMode` 枚举；`BarcodeModeSwitch` 重写为 N 段控件（标签/二维码/条形码） | `scan/ScanInputMode.java`（新）、`view/BarcodeModeSwitch.java` |
| 2 | MainActivity 接线：`changeScanMode(ScanInputMode)`；LABEL 档暂停 ZXing 解码、扫描按钮改「拍摄识别」、叠加引导框 | `MainActivity.java`（`configureEmbeddedScanner:586`、`changeScanMode:607`、`applyDecoderFormats:621`、`requestScan:2077`）、`layout-land/activity_main.xml` |
| 3 | 抓帧 + ROI 裁剪 + 轮廓自校正 | `scan/LabelFrameProcessor.java`（新，纯 Java：降采样/Otsu/连通域/旋正） |
| 4a | **benchmark 先行**：最小工程验证 onnxruntime-android + PP-OCRv6（tiny 与 small 两组模型）在 SM-X800 上的耗时与精度，输入用本次两张样品标签照片；定档模型规格、线程数、是否启用 NNAPI | 临时 harness 模块或 `androidTest` |
| 4b | OCR 引擎正式入库：`LabelOcrEngine`（det→cls→rec 流水线，参考 RapidOCR 裁剪移植，保留 Apache-2.0 声明），输出 `OcrLine`；模型与字典入 `assets/ocr/` | `build.gradle`（+onnxruntime-android、abiFilters）、`scan/ocr/`（新包） |
| 5 | OCR 行 → ComponentRecord 规则解析 | `scan/LabelTextParser.java`（新） |
| 6 | 结果确认 UI：原文+字段展示、重拍/确认 | `layout-land/activity_main.xml`（`componentPanel` 内）、`MainActivity.java` |
| 7 | 单测：LabelTextParser（两张样品标签的期望字段做 fixture）、Otsu/轮廓基础用例、ScanInputMode 切换 | `src/test/...` |
| 8 | 版本与文档：v1.5.15 / versionCode 21，README、BUILD_INFO、APK 体积变化说明（含新增 native 库与模型体积） | `build.gradle`、`README.md` |

竖屏布局（`layout/activity_main.xml`）仅有滑块无相机，滑块三档照常可用，「标签」档在竖屏下提示「请横屏使用拍摄识别」即可，不追加竖屏相机视图。

## 5. 验证计划

1. **benchmark harness（步骤 4a 输出）**：SM-X800 上实测 det/rec 分阶段耗时（CPU vs NNAPI）、tiny vs small 精度对比（以样品标签人工转写为 ground truth，算字段级命中率）；记录进 BUILD_INFO。
2. JVM 单测全绿（现有 96+ 项 + 新增 LabelTextParser 用例：以本次两张样品照片人工转写文本为 fixture，断言料号/数量/型号/流水号/规格字段）。
3. 真机（SM-X800）：横屏器件整理 → 切「标签」档 → 对准样品标签拍摄 → 确认识别文本完整、字段解析正确 → 确认后模板面板与画布生成 → 点阵预览核对。
4. 边界用例：标签倾斜 ~15°、标签占画面比例小、部分出框、二维码被手遮挡（OCR 不受影响，验证相对 QR 模式的优势）、无标签空画面（应提示重拍不崩溃）。
5. 离线验证：飞行模式下完整走一遍 OCR 链路（确认模型全部本地加载，无任何网络行为；可配合 `adb shell dumpsys connectivity` 或抓包佐证）。
6. Lint 0 error；APK 签名校验；不新增 `INTERNET` 权限；`abiFilters` 生效后 APK 仅含 arm64-v8a。

## 6. 风险与取舍

- **APK 体积**：onnxruntime-android native 库 + PP-OCRv6 模型使 APK 从 ~4.2MB 增至 30~40MB 级（详见 §3.2 包体影响）。C3 约束下接受；若后续要收窄，路径是：模型换 tiny、onnxruntime 换裁剪版（custom reduced build）或迁 NCNN/MNN。
- **首次加载延迟**：OrtSession 加载 10~20MB 模型约数百毫秒到数秒；策略是 App 启动后「标签」档首次切入时后台预热加载，识别时不阻塞。
- **ONNX 兼容性风险**：PP-OCRv6 官方 ONNX 为动态 shape 图，onnxruntime-android 需验证 det 动态宽高输入是否完整支持。v4 核实后该风险进一步降低：RapidOCR v3.9.0 已收录 PP-OCRv6 官方 ONNX 模型且 PaddleOCR 官方已提供基于 ONNX Runtime 的 PP-OCRv6 Android demo，端侧动态 shape 链路已有官方先例；benchmark 步骤 4a 仍会最先暴露残余问题，若踩坑则降级用 PP-OCRv5 mobile ONNX（生态最成熟）。
- **路线 B 模型迭代风险**：0.8~0.9B 文档 VLM 在 2026 年迭代极快（GLM-OCR 02 月发布，6 个月内即被 PaddleOCR-VL-1.6、OvisOCR2 超越）。路线 B 的「模型外置 + 热替换」设计正是对此的对冲；实施时应以 benchmark 实测为准选型，不要锁定单一模型。
- **手持抖动/模糊**：拍摄式抓帧天然规避连续帧抖动；可在抓帧后做拉普拉斯方差清晰度检查，过糊提示重拍（纯 Java 十余行，列入步骤 3 可选项）。
- **版式泛化**：规则引擎先只保证立创横版贴纸；其他供应商标签靠 `fields` 兜底保留原文，不阻塞主流程。
- **与既有计划的关系**：`docs/plan_0818a.md` 曾把「OCR 图像识别」列为不做项（理由：需三方 SDK 或云端模型）。本方案采用全端侧自托管模型，无云端、无联网，原约束的前提已不成立，但应在下次更新 plan_0818a 或 ARCHITECTURE 时注明该边界已修订。
- **隐私/合规**：模型与推理全部端侧，图片不出设备，符合离线定位；RapidOCR 裁剪代码需保留 Apache-2.0 LICENSE 声明，PP-OCR 模型权重遵循其开源许可（Apache-2.0），在 README/关于页注明。
