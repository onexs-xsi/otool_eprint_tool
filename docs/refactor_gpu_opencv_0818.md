# 重构 + GLM-OCR GPU 适配 + OpenCV 图像处理 过程记录（2026-08-18）

## 目标

1. 拆分 `MainActivity.java`（4395 行单一大文件）为按功能/页面划分的多个类。
2. 解决 GLM-OCR（MNN-LLM）的 GPU 加速适配问题（用户拍板：Vulkan 优先，CPU 兜底）。
3. 用 OpenCV 完善标签查找、图像裁切、图像遮盖。
4. 每次构建自动提升 versionName 最后一位（同步 versionCode +1）。
5. 使用无线 ADB（SM-X800，`DEVICE_IP:ADB_PORT`）远程调试与真机验证。
6. 本文档记录完成过程。

## 现状基线（动手前核实）

- 当前版本 `versionCode 23` / `versionName 1.5.17`，基线单测 161 项全绿。
- MainActivity：无 Fragment，单 Activity + 代码构建 Dialog；生效布局为 `res/layout-land/activity_main.xml`（122 个 id）。
- GLM-OCR：`VlmSettings.configJson()` 只写顶层 `backend_type`，模型 `config.json` 的 `mllm` 子块（视觉编码器，visual.mnn 权重 462M）未被覆盖，恒为 CPU；GPU 初始化失败无回退。`libMNN_Vulkan.so` / `libMNN_CL.so` 已打包在 jniLibs。
- 图像处理：零 OpenCV 依赖，全部为手写 Java/Bitmap 算法（`LabelImageAnalyzer`、`LabelFrameProcessor`、`BarcodeMasker`）。早期文档曾因包体拒绝 OpenCV，本次按需求引入。
- 版本号：硬编码于 `app/build.gradle`，BUILD_INFO 纯手工撰写，无构建脚本。
- 设备：SM-X800（骁龙 8 Gen 1 / Adreno 730 / 8GB，Android 14），无线 ADB 已确认在线。

## 阶段 0：版本自增构建脚本（→ v1.5.18）

- 新增 `apps/android/tools/build_version.py`：升版本号（versionName 末位 +1、versionCode +1）→ 跑 `testDebugUnitTest lintDebug assembleDebug` → APK 归档到 `artifacts/` 并生成 BUILD_INFO 骨架（单测计数自动从 test-results XML 统计，更新内容/真机验证段人工补写）。工具链固定使用 `.tools/` 内 JDK 21 / Android SDK / Gradle 8.14.3。
- 新增 `apps/android/tools/adb_connect.bat`：封装 `adb connect DEVICE_IP:ADB_PORT`，注明无线调试端口随机变化时的处理办法与 MSYS2 路径转换坑。

（以下各阶段随进度补充）

## 阶段 1：MainActivity 拆分（→ v1.5.19）✅

四批纯搬运重构，每批均 `testDebugUnitTest assembleDebug` 全绿（161 项单测）：

| 批次 | 新类（`com.otool.eprint.ui` 包） | 内容 | MainActivity 行数 |
|---|---|---|---|
| 1 | `discovery/PrinterDiscoveryDialog`、`config/ModelConfigDialog`、`profile/ProfileSettingsDialog` | 三个自包含对话框 | 4395 → 3612 |
| 2 | `recognition/LabelRecognitionController` | OCR/VLM 识别流水线 + 处理过程 UI + 识别日志 | → 3198 |
| 3 | `editor/ElementEditorController`、`template/ComponentTemplateController`、`SimpleTextWatcher` | 元素编辑器簇 + 器件模板面板 | → 1678 |
| 4 | `scan/EmbeddedScannerController` | 嵌入式扫码器配置与扫码流程 | → 1448 |

- 协作方式：controller 构造注入 View/Context + 小型 Host 接口回调 MainActivity；`persistAndRedraw` 实现迁入 ElementEditorController，MainActivity 保留同名委托，原调用点零改动。
- dpiSpinner/profileSpinner/labelSizeSpinner 与画布尺寸逻辑耦合深，保留在 MainActivity，经 Host 回调交互。
- 真机冒烟（v1.5.19）：无线 ADB 安装冷启动正常，截图 `.tools/verification/eprint-v1519-main.png`。

## 阶段 2：GLM-OCR Vulkan 适配（→ v1.5.20）

### 真机 benchmark（SM-X800 / Adreno 730，MNN 3.6.1，glm_smoke 经 adb shell 直跑，同一张 420px 标签图，128 token）

| 配置 | vision | prefill | decode(128 tok) | 结论 |
|---|---|---|---|---|
| LLM=CPU（现状） | 4.2s / 3.5s | 0.81s | 2.33s（≈55 tok/s） | 基线 |
| LLM=Vulkan | 4.3s（CPU） | 24.5s / 23.8s | 51.7s（≈2.5 tok/s） | 慢 ~25 倍，二次运行无缓存改善 |
| LLM=OpenCL | 3.8s（CPU） | 56.0s | 6.9s（≈18.5 tok/s） | 显著慢于 CPU |
| 视觉=Vulkan（LLM=CPU） | 48.3s | — | — | 慢 ~12 倍 |
| 视觉=OpenCL（LLM=CPU） | 171.5s | — | — | 慢 ~45 倍 |

**结论**：GLM-OCR 为 int4 量化 0.9B 模型，MNN 3.6.1 的 Vulkan/OpenCL 后端每次加载都要做权重转换/内核编译且无持久缓存，GPU 路径全面慢于 CPU。GPU 加速在本模型 + 本设备上不成立，**默认后端维持 CPU**。

### 代码适配（让 GPU 选项可用、可控、可诊断）

- `VlmSettings.configJson()`：新增 `mllm` 子块（backend/thread 跟随所选后端，precision 保持官方 normal），此前视觉编码器恒为 CPU、顶层配置覆盖不到它。
- `VlmOcrEngine.load()`：GPU 后端 `nativeInit` 失败时释放残留会话、自动以 CPU 配置重试，新增 `effectiveBackend()` / `backendFallbackReason()`；识别日志提示"GPU 后端加载失败，已自动回退 CPU"。
- `VlmSettings.withBackend()`：生成仅替换后端的配置副本；`BACKEND_LABELS` 标注实测结论（GPU 选项保留供其他设备尝试）。
- `glm_ocr_jni.cpp`：load 失败错误信息附带生效配置 JSON，便于 logcat 定位。
- 单测 161 → 163（新增 mllm 子块、withBackend 两项）。

### 顺路发现并修复的部署问题

- 设备模型目录缺 `visual.mnn` / `visual.mnn.weight`（此前 VLM 实际处于"模型未部署"状态），已补齐并校验 MD5 一致。
- `adb push` 直推应用私有目录会在 fchown 失败时丢弃文件，需先推 `/data/local/tmp` 再 `cp` 并 `chmod 644`。

## 阶段 3：OpenCV 图像处理（→ v1.5.21，真机发现 bug 后由 v1.5.22 修复发布）

### 引入方式

- 依赖 `org.opencv:opencv:4.10.0`（Maven Central 官方 AAR，abiFilters 限 arm64-v8a），APK 65.2MB → 89.8MB（+23.4MB）。
- `scan/opencv/OpenCVSupport.java`：`OpenCVLoader.initLocal()` 加载，失败缓存 false；`MainActivity.onCreate` 预热。**所有 OpenCV 入口经 isAvailable() 守卫，native 加载失败时行为与引入前完全一致**。
- `scan/LabelImageAnalyzer.java` 纯 Java 实现一行未删（JVM 单测基线，166 项全绿）。

### 三个完善点

1. **标签查找 + 透视纠偏**（新增 `OpenCVLabelRectifier`，283 行）：降采样灰度 → 高斯模糊 → Otsu（亮区失败再试反相）→ 闭运算 → 最大轮廓（面积占比 0.15~0.98）→ `approxPolyDP` 四边形逼近（非四边形退 `minAreaRect`）→ 角点排序/有效性校验 → `getPerspectiveTransform` + `warpPerspective` 全分辨率正视图。任何一步失败返回 null，回退原 Java 二次裁剪 + Matrix 旋正路径。新增设置开关 `perspectiveRectify`（默认开启，模型配置页可关）。此为原 ocr_plan_0818 §3.3 列为"后续增强项"的透视变换，本次落地。
2. **图像裁切**：纠偏成功时直接输出正视图（替代 Matrix 近似旋正）；失败路径沿用原实现，铺白/质心居中逻辑不变。
3. **图像遮盖**（新增 `OpenCVBarcodeRegionFinder`，229 行）：`BarcodeMasker` 的条纹/矩阵候选检测运行时优先 OpenCV（Sobel 梯度 + `morphologyEx` 闭运算 + 轮廓几何筛选，阈值与 Java 版对齐），返回 null 回退纯 Java；protectedTextRegions 避让不变，`experimentalWeakCodeMask` 默认关闭语义不变。

### 单测

163 → 166：新增 `OpenCVLabelRectifierTest`（角点排序、凸四边形接受、退化/自交/越界拒绝，纯 JVM float[] 签名不触碰 native）。

### 真机发现的兼容性问题（v1.5.21 → v1.5.22）

- v1.5.21 真机首测：`Imgproc.boxPoints` 在本机 OpenCV 4.10.0 输出 4x2 CV_32FC1 布局，按 4x1 CV_32FC2 读取抛 `ArrayIndexOutOfBoundsException`，纠偏每次回退 Java 路径。v1.5.22 修复为两种布局兼容（按 `channels()` 分派）。
- 教训：OpenCV Java 绑定 Mat 布局有版本差异，取值代码须防御性处理。

### 纠偏命中率调优（v1.5.23 → v1.5.25，用户反馈"找标签效果不好"驱动）

- v1.5.23：多轮廓（前 6）+ 多档 epsilon（0.02/0.035/0.05）+ 新增 Canny/自适应通道 + 全链路诊断日志。真机发现 **minAreaRect 兜底在通道内会短路后续通道**（白场景亮区粘连成一块，外接矩形恒非空）。
- v1.5.24：兜底移到所有通道之后。真机确认四通道均尝试，但白标签 + 白色软管场景仍全部失败。
- 离线调参（`.tools/pyvenv` 的 cv2 + 真机截图，`debug_canny.py`/`debug_hsv.py`）：Otsu/Canny 通道里标签卡与白色软管、纸巾盒亮区粘连成一整块；**HSV 低饱和高亮 mask（S≤60、V≥140）+ 5px 开运算**可稳定分离出卡片并逼近出四边形。
- v1.5.25：新增 `white-card` 通道（位于两个 Otsu 之后、Canny 之前）；minAreaRect 兜底收紧（面积占比 >0.90 视为整幅粘连拒用）。真机命中（占比 0.602），透视纠偏后文字水平、卡片充满画面，GLM 提取字段准确率明显改善。
- 同期 UX 修改：识别配置对话框"重启应用"→"保存并重启"（先校验保存再重启，校验失败不重启）、"保存"→"暂存配置"。

## 追加需求：PP-OCR / GLM-OCR 五档融合权重（→ v1.5.26）

- 原 `preferGlmOnConflict` 布尔开关升级为 `fusionWeightLevel`（1~5）：
  1=PP 为主（GLM 只补漏）、2=PP 权重大（GLM 置信度须超 2 倍才翻盘）、3=均衡按置信度、
  4=GLM 权重大、5=GLM 为主（PP 只补漏）。空字段补漏与档位无关。
- 融合置信度按档位加权（GLM 权重 20/35/50/65/80）；warning 文案随档位变化；识别日志显示当前档位。
- 持久化新键 `label_fusion_weight`，旧 `label_glm_priority` 自动迁移（true→5，false→3）；默认 5 与原默认行为一致。
- UI：识别配置 · 标签页新增五档 SeekBar + 实时档位说明文案。
- 单测 168 → 172（五个档位的冲突取舍行为全覆盖）。
- 真机验证：滑块渲染/切换/暂存持久化均正常（截图 `eprint-v1526-slider.png`）。

## 追加修复：VLM 信息提取消歧与 OCR 证据校验（→ v1.5.27）

- 根因：原七键 JSON 提示词只给 schema、未定义字段角色，GLM-OCR 会把立创标签首行内部编码误当
  `manufacturer_part_number`，把真实型号拆进 `parameters`，并把 ROHS、封装等多行拼入规格。
- 提示词新增字段级约束：内部/仓储编码不等于厂商型号；规格仅允许电气参数，不得混入 ROHS、
  数量、封装和其他字段；型号保持完整；parameters 不得重复型号/品牌/品类/封装。旧默认提示词在
  覆盖安装后自动迁移，自定义提示词仍保留并补齐完整 schema。
- `VlmOcrEngine` 在同一次图像推理中附带同图 PP-OCR 逐行文本作为候选证据，要求模型结合图片
  逐字符核对；证据去控制字符并限制 2400 字符，不增加第二次 VLM 推理。
- `GlmInformationExtractionParser` 增加 OCR-grounding：仅在 PP-OCR 置信度 ≥80 且存在明确反证时，
  修正内部编码冒充型号、无视觉依据字段、混入 ROHS/换行的规格、非法封装，并清理与规范字段重复的
  parameters；VLM 仍承担字段语义，OCR 仅做确定性校验和补漏。
- 新增截图错误 JSON 回归样例，验证恢复 `CGA0402C0G120J500GT`、`C45359894`、
  `12pF ±5% 50V`、`0402`、`HRE(芯声)` 并移除拆分型号参数。

## 真机验证记录

| 版本 | 验证内容 | 结果 |
|---|---|---|
| v1.5.19 | 无线 ADB 安装冷启动（拆分回归） | ✅ 截图 `eprint-v1519-main.png` |
| v1.5.20 | CPU 加载含 mllm 子块（4.7s）；Vulkan 后端整机加载成功（6.1s）；完整识别链路（60.3s/145 tok） | ✅ 截图 `eprint-v1520-recognition.png` |
| v1.5.21 | OpenCV 加载成功；纠偏抛异常回退（boxPoints 布局） | ❌ 发现 bug，未发布 |
| v1.5.22 | OpenCV 加载成功，纠偏无异常；完整识别链路（检测 333ms → 识别 1769ms → GLM 64.9s/189 tok） | ✅ 截图 `eprint-v1522-recognition.png` |
| v1.5.23 | 按钮文案/行为改造 + 纠偏多通道多轮廓；真机发现兜底短路问题 | ⚠️ 部分 |
| v1.5.24 | 兜底短路修复；白场景四通道均未命中 | ⚠️ 部分 |
| v1.5.25 | HSV 白卡通道命中（0.602），纠偏后 GLM 提取准确率明显改善 | ✅ 截图 `eprint-v1525-vlm-input.png` / `eprint-v1525-final.png` |
| v1.5.26 | 五档融合权重滑块渲染/切换/暂存持久化正常，旧布尔配置迁移为 5 档 | ✅ 截图 `eprint-v1526-slider.png` |
| v1.5.27 | 174 项单测、Lint/构建、安装版本与冷启动通过；真实相机 VLM/物理打印待复验 | ⚠️ 部分 |

## 最终状态汇总（六项目标）

1. **拆分 MainActivity**：4395 → 1448 行，新增 `ui/` 包 7 个类（discovery/config/profile 三个对话框 + recognition/scan/editor/template 四个 controller + SimpleTextWatcher）。✅
2. **GLM-OCR GPU 适配**：mllm 子块覆盖视觉编码器、GPU 失败自动回退 CPU 并可见、JNI 错误带配置诊断；benchmark 实测 GPU 全面慢于 CPU（Vulkan prefill 慢 30 倍、视觉 OpenCL 慢 45 倍），默认维持 CPU。✅
3. **OpenCV 完善图像处理**：透视纠偏（新能力）、裁切（正视图直出）、遮盖（OpenCV 候选检测），全链路回退保底。✅
4. **版本自增**：`tools/build_version.py` 每次构建 versionName 末位/versionCode +1，自动归档 APK + 生成 BUILD_INFO 骨架（本批产出 v1.5.18~v1.5.22）。✅
5. **ADB 远程调试**：全程无线 ADB（DEVICE_IP:ADB_PORT）安装/logcat/截图/点按回归；`tools/adb_connect.bat` 固化连接方式。✅
6. **过程文档**：即本文档。✅

基线对照：单测 161 → 166 全绿；Lint 0 error（warning 363 → 367，均为既有风格类）；APK 65.4MB → 86.4MB（OpenCV 占 +23.4MB）。
