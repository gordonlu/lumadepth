# THIRD PARTY NOTICES

## LumaDepth 自有代码

LumaDepth 自行编写的 Kotlin、图像算法、UI 与业务代码的版权归 Gordon Lu 所有
（`Copyright (c) 2026 Gordon Lu`），适用 **PolyForm Noncommercial License 1.0.0**
（SPDX: `PolyForm-Noncommercial-1.0.0`，见 [LICENSE](LICENSE)）。

第三方组件仍然适用各自原有许可证，不受 PolyForm Noncommercial 约束。

## 第三方组件清单

| 项目 | 来源 | 版本 | 许可证 | 用途 | 是否修改源码 |
| --- | --- | --- | --- | --- | --- |
| Kotlin 标准库 | https://github.com/JetBrains/kotlin | 2.0.20 | Apache-2.0 | 应用语言运行时 | 否 |
| AndroidX Core KTX | https://developer.android.com/jetpack/androidx | 1.13.1 | Apache-2.0 | Android 兼容组件 | 否 |
| AndroidX Lifecycle | https://developer.android.com/jetpack/androidx | 2.8.6 | Apache-2.0 | ViewModel 生命周期 | 否 |
| AndroidX Activity Compose | https://developer.android.com/jetpack/androidx | 1.9.2 | Apache-2.0 | Compose 集成 | 否 |
| Jetpack Compose (BOM) | https://developer.android.com/jetpack/compose | 2024.09.00 | Apache-2.0 | 界面框架 | 否 |
| kotlinx-coroutines | https://github.com/Kotlin/kotlinx.coroutines | 1.8.1 | Apache-2.0 | 协程与后台线程 | 否 |
| Android Gradle Plugin | https://developer.android.com/build | 8.5.2 | Apache-2.0 | 构建工具（构建期） | 否 |
| Gradle | https://gradle.org | 8.9 | Apache-2.0 | 构建工具（构建期） | 否 |
| JUnit 4 | https://github.com/junit-team/junit4 | 4.13.2 | EPL-1.0（测试专用，不随 APK 发布） | 单元测试 | 否 |
| androidx.test | https://developer.android.com/training/testing | 1.2.1 | Apache-2.0 | 插桩测试 | 否 |
| Android SDK / Android Framework | https://developer.android.com | API 34 | Apache-2.0 | 系统 API：Gainmap、ImageDecoder、MediaStore | 否 |

## 平台级说明

LumaDepth 使用 Android 14（API 34）官方 `android.graphics.Gainmap`、
`Bitmap.setGainmap()` 与 `Bitmap.compress()` 完成 Ultra HDR JPEG 的编码与解码。
Android 平台内部的 Ultra HDR 编解码由 Google 的
[libultrahdr](https://github.com/google/libultrahdr)（Apache-2.0 / MIT 双许可）实现，
它随 Android 系统分发。LumaDepth 不直接依赖、不捆绑、不修改 libultrahdr；
如未来直接集成 libultrahdr，将保留其原始许可证、版权及 NOTICE 声明。

## 参考项目

[andAicaroid](https://github.com/takusan23/andAicaroid)（Apache-2.0）仅作为 Ultra HDR
技术路线的研究参考（JNI 桥接思路、元数据概念）。LumaDepth 未复制其任何代码，
未使用其 `libaicaroid` 依赖，未采用其包名、界面或项目结构。

## 未使用的组件

- `io.github.takusan23:libaicaroid` — 未使用。
- `google/libultrahdr` — 未直接依赖（系统 API 已覆盖）。
- 参考项目的任何源码 — 未复制。

## 版权与许可保留

Android、AndroidX、Jetpack Compose 的版权归 Google LLC 所有；Kotlin 版权归
JetBrains s.r.o. 所有。以上组件各自的许可证全文可在其官方仓库获取。
