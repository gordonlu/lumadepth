# LumaDepth

> 让照片拥有更真实的光与深度

LumaDepth 是一款完全离线的计算摄影工具：把普通照片转换成**真正的 Ultra HDR 照片**——不是滤镜，而是标准的 HDR 图像格式，在支持 HDR 的屏幕上会显示更亮、更有层次的光影。

![Android CI](https://github.com/gordonlu/lumadepth/actions/workflows/android.yml/badge.svg)
[![License](https://img.shields.io/badge/License-PolyForm%20Noncommercial%201.0.0-lightgrey.svg)](LICENSE)

> LumaDepth is source-available for noncommercial use under the
> PolyForm Noncommercial License 1.0.0.
>
> Commercial use requires a separate license from the copyright holder.
>
> LumaDepth 公开源代码供个人学习、研究和非商业使用。
> 未经版权所有者另行授权，不得用于商业产品、收费服务或其他营利活动。

---

## 快速开始

1. 下载 APK：[最新 Release](https://github.com/gordonlu/lumadepth/releases) → `LumaDepth-1.0.0.apk`
2. 安装（需要 **Android 14 及以上**）
3. 打开应用 → 「选择照片」
4. 拖动分割线对比效果 → 调节强度 → 「导出」
5. 照片保存在系统相册 `Pictures/LumaDepth`

## 功能

- **SDR → Ultra HDR**：将普通 JPEG / PNG / WebP 照片转换为真正包含 Gain Map 的 Ultra HDR JPEG
- **自动优化**：根据照片亮度自动调节高光范围与保护强度，默认效果自然不夸张
- **原图 / HDR 对比**：可拖动分割线对比
- **HDR 强度、局部增强**：两个滑杆手动微调
- **高质量模式**：更自然的细节增强（处理稍慢）
- **识别 HDR 照片**：相册里没有 HDR 标识？批量选择照片，应用告诉你哪些包含 HDR 信息
- **完全离线**：不需要网络，照片绝不上传

## 截图

*（待完善）*

## 支持的设备

- **Android 14（API 34）及以上**
- 非 HDR 屏幕也能正常使用：显示模拟预览，导出的文件在支持 Ultra HDR 的设备上可查看完整效果

## 常见问题

**为什么导出的照片在相册里看起来和普通照片一样？**
Ultra HDR 文件在普通查看器里显示 SDR 主图（完全兼容）；只有在支持 Ultra HDR 的查看器/屏幕上才会显示更亮的 HDR 效果。应用内已提供模拟预览，可先看效果。

**怎么知道哪张照片是 HDR？**
相册里没有标识的话，用应用首页的「识别 HDR 照片」批量检测即可。

**HDR 照片可以再转换吗？**
可以，但会基于其标准版本重新生成 HDR 效果，原始 HDR 数据不会保留（应用会提示）。

**照片会上传吗？**
不会。所有处理都在手机本地完成，应用不申请网络权限。

## 隐私

- 完全离线，不申请网络权限，照片不上传
- 通过系统照片选择器选图（无存储权限），只读取你主动选择的照片
- 输出通过系统相册保存，不修改、不删除已有照片
- 临时文件处理结束即删除

## 开发者

```bash
# 单元测试
./gradlew testDebugUnitTest

# 静态检查
./gradlew lintDebug

# Debug APK
./gradlew assembleDebug

# 真机插桩测试（Android 14+ 设备/模拟器）
./gradlew connectedDebugAndroidTest
```

**APK 下载（CI 构建）**：仓库 Actions → 最新 **Android CI** 运行 → Artifacts → `LumaDepth-debug-apk`。

**技术概览**：使用 Android 14 官方 Gainmap API 完成 Ultra HDR 编码与验证；图像处理全部离线、确定性、可重复，包括线性光域亮度分析、SDR-to-HDR 逆色调映射（高光扩展 + 阴影/白色/噪声保护）、高光区域分类、连续细节置信度增强、Gain Map 生成（1/4 尺寸单通道）与元数据封装；导出后重新读取文件验证 Gain Map 存在。线程模型：文件与编码在 IO 线程、像素计算在后台线程，UI 永不阻塞。

**真机验证说明**：纯算法由 JVM 单元测试覆盖（CI 自动运行）；Ultra HDR 编码 → 重新解码 → Gain Map 验证由插桩测试覆盖（API 34 模拟器 CI）。真实 HDR 屏幕显示效果需要 Android 14+ 真机人工确认（安装后导出照片，在支持 HDR 的查看器中确认高光更亮且无灰白雾感）。

## 许可证

- **自有代码**：PolyForm Noncommercial 1.0.0（SPDX: `PolyForm-Noncommercial-1.0.0`，`Copyright (c) 2026 Gordon Lu`），源码可用，允许个人学习、研究与非商业使用；商业使用需另行授权，见 [COMMERCIAL_LICENSE.md](COMMERCIAL_LICENSE.md)
- **第三方组件**：Android 平台 API、AndroidX、Kotlin 等，各自许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)

## 已知限制

- 非 HDR 屏幕仅显示模拟预览；导出的文件需在支持 Ultra HDR 的设备/查看器上查看完整效果
- 不同品牌手机的 HDR 显示效果可能不同
- SDR 照片中已丢失的高光纹理无法真实恢复
- 导出主图最长边上限 3840px（超大图自动降级）
- 真机 HDR 显示效果尚待多机型人工确认

## 后续方向

- 深度效果（景深虚化、重聚焦、Relight）
- Image Enhance
- 更先进的本地图像算法

*（以上功能尚未实现，仅为方向。）*
