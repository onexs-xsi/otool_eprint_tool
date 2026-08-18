# plan_0818a — 普通模式元素工具箱（复刻 WePrint 编辑器插入体系）

日期：2026-08-18 · 目标版本：v1.5.6（按规则第三位递增）

> **状态：阶段 A 已完成并真机验证（2026-08-18，v1.5.6 / versionCode 12）。**
> 81 项 JVM 测试 0 失败；真机逐类型插入/属性编辑/点阵预览/锁定/持久化均通过。
> 结果明细见文末「阶段 A 完成记录」。

## 背景与依据

用户反馈：普通页面"普通标签工作台"左下角预留区（当前为"常用文本 + 画布元素 chips"）太空、不符合需求，要求参考 `backup/research` 复刻。

参考来源（WePrint 微打逆向分析，均为离线阅读）：

- `docs/05-标签编辑与模板/02-控件体系与模板格式.md`：控件继承体系（BaseControl → 文本/图片/一维码/二维码/弧形文字/直线/矩形等）
- `docs/05-标签编辑与模板/05-功能面板与属性面板.md`：底部功能面板 18 个插入功能 + 按控件类型分类的属性面板
- `docs/05-标签编辑与模板/04-EditorActivity主页面流程.md`：工具栏（多选/撤销/锁定）、保存/另存为流程
- 用户提供的参考截图：底部元素网格（文本/一维码/二维码/图片/图标/线条/图形/表格/时间/弧形文字/序列号/Excel/扫码/图像识别/语音识别/照猫画虎/边框/签名）

## 目标

把普通模式左下预留区改造成**元素插入工具箱**：可插入多种元素类型，画布与打印渲染、持久化、属性编辑全链路支持。

## 范围分期

### 阶段 A（本轮实现）

插入类型 6 种：**文本、一维码、二维码、线条、矩形、时间**。

- 文本：现状保留（内容/字号/粗体/对齐/微调/水平居中/复制/删除）
- 一维码：内容 + 码制（CODE_128 默认 / EAN_13 / EAN_8 / CODE_39 / ITF / UPC_A），zxing 离线生成（`com.google.zxing:core` 已在依赖中，无需新增依赖）
- 二维码：内容 + 边长(mm)，zxing 离线生成，纠错等级固定 M
- 线条：横/竖方向 + 线宽(mm)
- 矩形：线宽(mm) + 是否填充
- 时间：格式预设（yyyy-MM-dd / yyyy-MM-dd HH:mm / HH:mm:ss / MM-dd），渲染与打印时取当前时间
- 元素通用：拖动、微调、删除、复制；**锁定/解锁**（锁定后画布拖拽与微调失效）
- 全部元素支持 JSON 持久化（向后兼容：缺字段按文本默认值）

### 阶段 B（后续版本，本轮不做）

序列号（逐张递增）、边框（装饰框）、图片（选图+二值化）、图标（内置图标集）、表格、弧形文字、签名、撤销/重做（快照式）、多选。

### 明确不做（与离线定位冲突）

- Excel/云端数据列绑定、云模板同步、分享/上传（需联网；后续可考虑离线 CSV）
- OCR 图像识别、ASR 语音识别、照猫画虎/AI（需三方 SDK 或云端模型）

## 技术设计

### 1. 元素模型（`model/TextElement.java` 扩展，类名不动）

新增字段：

- `type`：String，`"text"|"barcode"|"qrcode"|"line"|"rect"|"time"`，默认 `"text"`
- `heightMm`：float，盒类元素高度（文本仍按字号行高估算，不使用该字段）
- `format`：String，码制（barcode）或时间格式（time），空为默认
- `lineWidthMm`：float，线条/矩形线宽，默认 0.3
- `filled`：boolean，矩形是否填充
- `locked`：boolean，锁定标志

JSON：`toJson/fromJson` 追加上述字段，`optString/optDouble/optBoolean` 带默认值，旧文档无损读取。

`LabelDocument.normalize()`：按类型分别夹取（文本维持现状；盒类夹 heightMm 1..heightMm；锁定不影响几何）。

### 2. 画布渲染（`view/LabelCanvasView.java`）

`drawElement` 按 `element.type` 分派：

- text：现状 StaticLayout
- barcode/qrcode：`com.google.zxing.MultiFormatWriter` 生成位矩阵后按元素盒尺寸绘制（按内容+尺寸+格式做简单 LruCache，避免每帧重算）
- line：按方向画线宽矩形
- rect：空心（stroke=lineWidthMm）或填充矩形
- time：按 format 取当前时间格式化为文本，走文本路径

触摸：命中测试对盒类元素用 widthMm×heightMm；`locked` 元素可选中但不可拖动。

### 3. 打印渲染（`render/LabelBitmapRenderer.java`）

同上分派；打印位图与画布预览保持一致（条码/二维码按 DPI 点阵生成）。

### 4. 插入工具箱（`layout-land/activity_main.xml` 的 `normalReservedPanel`）

重写为：

- 第 1 行标题"插入元素"
- 第 2-3 行 2×3 网格按钮：文本 / 一维码 / 二维码 / 线条 / 矩形 / 时间
- 保留"画布元素 chips + 清空"行（元素列表即参考应用的层管理简化版）

插入落位：参照参考应用"栅格自动落位"思路简化——新元素放标签左上起按已有元素数量纵向错位 2mm 瀑布落位，插入即选中。

### 5. 属性编辑（右侧 `textEditorCard` 按类型动态显隐）

- 公共行：位置微调 ←↑↓→、水平居中、复制、删除、锁定开关
- 文本行：内容、字号 －/＋、粗体、对齐
- 条码行：内容、码制 Spinner
- 二维码行：内容、边长输入
- 线条行：横/竖切换、线宽输入
- 矩形行：线宽输入、填充 CheckBox
- 时间行：格式 Spinner（4 预设）

`showSelectedElement` 按 type 切换可见行；`MainActivity` 新增对应读写监听。

### 6. 打印管线

无需改动：`sendPrint` 已是"渲染 Bitmap → MonochromeRaster → 协议编码"，新类型在位图阶段即已扁平化。

### 7. 器件整理模式

不受影响（其预留区为模板面板，已在 v1.5.5 完成）。

## 改动文件清单

| 文件 | 改动 |
| --- | --- |
| `model/TextElement.java` | 类型化字段 + JSON |
| `model/LabelDocument.java` | normalize 按类型夹取；addElement(type) 工厂 |
| `view/LabelCanvasView.java` | 分类型绘制 + 盒类命中 + 锁定 |
| `render/LabelBitmapRenderer.java` | 分类型打印渲染 |
| `render/BarcodeRasterizer.java`（新增） | zxing 条码/二维码矩阵生成与缓存 |
| `layout-land/activity_main.xml` | 工具箱网格 + 属性行动态化 |
| `MainActivity.java` | 插入动作、属性读写、锁定、类型切换 |
| `model/TimeElementFormat.java`（新增） | 时间格式预设枚举 |
| 测试 | `TextElementJsonTest`（兼容读写）、`BarcodeRasterizerTest`（矩阵尺寸/内容合法） |
| `build.gradle` / README | versionCode 12 / 1.5.6 |

## 验证

1. JVM 单测全过（含新增 2 个测试类）
2. 真机逐类型插入 → 画布截图核对渲染
3. 每种类型编辑属性 → 点阵预览截图核对
4. 重启 App 验证持久化与旧文档兼容（现有 "02214227" 文本元素不受影响）
5. P1 实打保护不变（25×15 限制、就绪门控不动）

## 风险与取舍

- 条码内容非法字符（如 EAN-13 非数字）由 zxing 抛异常 → 插入/编辑时捕获并 toast，不落非法状态
- 弧形文字/图片/表格等放阶段 B，避免本轮范围失控
- 锁定仅做"防拖动"，不做参考应用的权限语义

---

## 阶段 A 完成记录（2026-08-18 · v1.5.6 / versionCode 12）

### 已实现（与计划逐项对应）

- **元素模型类型化**：`TextElement` 增加 `type/heightMm/format/lineWidthMm/filled/locked`，JSON 全部带默认值，旧文档无损读取（单测覆盖）
- **6 种插入类型**：文本 / 一维码（CODE_128·EAN_13·EAN_8·CODE_39·ITF·UPC_A）/ 二维码（边长可调、纠错 M）/ 线条（横竖+线宽）/ 矩形（线宽+填充）/ 时间（4 种格式预设，渲染取当前时间）
- **渲染**：`LabelCanvasView` 与 `LabelBitmapRenderer` 按类型分派；条码/二维码由新增 `render/BarcodeRasterizer`（zxing + 纯 Java LRU 缓存）离线生成；非法内容画布显示占位框、打印跳过
- **工具箱**：左下预留区改为"插入元素 2×3 网格 + 画布元素 chips（类型前缀）+ 清空"；插入按序号网格化错位落位（瀑布重叠问题的简化修正）
- **属性卡动态化**：按类型显隐 码制/边长/线宽/填充/时间格式 行；标题随类型变化；文本类保留字号/粗体/对齐；通用 微调/水平居中/复制/删除/**锁定**（锁定后拖拽与微调失效并 toast 提示）
- **依赖**：仅测试类路径新增 `org.json:json:20240303`（JVM 单测用；App 运行时无新增依赖）

### 真机验证（SM-X800，adb 截图/uiautomator 驱动）

- 6 类型逐个插入 → 画布渲染正确（条码条纹、QR 点阵、线、矩形、时间文字）
- 码制 CODE_128→EAN_13：10 位数字非法 → toast + 占位框 ✓
- 锁定勾选后微调无效 ✓；chips 类型前缀 ✓；清空后重启恢复居中默认占位 ✓
- 点阵预览（打印渲染路径）五类型全部正确 ✓
- 元素文档重启后完整恢复（持久化 ✓）

### 测试

81 项 JVM 测试 0 失败（新增 `TextElementJsonTest` 3 项、`BarcodeRasterizerTest` 4 项、`ComponentLabelFactoryTest` 4 项随本轮一并入库）。

### 产物

- APK：`artifacts/EPrint-native-v1.5.6-debug.apk`（已安装到测试机）
- 计划外顺带修复：无

### 遗留到阶段 B（优先级建议）

1. 序列号（逐张递增）— 打印场景高频需求
2. 撤销/重做（JSON 快照栈，成本低）
3. 图片 / 图标 / 边框 / 签名
4. 表格、弧形文字、多选

---

## 阶段 B1 范围（2026-08-18 启动 · 目标 v1.5.7 / versionCode 13）

按优先级取四项，均为编辑器级、可离线实现：

1. **序列号**：新元素类型 `serial`；`text` 作前缀，`serialCounter/serialStep/serialDigits` 三个参数；打印任务成功入队后按步长递增并持久化（按任务计，不按份数）；属性行：当前值/步长/位数
2. **撤销/重做**：MainActivity 侧 JSON 快照双栈（上限 50，800ms 内连续修改合并为一步）；按钮放预留区 chips 行；模式切换时清空栈
3. **边框**：文档级 `borderStyle`（0 无 / 1 单框 / 2 双框），画布与打印一致渲染；工具箱"边框"按钮循环切换
4. **弧形文字**：新元素类型 `arctext`，沿上拱圆弧排布（drawTextOnPath），盒式命中，字号/粗体可调

工具箱扩为 3×3：文本/一维码/二维码、线条/矩形/时间、序列号/弧形/边框。

### 阶段 B2（仍不做，后续版本）

图片（选图+二值化+落盘）、图标（内置图标集）、表格、签名、多选。

### 结果

**阶段 B1 已完成并真机验证（2026-08-18，v1.5.7 / versionCode 13），85 项 JVM 测试 0 失败。**

- **序列号**：元素类型 `serial`；`text` 为前缀，`serialCounter/serialStep/serialDigits` 可编辑（属性行 当前/步长/位数）；渲染为"前缀+补零计数"（`serialText()`，单测覆盖）；打印任务成功入队后按步长递增并持久化（单测覆盖递增逻辑；真机未实打，不消耗 P1 打印许可）
- **撤销/重做**：JSON 快照双栈（上限 50，800ms 连续修改合并一步）；chips 行新增 撤销/重做 按钮，空栈半透明显示；模式切换清空栈。真机验证：插入+边框切换后两次撤销（边框消失→弧形删除）、一次重做（弧形恢复）✓
- **边框**：文档级 `borderStyle`（无/单框/双框）随文档持久化；画布与打印渲染一致；工具箱"边框"按钮循环切换并 toast 当前样式 ✓（旧 JSON 文档缺省为无边框，单测覆盖）
- **弧形文字**：元素类型 `arctext`，沿上拱圆弧排布（`drawTextOnPath`，画布与打印同逻辑）；盒式命中，字号/粗体可调 ✓
- 工具箱扩为 3×3（文本/一维码/二维码、线条/矩形/时间、序列号/弧形/边框）
- 新增 `SerialElementTest` 4 项；空文档 fromJson 被默认替换的既有行为在测试中注明
- APK：`artifacts/EPrint-native-v1.5.7-debug.apk`（已安装测试机，测试后已恢复干净默认文档）

### 阶段 B2 候选（后续）

图片（选图+二值化+落盘）、图标（内置图标集）、表格、签名、多选、序列号按份数递增（需与打印份数联动）。

---

## 阶段 B2 范围（2026-08-18 启动 · 目标 v1.5.8 / versionCode 14）

1. **图片**：系统照片选择器选图（无需权限），压缩落盘到应用私有目录（≤800px JPEG）；元素类型 `image`（`text`=文件路径），盒式渲染保持宽高比；属性行：替换图片 / 宽 mm / 高 mm；打印经点阵阈值自然二值化
2. **图标**：内置 8 个矢量图标（对勾/叉/警示/星/右箭头/圆/加/房屋），元素类型 `icon`（`format`=图标 id），画布与打印一致绘制；属性行：图标样式 Spinner
3. **表格**：元素类型 `table`，`tableRows/tableCols`（1-8），单元格内容经 textInput 按行优先每行一格；渲染网格+单元格居中文字
4. **签名**：手写签名对话框（轨迹描画/清除/保存），保存为图片文件并生成图片元素
5. **序列号按份数递增**：P1 打印份数 N 时，序列号按 步长×N 递增（其余协议按 1）

工具箱扩为 5 行：+图片/图标/表格 +签名。多选仍留后续。

### 结果

**阶段 B2 已完成并真机验证（2026-08-18，v1.5.8 / versionCode 14），89 项 JVM 测试 0 失败。**

- **图片**：系统照片选择器选图（PickVisualMedia，无需权限），采样压缩至 ≤800px JPEG 落盘应用私有目录；元素 `image`（text=路径），盒内保持宽高比渲染；按图片宽高比自动修正盒高；属性行：更换图片 / 宽 / 高；打印经点阵阈值自然二值化。真机验证：插入→选择器→导入→渲染 ✓
- **图标**：8 个内置矢量图标（对勾/叉/警示/星/右箭头/圆/加/房屋，`render/IconCatalog` 纯 Canvas 绘制）；元素 `icon`（format=id）；样式 Spinner 切换。真机验证：对勾渲染清晰 ✓
- **表格**：元素 `table`（tableRows/tableCols 1-8），textInput 每行一个单元格（行优先，不足补空）；网格 + 单元格居中文字渲染。真机验证：2×2 表格渲染与属性行 ✓
- **签名**：手写签名对话框（`view/SignatureView`，描画/清除/保存），导出白底 PNG 并生成图片元素（盒高按 2:1）。真机验证：笔画描画与保存生成 ✓
- **序列号按份数递增**：P1 打印份数 N 时按 步长×N 递增（`advanceSerial(copies)`，单测覆盖；真机未实打）
- 工具箱扩为 5 行（+图片/图标/表格 +签名）；chips 增加 图片/图标：样式/表格 R×C 标签
- 新增 `StageB2ElementTest` 4 项；`render/ImageElementRenderer`（解码缓存）
- APK：`artifacts/EPrint-native-v1.5.8-debug.apk`（已安装测试机，测试后已恢复干净默认文档）

---

## v1.5.9 修复发布（2026-08-18 · versionCode 15）

针对阶段 B1/B2 完成度复核中发现的问题完成修复：

- **图标绘制状态隔离**：`IconCatalog.draw()` 完整保存并恢复调用方 `Paint` 的 style、线宽、端点和连接样式，避免图标之后的文本、条码等元素被错误按描边模式渲染
- **首步撤销可靠性**：初始/模式切换/撤销/重做后把快照节流时间复位；首次编辑不再因启动后 800ms 合并窗口而丢失可撤销基线
- **复制元素完整性**：统一通过 `TextElement.copyWithNewId()` 深复制全部通用及类型专属字段，覆盖序列号、表格、锁定状态等，并生成新元素 ID
- **一维码默认格式**：旧文档或缺失格式的一维码统一归一化为 `CODE_128`，不再误落到二维码格式
- **签名保存线程**：签名位图在 UI 线程快照，PNG 压缩和写盘转移到工作线程，消除 Lint `WrongThread` 错误并避免阻塞界面
- **自动验证**：91 项 JVM 测试通过（0 failure / 0 error / 0 skipped）；Android Lint 0 error（保留 284 项 warning）；`testDebugUnitTest lintDebug assembleDebug` 构建成功
- **APK 校验**：应用 ID `com.otool.eprint`，minSdk 24 / targetSdk 36，v2 Debug 签名和 16 KiB 页面兼容 zipalign 校验通过；未声明 `INTERNET` 权限
- **交付 APK**：`artifacts/EPrint-native-v1.5.9-debug.apk`（4,279,050 字节；SHA-256 `AEE23F13E579B9627058E3A9F99DF362D87958FEF83207C9B798C7A91B744F7B`）
- **真机启动验证**：通过无线 ADB 连接 Samsung SM-X800 / Android 14（`DEVICE_IP:ADB_PORT`），以保留数据方式从 v1.5.8 / code 14 成功升级到 v1.5.9 / code 15；冷启动成功，主界面保持前台且未发现 `FATAL EXCEPTION`
- **验证边界**：本轮完成安装与启动冒烟验证，未触发真实打印；各修复项的完整交互回归和实打验证仍需单独执行

---

## v1.5.10 按钮文字修复（2026-08-18 · versionCode 16）

- **根因**：全局 `EPrintButton` 关闭 `includeFontPadding`，并使用上 6dp / 下 12dp 的不对称内边距；固定高度按钮在三星 CJK 字体度量下可用字形区域过小，导致汉字顶部或底部被裁切
- **修复**：恢复字体上下安全区，统一使用垂直居中和 0dp 对称垂直内边距；数字尺寸按钮同步继承同一安全样式
- **版本规则 Skill**：新增个人技能 `eprint-release-versioning`，规定普通更新必须递增 `versionName` 最后一段、同步递增 `versionCode`，并同步 README、计划、构建信息和版本化 APK；Skill 已通过 `quick_validate.py`
- **自动验证**：91 项 JVM 测试通过（0 failure / 0 error / 0 skipped）；Android Lint 0 error（284 warning）；`testDebugUnitTest lintDebug assembleDebug` 构建成功
- **APK 校验**：应用 ID `com.otool.eprint`，minSdk 24 / targetSdk 36，v2 Debug 签名和 16 KiB 页面兼容 zipalign 校验通过；未声明 `INTERNET` 权限
- **真机验证**：无线 ADB 覆盖安装到 Samsung SM-X800 / Android 14，设备版本确认为 v1.5.10 / code 16；冷启动无崩溃，截图确认插入元素区及其他同类按钮汉字完整且垂直居中
- **交付 APK**：`artifacts/EPrint-native-v1.5.10-debug.apk`（4,149,207 字节；SHA-256 `D44E04291D7F1E30F781A284AD2679E526E7D24A0A726C59AADD12054EC14592`）
- **验证边界**：本轮未触发真实打印

---

## v1.5.11 编辑与越界缩放（2026-08-18 · versionCode 17）

- **元素按钮完整显示**：删除 `chipLabel()` 对文本、条码、二维码和弧形文字的手工截断；元素列表保留横向滚动，并在选择元素后自动滚动到当前按钮，确保当前元素名称完整露出
- **近全屏编辑**：预览区“添加文本”改为“编辑”；点按后把完整的类型属性面板移入 94%×92% 编辑窗口，并增加通用 X/Y/宽/高（mm）编辑，可输入负坐标
- **画布直接缩放**：选中未锁定元素后显示右下角橙色缩放手柄；拖动即可调整元素尺寸，二维码保持正方形，横/竖线按主方向调整
- **允许越界**：`LabelDocument.normalize()` 不再把元素位置夹回标签，也不再按剩余边界缩小宽高；仅保留 1mm 最小尺寸及防异常上限。预览选择框允许显示到标签外，标签内容和打印位图仍按物理标签边界自然裁切
- **负坐标打印一致性**：新增有符号坐标点阵换算，避免负坐标在打印渲染时被错误改成 1 点
- **回归测试**：新增越界尺寸 JSON 往返及负坐标点阵换算测试；93 项 JVM 测试通过（0 failure / 0 error / 0 skipped）
- **构建验证**：Android Lint 0 error（289 warning）；`testDebugUnitTest lintDebug assembleDebug` 成功；v2 Debug 签名和 16 KiB 页面兼容 zipalign 校验通过；未声明 `INTERNET` 权限
- **真机验证**：无线 ADB 覆盖安装到 Samsung SM-X800 / Android 14，设备版本确认为 v1.5.11 / code 17；“编辑”窗口成功打开；拖动手柄把 8×8mm 图标放大到超出 25×15mm 标签后未自动缩小，随后使用撤销恢复；选择“新文本”后元素列表自动滚动并完整显示当前按钮；未发现 `FATAL EXCEPTION`
- **交付 APK**：`artifacts/EPrint-native-v1.5.11-debug.apk`（4,445,643 字节；SHA-256 `D7547C87D378B4F8CD3AE63333D7F5E3559861E835C2638B33160E8F980FFACD`）
- **验证边界**：本轮未触发真实打印

---

## v1.5.12 预览与配置布局优化（2026-08-18 · versionCode 18）

- **预览区增高**：横屏左栏由“预览 1 / 工具 2”改为 `1:1`，实时预览区高度比原布局增加约 50%；画布内边距由 8dp 缩减到 2dp，标签按比例尽量铺满可用区域
- **顶部打印机配置**：打印机档案下拉框和“设备配置”按钮移到顶部状态栏，“更换打印机”仍位于其右侧；移除原右下独立配置卡，所选元素编辑区获得完整高度
- **标签大小下拉**：“标签大小”移到实时预览标题右侧，提供 `20×10 / 25×15 / 40×30 mm` 预设和“自定义配置”入口；非预设尺寸显示为“当前 宽×高 mm”并保留再次修改入口
- **自定义尺寸**：弹窗支持宽 10–80 mm、高 10–200 mm，校验通过后更新当前文档、兼容性提示和实时预览
- **文字完整性**：尺寸下拉框增高到 36dp，顶部档案选择器加宽到 220dp；Samsung SM-X800 真机上 `25×15 mm`、`P1 · 德佟私有协议 · 203 DPI` 和“设备配置”均完整显示
- **自动验证**：93 项 JVM 测试通过（0 failure / 0 error / 0 skipped）；Android Lint 0 error（292 warning）；`testDebugUnitTest lintDebug assembleDebug` 构建成功
- **APK 校验**：应用 ID `com.otool.eprint`，minSdk 24 / targetSdk 36，v2 Debug 签名和 16 KiB 页面兼容 zipalign 校验通过；未声明 `INTERNET` 权限
- **真机验证**：无线 ADB 覆盖安装到 Samsung SM-X800 / Android 14，设备版本确认为 v1.5.12 / code 18；预览高度、顶部配置、尺寸菜单和自定义弹窗均已实际操作，冷启动无崩溃
- **交付 APK**：`artifacts/EPrint-native-v1.5.12-debug.apk`（4,160,828 字节；SHA-256 `51847865C947BA177471F66C776CBF7799CDD671F3443A651B55EA153B38EAE7`）
- **验证边界**：本轮未触发真实打印

---

## v1.5.13 元素配置与一维码修复（2026-08-18 · versionCode 19）

- **一维码根因与修复**：ZXing 的一维码 `BitMatrix` 高度为 1，旧实现沿用二维码的正方形模块算法，最终只画出一排方点；现按矩阵列宽横向映射，并把每个黑条拉伸到可用条码高度，画布预览和打印位图保持一致
- **条码尺寸与落位**：一维码默认高度改为根据标签高度计算（25×15 mm 标签默认 11 mm）；新元素先应用类型尺寸、再修正初始 X/Y，避免落在底边后只剩少量可见高度。后续编辑仍允许负坐标和越界
- **条码/二维码配置**：一维码支持 CODE_128、CODE_39、EAN_13、EAN_8、UPC_A，并新增“显示内容”；二维码新增 L/M/Q/H 纠错等级，编码缓存键同步区分纠错等级
- **通用几何配置**：所有元素在主属性面板直接提供 X、Y、宽、高（mm）；宽高可独立编辑，位置和尺寸继续支持超出标签边界
- **类型专属配置补齐**：文字/时间/序列号保留字号、粗体、对齐及各自格式；线条提供方向与线宽；矩形提供线宽与填充；弧形提供上弧/下弧；图片提供更换、宽高及完整适应/铺满裁切/拉伸填满；图标提供样式与线宽；表格提供内容、行列数与线宽
- **边框选择**：文档边框由循环按钮改为“无边框/单线边框/双线边框”单选对话框，当前样式直接显示在按钮文字中
- **自动验证**：96 项 JVM 测试通过（0 failure / 0 error / 0 skipped）；Android Lint 0 error（320 warning）；`testDebugUnitTest lintDebug assembleDebug` 构建成功
- **APK 校验**：应用 ID `com.otool.eprint`，minSdk 24 / targetSdk 36，v2 Debug 签名和 16 KiB 页面兼容 zipalign 校验通过；未声明 `INTERNET` 权限
- **真机验证**：无线 ADB 覆盖安装到 Samsung SM-X800 / Android 14，设备版本确认为 v1.5.13 / code 19；完整一维码及所有元素配置行均已逐项检查，最终冷启动无崩溃；临时测试元素已撤销，标签文档保持本轮验证前状态
- **交付 APK**：`artifacts/EPrint-native-v1.5.13-debug.apk`（4,172,989 字节；SHA-256 `20746A48C5325D54BC71F30AAAF2B707CE5E7FB1F47A7842BCE534EB703E6134`）
- **验证边界**：本轮未触发真实打印

---

## v1.5.14 条码编辑、黑白预览与设备配置完善（2026-08-18 · versionCode 20）

- **一维码能力补齐**：支持 CODE128、CODE39、ITF25、EAN13、EAN8、UPCA、UPCE、ISBN、CODABAR、CODE93、扩展 CODE39、ITF14；新增可读文字位置（不显示/上方/下方）、静区和 0/90/180/270° 旋转，一维码保持完整条高并与打印渲染一致
- **二维编码能力补齐**：支持 QRCode、PDF417、DataMatrix；QRCode 提供 L/M/Q/H 纠错等级，全部支持内容、静区、旋转及 X/Y/宽/高配置
- **位置与尺寸**：在水平居中旁新增垂直居中；实时预览右侧按钮明确改为“自定义”，专门编辑自定义标签宽高，尺寸预设仍保留在下拉菜单
- **打印能力预览**：点阵预览先按当前打印机阈值转成严格 1-bit 黑白，并应用 P1 的边距、旋转、翻转和反色任务变换；彩色图片不再以彩色形式误导显示
- **常用设备配置**：打印浓度、打印速度改为带实时数值的滑块；“反色打印（黑白互换）”从高级区提升到常用区，并统一作用于预览与实际打印数据
- **控件文字完整性**：画布元素按钮使用独立无垂直内边距样式、34dp 高度和紧凑语义文案；当前元素自动滚动到可见位置，一维码/二维码橙色按钮均不再裁字
- **自动验证**：99 项 JVM 测试通过（0 failure / 0 error / 0 skipped）；Android Lint 0 error（329 warning）；`testDebugUnitTest lintDebug assembleDebug` 构建成功
- **APK 校验**：应用 ID `com.otool.eprint`，minSdk 24 / targetSdk 36，v2 Debug 签名和 16 KiB 页面兼容 zipalign 校验通过；未声明 `INTERNET` 权限
- **真机验证**：无线 ADB 覆盖安装到 Samsung SM-X800 / Android 14，设备版本确认为 v1.5.14 / code 20；已实际打开一维码配置、自定义标签尺寸、1-bit 黑白点阵预览和设备配置，确认 12 种码制、2 个滑块及反色常用项；冷启动无崩溃，测试结束恢复原“器件整理”模式
- **交付 APK**：`artifacts/EPrint-native-v1.5.14-debug.apk`（4,318,172 字节；SHA-256 `7D298C8FD466AC33CE3BAA2E94E78D6B6E6B6043E0492CF191A152433B05AF77`）
- **验证边界**：本轮未触发真实打印

### 后续候选（阶段 C）

多选、参考应用的 Excel/CSV 离线数据列绑定、云模板（需重新评估离线定位）。
