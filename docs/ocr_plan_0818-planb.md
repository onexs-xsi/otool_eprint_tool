# ocr_plan_0818-planb — 路线 B 定案：端侧文档 VLM 主链路（手写便签 + 文本→预设位置自动匹配）

> **2026-08-19 更新**：VLM 用法已从"单步图→严格 JSON 信息提取"改为"**转写（Text Recognition:）→ 规则解析 → 融合**"两段式，采样/分辨率工作点重定（image_size 616、penalty 1.3/0.3/0.2）。实测依据与迁移说明见 `docs/ocr_optimize_0819.md`；本文 §3 以后的 JSON 提取设计仅作遗留参考。

日期：2026-08-19 · 当前交付：v1.5.17 / versionCode 23 · 后续目标：v1.6.0

> **状态：实施中（2026-08-19 启动，见 §0 进度日志）。** 本文是 `docs/ocr_plan_0818.md` 的**路线 B 深化定案**，取代其 §3.2 的双路线并立结构：**VLM 端到端链路升格为唯一主链路**，PP-OCRv6 级联降级为常驻几何/校验通道。
> **用户决策（2026-08-19）**：
> 1. 选定路线 B（端侧文档 VLM）为主路线；
> 2. 后期需支持**手写便签识别**；
> 3. 后期需支持**识别文本自动匹配到模板预设位置**，要求尽可能提高冗余度与智能程度；
> 4. **模型允许打包进 APK，APK 体积不作要求**（取代 ocr_plan_0818.md 的「模型外置导入」方案）。
> 5. 当前开发阶段不启用 OCR 通道自动回退；PP-OCRv6 与 GLM-OCR 结果独立展示，失败需明确暴露。
>
> 技术格局核实时间：2026-08-19（联网核实，逐项备注发布时间）。未变更的通用部分（取帧、标签定位、模式开关、打印链路）仍以 `ocr_plan_0818.md` 为准，本文不重复。

## 0. 实施进度日志

> 每次推进后更新本节；完成标准以 §5/§6 的验证计划为准。

| 日期 | 事项 | 状态 |
| --- | --- | --- |
| 2026-08-19 | 构建环境打通：`.tools/` 自带 JDK 21 + Android SDK + gradle 8.14.3，`gradlew` 验证可用；SM-X800（Android 14）经 adb 网络连接在线，已装现版 App | ✅ |
| 2026-08-19 | 模型下载并校验：PP-OCRv6 det/rec small+tiny、PP-OCRv5 cls、字典（ONNX，RapidOCR 官方 v3.9.2 模型库）；GLM-OCR-MNN 官方 4bit 全套（llm/visual/embeddings/tokenizer，~1GB）；MNN 3.6.1 Android 预编译包（含 `libllm.so`）。SHA-256 与官方清单逐一核对一致。暂存 `.tools/downloads/ocr-models/`、`.tools/downloads/mnn_android.zip`，未入库 | ✅ |
| 2026-08-19 | NDK 工具链就绪：`cmdline-tools` 为截断坏包无 sdkmanager，改从 dl.google.com 直链下载 **NDK r27d（27.3.13750724，匹配 AGP 8.13 默认）+ CMake 3.22.1**，已解压至 `.tools/android-sdk/ndk/` 与 `cmake/` 标准布局，AGP 可自动识别 | ✅ |
| 2026-08-19 | 阶段 2 物料备齐：MNN 3.6.1 编译头文件（`.tools/downloads/mnn-3.6.1-include/`，含 `llm.hpp` 与 nlohmann/json.hpp）、官方 JNI 层裁剪源码与 `LlmSession.kt`（`.tools/downloads/mnn-llm-src/`，已统一为 3.6.1 标签）；GLM-OCR prompt 约定核实（全文转写触发词 `Text Recognition:`，图片以 `<img>路径</img>` 嵌入 prompt） | ✅ |
| 2026-08-19 | 阶段 1（无模型依赖基础链路）完成：ScanInputMode 三档开关、MainActivity 拍摄识别接线、LabelGuideOverlay 引导框、LabelFrameProcessor（Otsu/轮廓自校正/糊片检测）、LabelTextParser + OcrLine。**构建通过、117 项单测全绿（独立复核 test-results XML）**；APK 装 SM-X800 实测：三档切换正常、引导框显示正常、拍摄识别抓帧成功（贴纸占比 72% 检出）。截图存 `.tools/verification/ocr-smoke*.png` | ✅ |
| 2026-08-19 | 阶段 1 决策备忘：SO 流水号映射到 `orderNumber`（与二维码 payload 的 `on` 字段对齐，保证双输入源 ComponentRecord 一致），`\d{4}-\d{4}` 批次号映射 `trackingId`——与 ocr_plan_0818.md §3.5 字面表述有出入，经确认按此执行 | ✅ |
| 2026-08-19 | 阶段 1 收口：assembleDebug + 全量单测全绿，真机装验三档切换与抓帧 | ✅ |
| 2026-08-19 | **基准图 + 桌面先行验证**：从真机截图裁剪出基准标签图（`.tools/verification/label-crop.png`，1461x1176，全字段清晰可读）；`.tools/pyvenv` 装 rapidocr + onnxruntime 用本地 PP-OCRv6 small 实测：**10/13 行全对**（内部编码/料号/型号/数量/流水/批次/厂商全部命中，置信度 0.98~1.00），桌面 CPU 单张 1.55s；唯一真漏行是「贴片电容（MLCC)」（截图红色扫描线横穿所致，实机拍摄无此干扰）。注意 cls 模型必须按 PPOCRV5 配置（输入 80x160，默认 v4 的 48x192 会维度报错） | ✅ |
| 2026-08-19 | GLM-OCR 模型已部署到真机：`/sdcard/Android/data/com.otool.eprint/files/models/glm-ocr-mnn/`（13 文件 1011MB，30MB/s，大小逐一核对一致）。坑：Git Bash 的 MSYS 路径转换会把 adb 远端路径 `/sdcard/...` 改写成本地路径导致假死，须 `MSYS2_ARG_CONV_EXCL="*"` | ✅ |
| 2026-08-19 | **tiny vs small 对比实测**（桌面，基准图）：tiny 1.34s vs small 1.55s，但 tiny 在关键字段上犯致命误识——型号 `CGA0402C0G120J500GT`→`...COG...`（0→O）、流水号 `SO26063011291`→`S026063011291`（O→0）。**结论坐实：small 为默认档，tiny 仅在耗时不可接受时降级**；同时验证了 LabelTextParser 误识映射表（O↔0）对 tiny 档的必要性 | ✅ |
| 2026-08-19 | **鲁棒性实测**（桌面，small 档）：基准图的 15° 旋转 / 高斯模糊(r=2.5) / 半分辨率三种退化变体均 **7/7 字段全中**（`.tools/verification/label-{rot15,blur,half}.png`）。§6 边界用例的桌面侧证据已就位，真机侧待 2c 后复测 | ✅ |
| 2026-08-19 | R5 手写基准物料：GLM-OCR 官方手写样本（`examples/source/handwritten.png`）+ 官方转写结果已取回（`.tools/verification/glm-handwritten-sample.png` / `glm-handwritten-expected.md`），样本已 push 到设备 files 目录；注意官方转写本身即有个别错字（如「入间」应为「人间」），手写 CER 基线需以此为参照而非满分预期 | ✅ |
| 2026-08-19 | 阶段 2b 中间检查：子代理的 `glm_ocr_jni.cpp`（薄 JNI：load/submit 流式/reset/release/maxNewTokens/updateConfig + prefill/generate 步进 workaround + LlmContext 性能计数器回传）经 NDK clang++ `-fsyntax-only --target=aarch64-linux-android24` 独立验证**零告警通过**；Java 封装（GlmOcrSession/VlmOcrEngine/VlmResult）审查通过（native 可用性守卫、模型目录必需文件校验、MAX_NEW_TOKENS 防退化死循环） | ✅ |
| 2026-08-19 | 阶段 2b 完成：MNN NDK 薄 JNI 改用标准 `Llm::response` 生成路径；SM-X800 复现电子料标签单 token `333…` 退化，独立原生 harness 证明同模型可正常识别官方手写样本、故障随图片内容触发。最终以确定性 penalty 采样（重复/出现/频率惩罚 1.6/0.8/0.4）修复：同一实拍标签由跑满 256/1024 token 降为 113 token 正常结束，识别出 `3417-B-D06-004`、`QTY:100个`、`C45359894` 等主体，应用内总耗时约 8.6s；另加连续 48 字符退化中止并明确报错，**不自动回退**。标题改“识别解析”，新增“模型配置”并验证进程重启后持久化 | ✅ |
| 2026-08-19 | 阶段 2a 完成：onnxruntime-android 1.22.0 + PP-OCRv6 det→cls→rec 级联入库（`scan/ocr/` 纯 Java；DB 后处理凸包+旋转卡壳+unclip、CTC greedy 纯 JVM 可测），det_small/rec_small/cls/字典入 `assets/ocr/`（noCompress，abiFilters arm64-v8a，APK ~55MB）；benchmark 埋点随 OcrResult 返回。MainActivity 接线：切入标签档后台预热、识别期按钮转加载态、结果（原文+解析字段+分阶段耗时）展示在扫码原文/本机解析区，不写画布（2c 再确认）。SM-X800 实测 det 316~482ms / cls 70~76ms / rec ~1.8s（16~17 行）/ 共 ~2.4s；立创样品字段全中（料号 C45359894、型号 CGA0402C0G120J500GT、数量 100、订单 SO26063011291、批次 0630-0057，置信度 100%）。130 项单测全绿（新增 CtcDecoder/DbPostProcessor 13 项）。坑：cls 静态输入 80×160（与上行桌面验证结论一致，初版按 48×192 写导致整批行被丢弃；cls 失败已改为降级不丢行）。截图存 `.tools/verification/ocr-stage2a-*.png` | ✅ |
| 2026-08-19 | v1.5.16 器件模板重构：按 MLCC 与 RGB LED 实物样品补齐英文品类、SMD 封装和厂商解析，并将标签 OCR 记录接入模板/画布；三档标签均移除数量、订单、批次、追踪号及内部仓储码，简洁档仅型号/品类/规格/封装且不含立创料号，标准和完整档保留立创料号；“显示追溯行”改为可持久化的“显示品牌 / 厂商” | ✅ |
| 2026-08-19 | v1.5.17 GLM-OCR 改用官方“信息提取”思路：固定完整七键 JSON schema（型号/立创料号/品类/规格/封装/品牌/扩展参数），旧全文转写提示词自动迁移，自定义提示词不能移除 schema；同一次 VLM 输出形成统一 ComponentRecord，模板在全部模型完成后生成且冲突字段默认由 GLM 优先。模型配置覆盖标签/二维码/条形码并持久化，支持直接重启应用；普通页仅保留二维码/条形码，器件整理页默认标签。识别底栏只显示分类后的最终完整结果，过程日志统一迁至右侧。三档模板采用固定前缀并允许逐类启停字段。码区遮盖因实拍误伤风险改为默认关闭的实验功能；标签外区域按纸张连通区清理 | ✅ |
| — | 阶段 2c：LabelSceneFusion + SlotMatcher + 确认 UI | ⏳ |
| 2026-08-19 | **桌面先行暴露风险及真机闭环**：pymnn 3.6.1 (Windows x86) 跑 GLM-OCR-MNN，纯文本正常但视觉路径输出「3333…」；ARM 真机在特定电子料标签上同样复现。排除 Java/JNI、图片编码、后端与精度后，确认原模型 greedy penalty=1.1 会陷入采样循环；v1.5.15 用确定性抗重复采样修复并加退化中止。开发阶段不做 PP-OCR 自动替代，两个通道独立展示 | ✅ |

## 1. 需求重述与约束变更

在 ocr_plan_0818.md §1 的 4 项需求基础上新增：

- **R5 手写便签识别**：拍摄手写便签（中文手写为主，可能混数字/型号），输出全文文本，供填入画布文本元素或建立器件记录。手写识别是级联式 OCR（PP-OCRv6 det+rec）的传统弱项，是选 VLM 主链路的核心动因之一。
- **R6 文本→预设位置自动匹配**：识别结果不只是「一段文本」，而要自动落到**预设位置**——器件整理场景是 `ComponentRecord` 的字段位（料号/型号/数量/规格/批号…），普通标签场景是模板上的 `TextElement` 槽位。要求：几何（文本在画面中的位置）与语义（文本是什么字段）双通道匹配，任何单一通道失效都有兜底，匹配不确定时交用户确认而非静默写错。

约束变更（其余 C1~C3 不变：全本地、前沿小模型优先、SM-X800 算力基准线）：

- **C4'（取代原外置模型方案）**：模型文件**直接打包进 APK**，APK 体积不作要求。由此量化档位从「尽量小」改为「精度优先」——默认 **int8**（约 0.9~1.2GB），不再以 int4（约 0.5~0.7GB）为默认；int4 仅作为后续低配备选。
- **C5 冗余度优先**：允许（且鼓励）多模型常驻、多通道交叉校验，不为此节省包体或内存（SM-X800 8GB RAM 为基准）。

## 2. 引擎选型定案（2026-08-19 联网核实）

### 2.1 针对新需求的候选对比

| 维度 | GLM-OCR 0.9B（智谱，2026-02-03，模型 MIT） | PaddleOCR-VL-1.6 0.9B（百度，2026-06-02，Apache-2.0） | OvisOCR2 0.8B（阿里，2026-07-24，Apache-2.0） |
| --- | --- | --- | --- |
| 印刷精度 | v1.5 榜 94.62 | v1.6 榜 **96.33** | v1.6 榜 **96.58** |
| **手写能力（R5）** | 官方文档明确支持手写体（2026-08 核实） | 官方宣传支持手写汉字 | 未见官方手写承诺；PureDocBench「真实再捕获」退化档 66.56，低于部分通用 VLM，实拍/手写场景存疑 |
| **坐标输出（R6）** | 两阶段管线（PP-DocLayout-V3 版面分析 + 并行识别），输出区域级 bbox 的 JSON 版面详情 | **原生 Text Spotting**：4 点四边形坐标嵌入，行级坐标直接随文本输出（v1.5 起，1.6 继承） | Markdown 为主，图形区域有标注，行级坐标能力未证实 |
| 结构化/KIE 直出 | 支持 Markdown/JSON + KIE prompt | 支持 JSON 结构化输出 | Markdown 为主 |
| **MNN 端侧链路** | **最成熟**：ModelScope 有 MNN 官方 4bit 成品（`MNN/GLM-OCR-MNN`），MNN release 原生支持 | 社区 hackathon 级（llama.cpp/MNN int4），需自行 llmexport 转换验证 | 无官方成品；Qwen3.5 生态利于转换，需自行验证 |

### 2.2 定案：**GLM-OCR（int8，打包 APK）为主模型 + PP-OCRv6 det 常驻几何通道**

- **主模型 GLM-OCR**：三个候选中唯一同时满足「官方明确手写支持 + MNN 官方端侧链路 + KIE 直出 JSON」的模型。精度分数（94.62）低于另两款约 2 分，但对本场景（标签/便签短文本）远非瓶颈；手写便签（R5）的确定性比榜单 2 分更重要。
- **PP-OCRv6 det-only 常驻（~5-10MB）**：只跑检测不跑识别，输出行级四边形框。作用有二：① 给 VLM 的文本/KIE 结果**配上精确行级坐标**（GLM-OCR 自身只有区域级 bbox，不足以做 R6 的槽位匹配）；② 作为独立第二通道与 VLM 结果交叉校验（冗余度，见 §4）。包体可忽略，无理由不带。
- **PaddleOCR-VL-1.6 为热替换升级候补**：一旦其 MNN 转换链路在本项目 benchmark 中跑通，它凭原生 spotting（文本+行级四点坐标一次出）可**取代「GLM-OCR + det 通道」的组合**成为单模型方案——届时模型文件级替换即可，架构不变。OvisOCR2 因实拍退化场景存疑且坐标能力未证实，仅观察。
- **量化档位**：默认 int8（C4' 精度优先）。手写小字对量化敏感，benchmark 需对比 int8/int4 的手写 CER 后再定是否发布 int4 备选包。

### 2.2.1 MNN 接入方式修正（2026-08-19 实施前核实）

原「MNN Android AAR，Java 层直接调」的假设**部分不成立**，实测核实结果：

- MNN 官方 release（v3.6.1，2026-07-23）的 Android 包**只有 .so**（arm64-v8a 的 `libMNN.so` 2.4MB、`libllm.so` 1.3MB、`libMNN_Vulkan.so`、`libMNN_CL.so`、`libc++_shared.so` 等），**没有 Java AAR**；Maven Central / 阿里云 maven 均无 MNN 或 MNN-LLM 的 Java 制品。
- **MNN-LLM 没有官方 Java 绑定**：官方安卓 app（MnnLlmChat）是自带 JNI 层（`llm_mnn_jni.cpp` / `llm_session.cpp` + Kotlin session 类）直接链接预编译 .so。
- **结论**：项目需引入**最小 NDK 工程**——CMake 编译一个薄 JNI wrapper（参照 MnnLlmChat 的 JNI 层裁剪，Apache-2.0），链接 release 预编译的 arm64-v8a .so。这是路线 B 的唯一 NDK 改造点；PP-OCRv6 det 通道仍走 onnxruntime-android（Maven Central `com.microsoft.onnxruntime:onnxruntime-android`，2026 年最新 1.22.0；另有 `onnxruntime-android-qnn` 变体可试路由 Hexagon NPU），保持纯 Java。
- 开发机已就绪：模型文件（GLM-OCR-MNN 4bit 全套 + PP-OCRv6 ONNX det/rec small/tiny + cls + 字典，SHA-256 与官方清单核对一致）在 `.tools/downloads/ocr-models/`；MNN 3.6.1 Android 包在 `.tools/downloads/mnn_android.zip`；官方 JNI 层裁剪源码（`llm_mnn_jni.cpp`/`llm_session.cpp` 等）与 `LlmSession.kt` 在 `.tools/downloads/mnn-llm-src/`。
- **prompt 约定（2026-08-19 从官方 SDK 源码核实）**：全文转写用官方默认触发词 `Text Recognition:`（SDK 在 prompt 为空时的默认值）；KIE 直出用自定义 prompt 指定 JSON schema。图片以 `<img>本地路径</img>` 标记嵌入 prompt 字符串，由原生层解析加载。模型 `image_size=420`、mrope 位置编码、LLM 4bit(block64)+visual 8bit 量化（`export_args.json` 实测配置）。官方仓库含手写体示例（`examples/result/handwritten/`），R5 可行性有官方背书。

### 2.3 打包交付（C4'）

- 模型（`.mnn` int8，约 0.9~1.2GB）放入 `src/main/assets/ocr/`，并在 gradle 中对 `.mnn` 配置 `noCompress`（已压缩格式再压缩只会拖慢安装）；首次启动校验完整性（大小 + SHA-256）后拷贝/映射到私有目录加载。
- APK 预计 1GB 量级：不走 Google Play（本项目的离线分发定位本就侧载），需确认内部分发渠道（局域网/U 盘/adb）对超大 APK 的安装体验；Android 对单 APK 有 4GB（`extractNativeLibs` 等）限制，1GB 级无技术障碍。
- 模型可热替换设计保留：私有目录存在更新版模型文件时优先加载，便于不重装 APK 升级模型（如未来换 PaddleOCR-VL-1.6）。

## 3. 总体架构：双通道融合流水线

```
点按「拍摄识别」（建议连拍 2~3 帧，见 §4.3）
  → TextureView.getBitmap() 抓帧
  → 引导框 ROI 裁剪 → 轮廓自校正（沿用 ocr_plan_0818.md §3.3，纯 Java）
  → 并行两通道（后台线程池）：
      通道 1【语义】GLM-OCR int8 (MNN, Vulkan 优先 CPU 兜底)
              → Markdown/JSON 全文 + KIE 字段 + 区域 bbox
      通道 2【几何】PP-OCRv6 det-only (onnxruntime-android)
              → 行级四边形框集合（无文本）
  → LabelSceneFusion（新增，见 §4.1）
      文本行 ↔ 几何框对齐 → OcrLineEx{text, field?, quad, 双通道置信度}
  → SlotMatcher（新增，见 §4.2）
      几何通道（quad → 模板槽位）+ 语义通道（KIE 字段名 → 槽位）加权评分
  → 确认 UI（全部原文 + 字段 + 槽位落位预览，可改可重拍）
  → ComponentRecord / TextElement → 现有模板与打印链路（零改动）
```

「便签」场景复用同一条流水线：VLM 无版式假设，手写便签直接整图送通道 1；通道 2 的 det 对手写行检测召回可能下降，此时融合层退化为「VLM 全文 + 阅读顺序」，SlotMatcher 退化为纯语义/顺序匹配（见 §4.2 降级策略）。模式开关上，「便签」不单设档位——并入「标签」档，由 VLM 输出自动判别版式（印刷标签/手写便签/其他），必要时 UI 上加一个「印刷/手写」提示切换（实测后再定）。

## 4. 冗余度与智能程度设计（R6 核心）

### 4.1 LabelSceneFusion：文本↔几何对齐（新增 `scan/fuse/`）

- 对齐：通道 1 的行文本按阅读顺序与通道 2 的行框做**顺序约束 + IoU 双重匹配**（VLM 输出行数与 det 框数通常不等：VLM 可能合并/省略行，det 可能碎行）。匹配算法用带权二分或简单贪心（按 y 排序后动态规划对齐，~100 行纯 Java，可单测）。
- 冲突处理：同一行两通道文本不一致时（手写场景 det 无文本，仅印刷场景若后续给通道 2 加 rec 才有冲突），以 VLM 为准、差异记入 `warning`；VLM 漏行而 det 有框时，该框区域单独裁剪二次送 VLM 补识别（裁剪小图推理快，手写小字场景收益大）。
- 输出 `OcrLineEx { text, fieldHint, quad, vlmConf, detConf, fusedConf }`，全链路下游只认这个结构。

### 4.2 SlotMatcher：文本→预设位置双通道匹配（新增 `scan/slot/`）

预设位置抽象为 `Slot { id, name, normRect(模板归一化矩形), accept(字段类型/正则/词表) }`，器件整理场景由 `ComponentRecord` 字段生成，普通模板场景由 `TextElement` 布局生成。每条 `OcrLineEx` 对每个 Slot 算双向得分：

- **几何分** G：标签实拍图经引导框+自校正后与模板坐标系近似对齐，`quad` 中心点到 `normRect` 的归一化距离/重叠度得分。手持偏差由自校正与连拍优选吸收，残余偏差允许 10~15% 容差。
- **语义分** S：VLM 的 KIE 字段名/fieldHint 与 `Slot.accept` 的匹配度（字段名相同=1.0；正则/词表命中=0.7；VLM 未给字段时用语义相似或规则推断=0~0.5）。
- **总分** = w1·G + w2·S（初值 w1=0.4, w2=0.6，语义优先；benchmark 调参）。**冲突仲裁**：几何与语义指向不同槽位时，若总分差 <阈值 → 该字段标黄交用户点选；≥阈值 → 取高分方并记 `warning`。
- **降级链**：det 失效（手写召回低）→ 纯语义匹配；KIE 失效（VLM 只出 Markdown）→ 规则引擎（沿用 ocr_plan_0818.md §3.5 的 LabelTextParser）补语义分；两者都失效 → 全文展示 + 完全手动拖放。**任何一级降级都不阻塞流程，只是置信度与自动化程度递减**——这是冗余度设计的核心承诺。

### 4.3 多帧冗余（可选增强，成本低收益高）

拍摄按钮长按或设置项开启「连拍 3 帧」：3 帧各自走完整流水线，字段级多数投票（2/3 一致即采纳），不一致字段标黄。拍摄式交互下仅增加 2 次推理耗时（可并行），对「±、Ω、0/O」这类关键误识的抑制效果明显。VLM 侧还可对同一图做 2 次不同 prompt（自由转写 vs KIE 直出）的 self-consistency 校验，输出 token 少时成本可忽略。

### 4.4 智能程度边界（诚实说明）

- 0.9B 级 VLM 的 KIE 对训练分布内的版式（各类印刷标签）可靠，对**手写便签的字段抽取**（如从「剩 120 个 0603 10k」抽出数量/封装/阻值）属于小模型能力边缘，预期可用但需 benchmark 实测；不达标时的兜底是「VLM 全文 + 规则引擎抽取」，不阻塞 R6 主流程。
- 不引入通用小 LLM 做后处理推理：当前需求用 VLM KIE + 规则已覆盖，再叠模型收益递减，后续确有需要（如自然语言指令改模板）再评估。

## 5. 实施步骤（在 ocr_plan_0818.md §4 基础上的增量/变更）

| # | 内容 | 文件 |
| --- | --- | --- |
| 0 | 原计划的步骤 1~3（三档开关、抓帧、ROI/自校正）不变，照做 | 见 ocr_plan_0818.md §4 |
| 4a' | **benchmark 先行（范围扩大）**：SM-X800 实测 ① GLM-OCR int8 vs int4：印刷标签字段命中率 + **手写便签 CER**（自备 10~20 张手写便签样本做 ground truth）+ 单图耗时（CPU/Vulkan）；② PP-OCRv6 det-only 耗时与手写行召回；③（探索）PaddleOCR-VL-1.6 llmexport 转 MNN 能否跑通 | 临时 harness 模块或 `androidTest` |
| 4b' | MNN 引擎入库：MNN Android AAR 依赖、`scan/vlm/` 封装（加载/Vulkan 回退/prompt 模板/JSON 解析）；GLM-OCR int8 模型入 `assets/ocr/`（`noCompress`） | `build.gradle`、`scan/vlm/`（新包） |
| 4c' | PP-OCRv6 det-only 通道入库（onnxruntime-android，模型入 assets） | `scan/ocr/`（新包） |
| 5' | LabelSceneFusion 对齐与补识别逻辑 | `scan/fuse/`（新包） |
| 6' | SlotMatcher 双通道匹配 + Slot 抽象（ComponentRecord 字段 / TextElement 两种来源） | `scan/slot/`（新包） |
| 7' | 结果确认 UI：原文 + 字段 + **槽位落位预览**（模板缩略图上标注各字段落到哪个位置）、冲突字段点选、重拍 | `layout-land/activity_main.xml`、`MainActivity.java` |
| 8' | 单测：融合对齐（构造行数不一致用例）、SlotMatcher 评分与仲裁、KIE JSON 解析容错 | `src/test/...` |
| 9' | 版本与文档：v1.5.15 / versionCode 21 起；README/BUILD_INFO 注明 APK 体积剧增（~1GB 级）与模型许可（GLM-OCR 权重 MIT、PP-OCRv6 Apache-2.0、MNN Apache-2.0） | `build.gradle`、`README.md` |

## 6. 验证计划（相对 ocr_plan_0818.md §5 的增量）

1. **手写便签专项**：≥20 张真实手写便签（不同书写人/光照/倾斜），指标：全文 CER、字段抽取命中率（数量/型号类字段）、单图耗时；int8 vs int4 对比后定量档。
2. **位置匹配专项**：≥10 种版式（立创标签 + 自制便签 + 他厂标签），指标：槽位正确落位率（无人工干预）、冲突仲裁准确率、降级链各档触发正确性。
3. **冗余失效注入**：人为屏蔽通道 2（模拟 det 失效）、屏蔽 KIE（模拟只出 Markdown），验证 §4.2 降级链行为符合承诺。
4. 多帧投票（若启用）：同一标签 3 帧，验证误识字段被投票纠正的比例。
5. 安装体验：1GB 级 APK 经 adb / 局域网 / U 盘三种途径安装到 SM-X800 的耗时与成功率；冷启动模型加载耗时（目标 <5s，后台预热）。
6. 沿用原计划：离线飞行模式全链路、Lint 0 error、签名、无 `INTERNET` 权限。

## 7. 风险与取舍

- **APK 体积 ~1GB 级**：C4' 已授权。代价是分发与安装体验（§6-5 验证）、升级包同样巨大——配合 §2.3 的模型热替换设计，日常版本升级可发「不含模型的瘦 APK + 模型沿用」的变体包（gradle productFlavor 一行事）。
- **GLM-OCR int8 在 SM-X800 的耗时**：0.9B int8 较 int4 内存带宽压力翻倍，单图（输出 <300 token）可能落在 5~15s（CPU）——benchmark 步骤 4a' 若超标，手段依次为：Vulkan 后端、缩短输出（KIE 模式限 token）、int4 折中、或改推 PaddleOCR-VL-1.6（若其链路跑通）。
- **手写识别不确定性**：三家 0.9B 级模型均无公开的手写中文基准分数，R5 达标与否只能实测；本方案的缓冲是「全文转写优先、字段抽取尽力」，即使字段抽取失败，全文填入文本元素的价值已经成立。
- **坐标对齐误差**：引导框+自校正把标签坐标系与模板坐标系对齐是几何分的前提，俯拍大角度形变会破坏该前提——§4.2 的语义优先权重与冲突仲裁即为兜底；透视校正（四点变换）列为后续增强项（届时 det 四顶点可直接做单应变换）。
- **与 ocr_plan_0818.md 的关系**：本文仅取代其 §3.2 的路线选择与 §4/§5/§6 中引擎相关内容；模式开关、取帧、标签定位、LabelTextParser（降级为语义分补充与兜底）、打印链路等设计继续有效。
