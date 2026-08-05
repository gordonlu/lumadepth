# Contributing to LumaDepth

欢迎为 LumaDepth 贡献力量。请先阅读并遵守以下约定。

## 约定

- 提交信息使用英文，简洁描述变更内容。
- 代码使用 Kotlin，遵循 Kotlin 官方代码风格。
- 新增功能必须附带 JVM 单元测试（`app/src/test`），涉及 Android 平台能力的
  附带插桩测试（`app/src/androidTest`）。
- 不得引入 GPL/AGPL/SSPL、非商业许可证或来源不明的代码。
- LumaDepth 保持完全离线：不要引入网络权限、统计或广告 SDK。

## 工作流

1. Fork 仓库并创建功能分支。
2. 修改代码并运行测试：

   ```bash
   ./gradlew testDebugUnitTest lintDebug assembleDebug
   ```

3. 提交并推送，创建 Pull Request。
4. PR 通过 CI（Android CI workflow）后即可合并。

## 真机验证

涉及 Ultra HDR 编码/显示的变更，请在 Android 14+ 真机上运行：

```bash
./gradlew connectedDebugAndroidTest
```

## 许可证

贡献的代码将以 Apache License 2.0 授权（见 [LICENSE](LICENSE)）。
