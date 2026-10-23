# 第三方软件与模型声明

更新日期：2026-10-24。根目录 MIT 许可仅覆盖本项目有权许可的自有代码；下列组件保留各自版权和许可证。许可证原文放在 [`third_party/licenses/`](third_party/licenses/)，随附文件的大小、SHA-256、来源和版本记录在 [`third_party/assets.json`](third_party/assets.json)。

## 仓库直接随附的内容

| 内容 | 来源 / 版本 | 许可与声明 |
|---|---|---|
| MNN、MNN Express、LLM、OpenCL、Vulkan、音频和 OpenCV 桥接的 arm64 `.so` | [Alibaba MNN 3.6.1](https://github.com/alibaba/MNN/releases/tag/3.6.1)，官方 Android 分发包 | Apache-2.0；见 `MNN-3.6.1.txt` 及相关第三方许可 |
| MNN / LLM C++ 头文件 | [MNN 3.6.1](https://github.com/alibaba/MNN/tree/3.6.1)，原版权头保留 | Apache-2.0；MNN 自带的第三方组件仍按各自许可 |
| `glm_ocr_jni.cpp` 中参考、裁剪的 JNI 会话和流式调用实现 | [MNN MnnLlmChat](https://github.com/alibaba/MNN/tree/3.6.1/apps/Android/MnnLlmChat) | Apache-2.0，Copyright Alibaba Group Holding Limited；本项目添加会话裁剪、错误处理、指标和取消恢复逻辑，修改部分不取消上游许可要求 |
| `nlohmann/json.hpp` | [JSON for Modern C++ 3.11.3](https://github.com/nlohmann/json/tree/v3.11.3) | MIT，Copyright 2013–2023 Niels Lohmann；见 `nlohmann-json-3.11.3.txt` |
| `libc++_shared.so` | MNN 3.6.1 Android 包中附带的 LLVM C++ runtime | Apache-2.0 WITH LLVM-exception 及历史组件条款；见 `LLVM-libcxx.txt`。本机 JNI 编译所用 Android NDK r27d NOTICE 另附供核查，不能据此推定 MNN 预编译库使用了相同编译器版本 |
| PP-OCRv6 small 检测、识别 ONNX 模型与字典；PP-OCRv5 PP-LCNet 方向分类模型 | [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR)，经 [RapidAI/RapidOCR 模型镜像 v3.9.2](https://modelscope.cn/models/RapidAI/RapidOCR/files) 获取 | Apache-2.0；见 `PaddleOCR.txt`、`RapidOCR.txt`；精确下载路径及哈希在资产清单中 |
| OCR 检测、方向、识别、CTC / DB 后处理的实现参考 | [RapidOCR](https://github.com/RapidAI/RapidOCR) 与 PaddleOCR 的公开实现 | 保留 `RapidOCR.txt` / `PaddleOCR.txt`，相关 Apache-2.0 来源不因项目 MIT 声明被重新许可 |
| Gradle Wrapper 脚本、JAR | [Gradle 8.14.3](https://github.com/gradle/gradle/tree/v8.14.3) | Apache-2.0；Wrapper 分发包 SHA-256 已固定 |

八个随附 `.so` 已与本机留存的 MNN 官方 Android ZIP 逐字节比对；三个 ONNX 模型与 RapidOCR 发布清单的 SHA-256 一致，字典与镜像下载内容一致。记录的是这些具体文件，不代表可以把任意同名模型或二进制替换进来。

MNN 依赖的 FlatBuffers、half、Protobuf、RapidJSON、Skia、TensorFlow 等组件具有各自条款。随附的 MNN 第三方许可保留原文；某项许可随仓库保存，不代表当前每个 `.so` 都链接了该组件。不得删除第三方源码中的版权头或 NOTICE。

## 通过 Gradle 获取的依赖

| 组件 | 当前直接依赖版本 | 许可 |
|---|---|---|
| AndroidX AppCompat / Activity / Core | 1.7.1 / 1.11.0 / 1.17.0 | Apache-2.0，Android Open Source Project |
| ZXing Android Embedded | 4.3.0 | Apache-2.0，Journey Mobile / JourneyApps；`ZXing-Embedded-4.3.0.txt` |
| ZXing Core | 3.5.3 | Apache-2.0；`ZXing-3.5.3.txt` 包含上游附加声明 |
| ONNX Runtime Android | 1.22.0 | MIT，Microsoft Corporation；`ONNX-Runtime-1.22.0.txt` 和 `ONNX-Runtime-ThirdPartyNotices.txt` |
| OpenCV Android | 4.10.0 | Apache-2.0，OpenCV contributors；`OpenCV-4.10.0.txt`，相关第三方组件另按原条款 |
| JUnit（仅测试） | 4.13.2 | EPL-1.0；`JUnit-4.13.2.txt` |
| JSON-java（仅 JVM 测试） | 20240303 | 上游明确标为 Public Domain；`JSON-java-20240303.txt` |
| AndroidX Test JUnit / Espresso（仅设备测试） | 1.3.0 / 3.7.0 | Apache-2.0 |

完整的实际解析依赖及 POM 许可声明见 [`third_party/dependencies.json`](third_party/dependencies.json)，包括传递引入的 AndroidX、Kotlin、协程、Hamcrest 等。依赖升级或改变构建配置后，需要重新核查清单与许可；此文件不是供应链安全认证。Maven 制品的 POM 许可信息也不能取代其第三方 NOTICE。

## 可选外部模型，不附权重

- **GLM-OCR**：基础模型 [zai-org/GLM-OCR](https://huggingface.co/zai-org/GLM-OCR) 的模型卡标记 MIT；本项目兼容的 [MNN/GLM-OCR-MNN](https://modelscope.cn/models/MNN/GLM-OCR-MNN) 量化包模型卡标记 Apache License 2.0。分别遵守上游条款，不能把模型、运行时、量化工具的许可混为一谈。
- **Qwen3.5-2B-MNN**：[taobao-mnn/Qwen3.5-2B-MNN](https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN)，固定 revision `35781816d7b6a9dcb273a6765ac9563401951c3c`，模型卡标记 Apache-2.0。部署模板记录固定文件哈希；本仓库不附权重，也不改变其许可。

## 不随公开源码分发的材料

- 厂商 APK、DEX、反编译工程、厂商字体、模板、图标和 SDK；这些本地研究材料不属于项目 MIT 授权。
- 厂商内置授权令牌和默认令牌回退；本项目不再提供此类材料。
- 立创分类种子和合并目录快照：未取得明确的再分发许可记录，已从可分发源码和清理后的 Git 历史中排除。本地导入说明见 [BUILDING.md](docs/BUILDING.md)。
- 模型权重、设备截图、ADB 私有端点、签名私钥和历史 APK。

商标和产品名称用于识别兼容目标，不表示厂商认可、合作或支持。兼容性结论以实际验证范围为准。
