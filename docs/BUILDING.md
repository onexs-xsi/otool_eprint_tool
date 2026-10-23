# 从源码构建 EPrint

仓库交付源码，不发布本次 README 整理对应的 APK 或 GitHub Release。当前开发版本为 v1.5.42 / 48，供本机界面截图验证；维护者本机 SDK、历史安装包、目录快照和原始设备截图不属于源码分发内容。

## 环境

使用 Android Studio 打开 `apps/android`，或安装以下命令行环境：

| 工具 | 版本 |
|---|---|
| JDK | 17+（本机验证使用 JDK 21） |
| Gradle / Android Gradle Plugin | Wrapper 8.14.3 / AGP 8.13.0 |
| Android SDK Platform / Build Tools | android-36 / 35.0.0 |
| Android NDK / CMake | 27.3.13750724（r27d）/ 3.22.1 |
| Python | 3.10+，仅维护脚本需要 |

在 SDK Manager 中安装这些组件并接受 SDK 许可。命令行对应：

```text
sdkmanager "platform-tools" "platforms;android-36" "build-tools;35.0.0" "ndk;27.3.13750724" "cmake;3.22.1"
sdkmanager --licenses
```

Windows PowerShell 设置环境，JDK 路径替换为本机实际位置：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
```

macOS/Linux 同样设置 `JAVA_HOME` 和 `ANDROID_HOME`，常见 SDK 路径为 `~/Library/Android/sdk` 或 `~/Android/Sdk`。Android Studio 也可以生成不入 Git 的 `apps/android/local.properties`，其中 `sdk.dir` 指向本机 SDK。

## 检查与编译

从仓库根目录执行：

```powershell
& .\apps\android\gradlew.bat -p apps/android :app:testDebugUnitTest :app:lintDebug :app:externalNativeBuildDebug :app:compileDebugAndroidTestJavaWithJavac
```

这组任务检查 JVM 单测、Lint、JNI 和设备测试代码编译，不生成安装包。macOS/Linux 使用：

```sh
./apps/android/gradlew -p apps/android :app:testDebugUnitTest :app:lintDebug :app:externalNativeBuildDebug :app:compileDebugAndroidTestJavaWithJavac
```

首次需要联网下载 Gradle 和 Maven 依赖；缓存齐全后可追加 `--offline`。应用运行时没有 `INTERNET` 权限。Wrapper 已固定分发包 SHA-256；不需要维护者的 `.tools` 目录。

仓库包含 arm64-v8a 的 MNN 库、编译头文件和 PP-OCR ONNX 资产。来源、许可和哈希见 [第三方声明](../THIRD_PARTY_NOTICES.md) 及 `third_party/assets.json`。运行 `python tools/verify_assets.py` 可以检查随附资产完整性。`third_party/` 的声明和许可证会作为 assets 一起进入自行构建的安装包。

如需自行生成本地调试包，执行 `:app:assembleDebug`，输出为 `apps/android/app/build/outputs/apk/debug/app-debug.apk`。目前只支持 arm64-v8a，不是 32 位设备构建。开发者对外分发有改动的安装包时，应递增版本并核实许可和兼容性。

`apps/android/tools/build_version.py` 是后续生成新版本的维护工具，默认会升版本并构建；本次源码准备不运行它生成版本。可以用 `python apps/android/tools/build_version.py --check-tools` 只验证工具发现逻辑。该脚本优先使用环境变量和 Wrapper，同时保留本机 `.tools` 兼容回退。

## 可选 GLM / Qwen 模型

PP-OCR 已内置，二维码、条形码和基础文字解析无需另下大模型。GLM/Qwen 是可选增强；模型缺失时保留可用的基础识别结果。

GLM 兼容包来源是 [MNN/GLM-OCR-MNN](https://modelscope.cn/models/MNN/GLM-OCR-MNN)，基础模型为 [zai-org/GLM-OCR](https://huggingface.co/zai-org/GLM-OCR)。量化包模型卡标记 Apache-2.0，基础模型标记 MIT，分别保留上游条款。设备目录为应用外部私有目录 `files/models/glm-ocr-mnn/`，需包含：

```text
config.json  llm_config.json  llm.mnn  llm.mnn.weight
visual.mnn  visual.mnn.weight  embeddings_bf16.bin  tokenizer.txt
```

Qwen 固定使用 [taobao-mnn/Qwen3.5-2B-MNN](https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN)，revision 为 `35781816d7b6a9dcb273a6765ac9563401951c3c`，Apache-2.0。通过支持固定 revision 的模型下载工具取得完整快照（含 `.gitattributes`），然后把 `tools/models/qwen3.5-2b-mnn/` 中的 `config-text-only.json` 和 `model_manifest.json` 复制进去，再验证：

```text
python tools/verify_assets.py --manifest tools/models/qwen3.5-2b-mnn/model_manifest.json --root /path/to/qwen3.5-2b-mnn
```

全部文件通过后，部署到设备 `files/models/qwen3.5-2b-mnn/`。即使只走文本推理，仍须保留固定快照中校验要求的视觉文件，不能擅自删除或修改。配置模板的字节内容也参与固定哈希校验，请直接复制，不要经编辑器转换换行符。

调试包在常规 Android 主用户下的完整模型路径为 `/sdcard/Android/data/com.otool.eprint/files/models/`。可先 `adb push` 到 `/data/local/tmp/eprint-models/`，再用调试包的 `run-as com.otool.eprint` 复制到应用外部私有模型目录；不同设备可能限制对该目录的访问，部署后以识别配置显示的实际目录、完整性校验和模型初始化结果为准。应用仅在外部私有目录不可用时才回退内部目录，不能随意把模型放到内部目录替代。模型权重不要提交到 Git。

## 可选目录数据

公开源码排除了立创分类种子和合并目录快照，因为尚无明确的再分发许可记录；数据不属于项目 MIT 授权。没有快照时应用使用基础解析回退，品类、品牌和封装的完整目录校验不可用。

取得适当的数据使用及再分发权后，可以在本机放置以下文件：

- `apps/android/app/src/main/assets/catalog/lcsc_category_packages.json`：schemaVersion 2，含 `packages`、`brands` 和 `categories` 数组，参与本地构建。
- `tools/lcsc/electronic_categories.json`：可选同步种子，含 `categories` 数组和一致的 `categoryCount`，单项字段要求见同步脚本。

这两个路径均被 Git 忽略，本机原有文件仍可用于本地验证。同步脚本只在显式运行时请求数据：

```text
python tools/lcsc/sync_package_catalog.py --categories /path/to/authorized-categories.json --workers 2 --delay-ms 400 --refresh
```

公开可访问或 robots 允许抓取，不自动等于获得再分发许可。

## 打印与设备检查

应用不内置厂商授权令牌，不提供默认令牌回退。用户明确填入合法取得的自定义令牌后才执行授权握手；两项留空跳过，一项缺失或格式非法则阻止该次发送。旧配置中的内置授权开关不再读取。

设备测试任务为 `:app:connectedDebugAndroidTest`，会生成并安装调试包；Qwen 测试还依赖完整模型和足够内存。本次源码准备仅编译设备测试代码。实体打印以 [兼容矩阵](PRINTER_COMPATIBILITY.md) 中的实际设备、BLE 通道和纸张条件为准。

无线连接辅助脚本用法为 `apps\android\tools\adb_connect.bat IP:PORT`；不传地址只列出设备，不自动连接维护者设备。
