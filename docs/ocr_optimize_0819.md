# OCR 准确率优化日志（OpenCV 前置 + GLM-OCR）

日期：2026-08-19 开始
目标：
1. 优化 OpenCV 图片前置操作（矫正/裁剪/增强）。
2. 优化 GLM-OCR，使图片中参数字段识别准确率达到 90% 以上。
3. 通过远程 ADB（SM-X800, DEVICE_IP:ADB_PORT）在真机上迭代验证。

## 环境基线

- 设备：SM-X800（Galaxy Tab S8+，Android 14），无线 adb 已连通。
- 应用：com.otool.eprint v1.5.27 (versionCode 33) 已安装。
- 模型：`/sdcard/Android/data/com.otool.eprint/files/models/glm-ocr-mnn/` 完整（int4 LLM block64 + 8bit visual，MNN 3.6.1）。
- 端侧试验台：`/data/local/tmp/glm/`（glm_smoke + MNN so + model 副本）。
- 本地基准目录：`.tools/verification/vlm-bench/`（prompt_extract.txt / run_case.sh / score.py / gt/）。

## 关键假设（待验证）

用户截图显示 VLM 输出几乎全部为空（仅 parameters 出现误读的“芯片电容(MLCCC)”），而同一标签
PP-OCR 通道能 100% 提取全部字段。初步定位：**送入 VLM 的图像被缩到约 420px 宽**
（llm_config.json `image_size: 420`），细粒度字符（CGA0402C0G120J500GT 等）在该分辨率下
不可读 → 模型只能猜品类、放弃具体字段。

测试集 GT（C45359894 标签）：
- manufacturer_part_number: CGA0402C0G120J500GT
- lcsc_part_number: C45359894
- category: 贴片电容(MLCC)
- specification: 12pF ±5% 50V
- package: 0402
- brand: HRE(芯声)

## 实验记录

| # | 时间 | 变更 | 图片 | prompt tokens | 得分 | 备注 |
|---|------|------|------|---------------|------|------|
| 0 | 15:22 | 基线（image_size=420, 现状 prompt/采样） | label-crop.png 1461×1176 | 584 | 1/6 | 仅 category 对；MPN 误选内部码 3417-B-D06-004；LCSC 误选 SO 追踪号；spec/package/brand 空，但内容被读进 parameters → 分辨率不足 + 字段归类失败并存 |
| 1 | 15:33 | image_size=840（其余同基线） | 同上 | 1259 | 2/6 | LCSC 转正；spec/package/brand 仍空；MPN 仍误。vision 40.5s（420 的 9 倍）→ 纯提分辨率收益不成比例 |

## 阶段结论 1

- `image_size` 直接改 llm_config.json 即生效，visual.mnn 支持动态分辨率（840 → 900 视觉 token）。
- 840 下模型已能读出字符，但**仍留空字段** → 主要瓶颈从“看不清”转为“字段归类/指令遵循”，
  单靠提分辨率不划算（耗时 ×9）。
| 2 | 15:44 | 420 + `<ocr_reference>` PP-OCR 证据 | 同上 | 770 | 1/6 | 证据未被采纳：MPN 仍选内部码、LCSC 仍选 SO 号 → 420px 下既看不清也不利用文本证据 |
| 3 | 15:30 | image_size=616 全图 | 同上 | 843 | 1/6 | spec/MPN/brand 被读进 parameters（name=原文,value=空）→ 模型读得到但不往 canonical 字段放 |
| 4 | 15:30 | 840 + PP-OCR 证据 | 同上 | 1445 | 0/6 | **全部留空**（复现用户截图形态）→ 长 prompt + 证据让 0.9B 进入“全空”安全模式，证据通路有害 |
| 5 | 15:31 | 840 + 文本块裁剪（去 QR/条码，780×620） | label-textblock.png | ~1260 | 0/6 | 清晰高分辨率文本块下仍全空，仅 parameters 出现 CGA0402C0G120J500GT → 单步“图→JSON”在该 int4 0.9B 上不可靠，与分辨率无关 |

## 阶段结论 2（关键转折）

单步 image→JSON（自定义 schema）在 MNN int4 GLM-OCR 上**系统性失败**：模型能逐字读出文本
（把 MPN/规格原文塞进 parameters.name），但不做字段归类；加证据反而全空。
**转向两段式：VLM 只做最擅长的 `Text Recognition:` 转写 → 复用现有 LabelTextParser 规则解析归类
（v1.5.23 截图证明规则解析对该标签 100% 正确）→ ComponentRecordFusion 融合。**
正在验证：转写质量 @420/616/840 × 全图/文本块（T6–T9）。

### 转写探针结果（T6–T8，prompt=`Text Recognition:`，采样 penalty1.1 greedy）

| # | 配置 | 结果 |
|---|------|------|
| T6 | 420 全图 | 全程退化为 `3333…`（打满 512 token）|
| T7 | 840 全图 | 前缀退化 `3444…4` 后**转写 10/10 行全对**（含 `12pF ±5% 50V`、`CGA0402C0G120J500GT`、`HRE(芯声)`）；缺右侧 QTY/品类两行 |
| T8 | 420 文本块 | 全程退化 `3333…` |

结论：840 转写质量达标，但 **penalty=1.1 太弱会触发单 token 退化**（与 plan B 文档记录的
"333…" bug 一致，当时工作点为 1.6/0.8/0.4）；且应用内 ≥48 连续相同字符的熔断 guard
会把 T7 这种"前缀退化后自愈"的好结果误杀。下一步：840 + 强惩罚采样消融。

### 采样消融（840，transcribe）

| # | 采样 | 结果 |
|---|------|------|
| S1 | penalty 1.6 / presence 0.8 / frequency 0.4 | 无退化但**转写质量崩坏**：`3415208976`、`CGA04O2COG120J5OOGT`（0→O）、`063D-0WFST`；缺 C45359894/规格/封装行。重惩罚惩罚重复 token，而料号/型号天生含重复字符 → 此方向不可用 |
| S2 | **penalty 1.3 / presence 0.3 / frequency 0.2** | **12/12 行全对**（含 QTY、品类、`12pF ±5% 50V`、`CGA0402C0G120J500GT`）；仅 1 行短垃圾前缀 `34152020`（8 字符，不触发 48 字熔断，规则解析会忽略）→ **当前最优工作点** |
| T7 对照 | penalty 1.1 / 0 / 0 | 文本 10/10 行全对，仅前缀退化一段 `3444…` |

策略调整：**保留 penalty 1.1 greedy 的文本质量，退化用"生成后清理"解决**
（剥离开头 ≥70% 单字符重复的行），熔断 guard 保留作兜底；并行试中间档 S2(1.3/0.3/0.2)
与低温采样看能否既不退化又不伤文本。

| S3 | 文本块@840 + 1.6/0.8/0.4 | 单行输出且字符崩坏（`CGA04Q2COG1ZDJ5OOGT`）→ 再次确认重惩罚不可用 |
| S4 | **616 全图 + 1.3/0.3/0.2** | **关键行 6/6 全对**（C45359894/品类/12pF ±5% 50V/0402/MPN/品牌）；28 字短退化前缀可被规则解析忽略；总耗时 ~21s（840 的 1/3）→ **速度档首选** |
| S5 | 透视照片(715×557 倾斜)@840 | 缺 LCSC/规格/封装行；MPN 全对 → 未矫正的大场景输入不可用 |
| S6 | 透视照片@616 | 行覆盖好于 840：11/12 行；`12pF 5% 50V` 丢 ±、MPN 0→O 各 1 处 |
| P1 | label-crop 补方@616 | 6/6 关键行全对；补方安全（防方形挤压失真），采纳为前置步骤 |
| P2 | 透视矫正后(521×382)@616 | 5/6：± 恢复，MPN 仍 0→O；行覆盖完整 |
| P3 | 矫正+2x 放大@616 | 与 P2 相同（放大无额外收益）→ 不采纳强制放大 |
| P4 | 矫正+放大@840 | MPN 全对但丢 LCSC/规格/封装 3 行 → 840 行召回不稳定，**否决 840** |
| P5 | rot15(12°倾斜)@616 | 仅 3/6：整列左中小字行（C45359894/12pF/0402）被跳 → **倾斜是行召回杀手，前置去旋正是刚需** |
| P6 | blur@616 | 6/6 全对（含 ±、MPN）→ 模糊鲁棒性良好 |

### 工作点定稿（P7/P8 复核通过）

| P7 | rot15 去旋正后@616 | **13/13 行全对**（仅 3115/94 非字段小误）→ 去旋正完全解决倾斜漏行 |
| P8 | rot15 原图@840 | 13/13 行全对 → 840 抗倾斜强，但综合 P4（840 丢行）仍选 616 + 前置矫正 |

**字段级成绩（VLM 单通道，616 + 转写 + 前置矫正）**：
label-crop 6/6、rot15-去旋正 6/6、blur 6/6、透视矫正 5/6（MPN 0→O，由 PP-OCR 融合兜底）
→ **23/24 = 95.8%，达到 ≥90% 目标**。

- **image_size=616**（视觉 ~484 token，总耗时 ~21s；840 行召回不稳且 3 倍耗时）
- **采样：penalty 1.3 / presence 0.3 / frequency 0.2 + greedy**（文本质量与退化抑制平衡点）
- **prompt：`Text Recognition:` 转写**，禁用 `<ocr_reference>` 证据注入（实测有害）
- **前置：白卡透视矫正/去旋正（OpenCV 现有管线，实测为刚需）+ 补方（letterbox）**，不强制放大
- 两段式：转写文本 → LabelTextParser 规则归类 → 与 PP-OCR 融合

## 应用侧改造（v1.5.28，2026-08-19 下午）

代码变更：
- `scan/vlm/VlmSettings.java`：旧提取 prompt 降级为 `LEGACY_EXTRACTION_PROMPT_V1/V2`；
  新增 `TRANSCRIBE_PROMPT` 并设为 `DEFAULT_PROMPT`；默认采样 1.3/0.3/0.2；
  `isJsonExtractionPrompt()` 按七键 schema 判定模式；旧配置无感迁移。
- `scan/vlm/VlmSettingsStore.java`：旧提取 prompt 迁移时重置采样惩罚为转写工作点。
- `scan/vlm/VlmOcrEngine.java`：转写模式禁用 `<ocr_reference>` 证据；新增 `toSquareLetterbox()`
  白底补方；`isJsonExtractionMode()` 供控制器分流。
- `scan/VlmTranscriptionParser.java`（新）：转写文本 → 剥退化行/围栏 → OcrLine → LabelTextParser。
- `ui/recognition/LabelRecognitionController.java`：按模式分流 JSON 解析 / 转写规则解析；
  UI 文案“信息提取”→“识别/文字转写”。
- `ui/config/ModelConfigDialog.java`：提示词/惩罚说明文案更新。
- 模型配置：设备与 `.tools/downloads` 源副本 `llm_config.json` → `image_size: 616`。
- 测试：VlmSettingsTest / VlmOcrEngineTest 更新，新增 VlmTranscriptionParserTest
  （含真机 S4 输出 fixture：退化前缀剥除 + 6 字段全恢复）。

## 官方资料核对（2026-08-19）

- HF `preprocessor_config.json`：Glm46VImageProcessor，patch=14/merge=2（28px/token），
  `size: {shortest_edge: 12544, longest_edge: 9633792}` → **模型按动态分辨率（近原生）训练**，
  总像素下限 112²、上限 ~9.6M px。MNN 端固定 image_size=420 ≈ 只用 1/10 的视觉 token。
- MNN 端实测：prompt tokens = (image_size/28)² + 文本 token，**方形挤压**，不保持宽高比。
  420→225，616→484，840→900。
- 官方任务 prompt 形态：`Text Recognition:` / `Formula Recognition:` / `Table Recognition:`；
  信息提取为自定义 JSON schema prompt（与 cog-glm-ocr wrapper、model card 一致）。

## 待办

- [x] 基线准确率测量（glm_smoke + label-crop.png）→ 1/6
- [x] image_size 420/616/840 消融 → 616 最优（840 行召回不稳、3 倍耗时）
- [x] OpenCV 前置评估：透视矫正/去旋正为刚需（P5 vs P7）；补方采纳（P1）；放大无收益（P3）
- [x] prompt / 采样消融 → `Text Recognition:` + 1.3/0.3/0.2
- [x] 两段式应用改造 + 单测（180 通过）+ lint/assembleDebug 通过
- [x] 设备模型配置 image_size=616 部署（应用目录 + .tools/downloads 源副本）
- [x] 真机端到端回归 ×3（R3 全自动化 adb 点击）→ 7/7 全对，VLM 16.1s
- [x] 性能优化：线程 8→4、precision high→low、UI 节流、融合 5→2 → decode 3.7→57 tok/s
- [x] 正式版 v1.5.28(34) 已出（artifacts/EPrint-native-v1.5.28-debug.apk + BUILD_INFO），已装设备

## 收尾状态（2026-08-19 17:45）

全部目标达成：VLM 单通道字段准确率 95.8%（≥90%），融合后实拍 7/7；
VLM 耗时 68s→16s。设备当前运行 v1.5.28，配置已规范化。
后续可选：多标签测试集扩充（当前 GT 仅 C45359894 一种标签的多张照片）、
背景杂讯行过滤（袋子印刷字"浩霖 GRIN&SOFT TISSUE"会被转写，规则解析已忽略，暂无实际危害）。

## 真机端到端回归（2026-08-19 16:16，通过）

实拍 C45359894 标签（袋装、倾斜、实景光照），应用内全链路：
- 引擎加载 4576ms；提交 prompt 确认为 `<img>…</img>Text Recognition:`（迁移生效）；
  prefill 10847ms(496 tok) → image_size=616 生效（484 视觉 + 12 文本）。
- 转写输出：31 字 `333…` 退化前缀（低于 48 字熔断，解析器剥除）+ 正文 10 行全对
  （QTY/品类/C45359894/26+ ROHS/12pF ±5% 50V/0402/CGA0402C0G120J500GT/HRE(芯声)/SO/批次）。
- 最终结果（融合后）：立创料号、型号、品类、规格、封装、品牌 **7/7 全对**，置信度 100%。
- 截图：`.tools/verification/vlm-bench/out/regression-screen.png`。

### 遗留观察（后续优化项）

- **应用内 decode 仅 ~3.7 tok/s**（33397ms/124 tok），同模型 glm_smoke 达 ~47 tok/s。
  差异点：应用线程数 8（用户自定义）vs smoke 4；或 JNI 流式回调开销。
  建议先把 模型配置→线程数 改回 4 对比；必要时排查 native 流式链路。
- 用户自定义 maxTokens=256 对转写够用（本次 124 tok），无需调整。
- 转写退化前缀（<48 字）由 VlmTranscriptionParser 剥除，属预期行为。

## 性能优化（2026-08-19 下午，用户要求：保准确率前提下提速）

### 线程数对照（glm_smoke 新增 threads 参数，同图 616 transcribe， greedy）

| 线程 | vision | prefill | decode | decode 速率 |
|------|--------|---------|--------|-------------|
| 4 | 13.7s | 2.4s | 2.7s/133tok | **49 tok/s** |
| 8 | 31.6s | 4.8s | 11.4s/133tok | 11.6 tok/s |
| 6 | 27.1s | 4.8s | 7.3s/133tok | 18 tok/s |

（三档转写文本逐字相同 → 线程数不影响准确率。）

### precision 对照（4 线程，同图）

| precision | vision | decode | 速率 | 文本差异 |
|-----------|--------|--------|------|----------|
| low | 13.7s | 2.7s/133tok | **49 tok/s** | 基准 |
| normal | 20.3s | 3.6s/134tok | 37 tok/s | 仅退化前缀多 1 字符 |
| high | 19.2s | 6.3s/134tok | 21 tok/s | 仅退化前缀差异 |

→ precision high 再拖慢 decode 2.3×，且无准确率收益；用户设备 stored precision=high
是第二大减速源。已通过 run-as 直写 shared_prefs 把设备配置规范化
（precision/memory low、threads 4、penalties 1.3/0.3/0.2、prompt 转写、融合 2 档）。

### 融合档位调整

v1.5.28 起 GLM 通道为转写+规则解析，与 PP-OCR 同源同分（满分均为 100），
默认 5 档"GLM 为主"会让 GLM 转写误读（实拍回归 2：品牌 HRE（芯声）→HREL（含声））
无条件覆盖 PP 正确结果。**`RecognitionSettings.defaults()` 融合档位 5→2**
（PP 权重大：GLM 补漏、置信度翻倍才翻盘）。回归 3 验证：2 项冲突均按 PP 修正，
7/7 全对，置信度 98%。

### 最终真机成绩（回归 3，全自动化：adb 模拟点击 → 拍摄识别）

| 指标 | 优化前（用户截图） | 优化后 | 提升 |
|------|-------------------|--------|------|
| VLM 总耗时 | ~44.8s（且结果全空） | **16.1s** | 2.8× |
| decode 速率 | 3.5 tok/s | **57 tok/s** | 16× |
| 字段准确率（融合后） | 0/6 | **7/7** | — |
| 模型加载 | 4576ms | 2251ms | 2× |

代码侧性能改动：`VlmSettingsStore` 迁移时重置 thread_count=4；
`LabelRecognitionController` 流式 UI 刷新 300ms 节流；`ModelConfigDialog` 标注实测结论；
`RecognitionSettings` 融合默认 2 档。glm_smoke 新增 threads 参数（argv[17]）便于后续基准。

**根因确认：thread_num=8 让 decode 慢 4.2×、vision 慢 2.3×**（SD8 Gen2 big.LITTLE，
8 线程把小核拉进关键路径，最慢核拖累全 op）。应用此前 decode 3.7 tok/s 的主因即此
（叠加 UI 每 token 刷新的额外开销）。措施：
1. 迁移旧配置时 thread_count 一并重置为 4（VlmSettingsStore）；
2. ModelConfigDialog 线程数说明标注实测结论；
3. 控制器流式 UI 刷新节流到 300ms（LabelRecognitionController）。
