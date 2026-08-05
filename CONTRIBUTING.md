# Contributing to LumaDepth

感谢对 LumaDepth 的关注。

> External code contributions are not currently accepted.
> Issues, testing reports and feature suggestions are welcome.

**目前不接受外部代码贡献。** 欢迎提交 Issue、测试报告与功能建议。

原因：在建立明确的贡献者许可协议（CLA）之前，外部贡献代码的版权归属
会影响 LumaDepth 的商业授权安排。CLAU 建立后本政策将更新。

## 内部约定

- 提交信息使用英文，简洁描述变更内容。
- 代码使用 Kotlin，遵循 Kotlin 官方代码风格。
- 新增功能必须附带 JVM 单元测试（`app/src/test`），涉及 Android 平台能力的
  附带插桩测试（`app/src/androidTest`）。
- 不得引入 GPL/AGPL/SSPL、非商业许可证或来源不明的代码。
- LumaDepth 保持完全离线：不要引入网络权限、统计或广告 SDK。
- 自有代码文件需包含 SPDX 标识 `PolyForm-Noncommercial-1.0.0` 与版权声明；
  第三方文件不得替换其原有许可证声明。

## 验证

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

涉及 Ultra HDR 编码/显示的变更，请在 Android 14+ 真机上运行：

```bash
./gradlew connectedDebugAndroidTest
```

## 许可证

自有代码适用 PolyForm Noncommercial 1.0.0（见 [LICENSE](LICENSE)）；
商业使用需另行授权（见 [COMMERCIAL_LICENSE.md](COMMERCIAL_LICENSE.md)）。
