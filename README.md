# LumaDepth

> 让照片拥有更真实的光与深度

LumaDepth 是一款完全离线的移动端计算摄影工具。首个功能是将普通 SDR 照片转换为
**真正包含 Gain Map 的 Ultra HDR JPEG**——不是普通滤镜，而是标准的 HDR 图像格式。

![Android CI](https://github.com/gordonlu/lumadepth/actions/workflows/android.yml/badge.svg)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

## 当前首个功能：Ultra HDR

```
选择普通 SDR 照片（JPEG / PNG / WebP）
→ 分析图像亮度（线性空间直方图）
→ SDR-to-HDR 逆色调映射
→ 生成 Gain Map（原图 1/4 尺寸，单通道）
→ 编码为 Ultra HDR JPEG（Android 官方 Gainmap API）
→ 重新读取并验证（确认 Gain Map 存在）
→ 保存到系统相册
```

支持的输入：JPEG、PNG、WebP（通过 Android Photo Picker 选择）。
输出：`LumaDepth_UltraHDR_yyyyMMdd_HHmmss.jpg`（Ultra HDR JPEG，保存到 `Pictures/LumaDepth`）。

## 截图

*（待完善：安装 Debug APK 后可在编辑页看到原图/HDR 可拖动分割线对比。）*

## 支持的 Android 版本

- **最低：Android 14（API 34）** —— 首版聚焦 Ultra HDR，直接使用系统级 Gainmap API，
  不引入 JNI/NDK/CMake，兼容代码复杂度最低。
- 非 HDR 屏幕设备仍然完整可用（SDR 模拟预览 + 导出真实 Ultra HDR 文件），
  应用会明确提示。

## 构建状态与 APK 下载

| 状态 | 说明 |
| --- | --- |
| [Android CI](https://github.com/gordonlu/lumadepth/actions/workflows/android.yml) | 单元测试 + Lint + Debug APK 构建 |
| [Emulator Tests](https://github.com/gordonlu/lumadepth/actions/workflows/android-emulator.yml) | API 34 模拟器上的 Ultra HDR 编码/验证插桩测试 |

**下载 APK**：打开 [Actions](https://github.com/gordonlu/lumadepth/actions) →
选择最新一次 **Android CI** 运行 → 底部 Artifacts →
下载 **LumaDepth-debug-apk**，解压得到 `LumaDepth-debug.apk`。

## 构建命令

```bash
# 单元测试
./gradlew testDebugUnitTest

# 静态检查
./gradlew lintDebug

# Debug APK（输出：app/build/outputs/apk/debug/app-debug.apk）
./gradlew assembleDebug

# 真机插桩测试（需要 Android 14+ 设备或模拟器）
./gradlew connectedDebugAndroidTest
```

## 技术实现概览

- **技术路线：Android 系统 Gainmap API（路线 A）**。使用 `android.graphics.Gainmap`、
  `Bitmap.setGainmap()`、`Bitmap.compress(JPEG)` 编码，`Bitmap.hasGainmap()` 验证。
  不引入 JNI、NDK、CMake 与任何第三方编码库（libultrahdr 由 Android 平台内部提供）。
  选择理由：API 34+ 官方支持、文档明确支持 1/4 尺寸 Gain Map、编码/解码/验证全链路
  都由平台保证正确性与兼容性，维护成本最低。
- **sRGB → 线性光**：所有增益计算基于线性亮度 `Y = 0.2126R + 0.7152G + 0.0722B`。
- **亮度分析**：缩略图直方图统计 P1/P5/P20/P50/P90/P95/P99、黑/阴影/中间调/高光/白占比、
  RGB 同时剪裁比例、动态范围（档）。
- **逆色调映射**：`highlightMask = smoothstep(highlightStart, highlightEnd, Y)`，
  `gainEv = maxGainEv × highlightMask`，`Y_hdr = Y_sdr × 2^gainEv`，阴影保护使暗部增益趋近 1.0。
- **白色保护**：大面积、低纹理、已剪裁的纯白区域（天空、白墙）限制增益；
  小面积点光源、太阳反光仍正常增强。丢失的高光纹理不会伪造恢复。
- **色彩保护**：单通道亮度增益（保色相）+ 预览渲染使用柔和肩部压缩与饱和度保护。
- **局部增强**：log 亮度弱 unsharp（默认 20/100，自动模式减半），边缘处自动减弱防光晕。
- **Gain Map**：原图 1/4 尺寸单通道灰度；对数空间归一化
  `normalized = clamp((log2(gain) - log2(minBoost)) / (log2(maxBoost) - log2(minBoost)), 0, 1)`。
  `minBoost = 1.0`，`maxBoost = 2^maxGainEv`（≤ 6.0），gamma = 1.0。
- **输出验证**：重新解码输出文件，检查 hasGainmap()、Gain Map 尺寸与主图比例、
  Ratio Min/Max、Gamma、无 NaN，保存到相册后再验证一次。
- **线程模型**：解码/编码在 IO 线程，像素计算在 Default 线程，UI 永不阻塞；
  预览参数防抖（150ms）+ 取消旧任务。

## Ultra HDR 与普通滤镜的区别

普通滤镜只改变像素数值并输出普通 JPEG，HDR 信息（超过屏幕白点的亮度）在保存时就永久丢失。

Ultra HDR JPEG 在 SDR 主图旁额外嵌入一张 **Gain Map** 与元数据：

- 普通 JPEG 查看器看到的是正常 SDR 主图（完全兼容）；
- 支持 Ultra HDR 的屏幕（如大多数现代旗舰手机）会结合 Gain Map 重建 HDR 亮度，
  高光更亮、层次更丰富；
- 文件仍是一个标准 `.jpg`，可正常分享。

LumaDepth 的增强算法基于线性光计算增益比，输出即标准的 ISO 兼容 Ultra HDR 格式。

## 隐私说明

- **完全离线**：不申请网络权限（Manifest 中无 `INTERNET`），不上传任何照片。
- 无统计、无广告、无账号、无追踪。
- 使用 Android Photo Picker 选图（无存储权限），输出通过 MediaStore 保存到系统相册。
- 临时处理文件保存在应用缓存目录，处理结束后立即删除。

## 许可证

LumaDepth 使用 **Apache License 2.0**（见 [LICENSE](LICENSE)），允许商业使用。

第三方依赖及其许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
所有依赖均允许商业与闭源使用。

## 当前限制

- SDR 照片中已经丢失的高光纹理无法真实恢复（不会用生成式方法伪造细节）。
- 不同品牌手机的 HDR 显示效果可能不同（取决于屏幕峰值亮度与系统色调映射）。
- 非 HDR 屏幕只能显示 SDR 模拟预览；导出的文件在支持 Ultra HDR 的设备上才能看到完整效果。
- 导出主图最长边上限 3840px（超大图自动降级），Gain Map 为主图 1/4 尺寸。
- 首版预览为 SDR 模拟（效果预览），不在编辑页启用真实 HDR 窗口渲染。
- 应用图标为矢量占位图，待完善。
- 真机 HDR 显示效果验证尚未完成（需要 Android 14+ 真机，见下节）。

### 真机验证说明

纯算法由 JVM 单元测试覆盖（CI 自动运行）；Ultra HDR 编码→重新解码→Gain Map 验证
由插桩测试覆盖（`app/src/androidTest`，可在 API 34 模拟器 CI 中运行）。
**真实 HDR 屏幕显示效果需要 Android 14+ 真机人工确认**，步骤如下：

1. 安装 `LumaDepth-debug.apk`；
2. 选择一张普通照片，导出 Ultra HDR；
3. 在支持 HDR 的照片查看器（如 Google Photos）中打开，确认高光明显更亮且无灰白雾感。

## 后续方向

- 3D Photo
- Depth Map / Portrait Depth
- Relight
- Image Enhance

*（以上功能尚未实现，仅为架构预留方向。）*
