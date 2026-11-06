# test-target —— LSPFRIDA 注入测试目标

最小 Android 应用 + 注入测试脚本，用来在真机上验证 LSPFRIDA 的
Java hook 与 Native hook 是否生效。

## 目标包

- 包名：`com.bail.testtarget`，入口 `MainActivity`。
- Java 侧：`Target.greet(String)`（`@JvmStatic`），打 log `TARGET_JAVA`。
- Native 侧：`Target.nativeCompute(Int)`，JNI，位于 `libtarget.so`
  （导出 `Java_com_bail_testtarget_Target_nativeCompute`），打 log `TARGET_NATIVE`，
  返回 `x * 2`。
- 启动后自动各调一次（不用点按钮），结果同时写屏幕与 logcat；
  界面另有两个按钮可手动再触发。

## 构建

```bash
source ~/android-sdk/rwx-env.sh
bash gradlew :test-target:assembleDebug
# 产物：test-target/build/outputs/apk/debug/test-target-debug.apk（arm64-v8a）
```

本模块不影响主 app：CI 只跑 `:app:assembleDebug`；AGP 9.0+ 内置 Kotlin
支持，所以本模块不单独应用 `org.jetbrains.kotlin.android`
（单独应用会触发 `AgpWithBuiltInKotlinAppliedCheck` 失败）。

## 真机测试

前置（手动，一次）：
1. 手机装 LSPosed；LSPFRIDA 模块启用并勾选 `com.bail.testtarget`。
2. 在 LSPFRIDA 应用里，给 `com.bail.testtarget` 设置 `test-hook.js` 的内容。

跑脚本（电脑接手机，adb 已连）：
```bash
bash test-target/test-inject.sh [lspfrifa-apk]
# 不传参数只测目标包；传模块 APK 会先重装模块。
```

脚本装目标 APK、启动应用、抓 logcat、按标记判定：
- 基线（必须有）：`TARGET_JAVA`、`TARGET_NATIVE`——目标自身的方法跑了。
- Hook（有则证明注入生效）：`HOOK_JAVA_FIRED`、`HOOK_NATIVE_FIRED`。
  没出现时只记 NOTE、不算 FAIL，因为可能是模块未启用或脚本未设置。

`test-hook.js` 做的事：
- Java：`Java.use("com.bail.testtarget.Target").greet.implementation`
  改写返回值（原串 + ` [hooked]`），打 `HOOK_JAVA_FIRED`。
- Native：轮询等 `libtarget.so` 加载（脚本跑在包就绪阶段时它可能还没加载），
  再 `Interceptor.attach` 其 JNI 导出，打 `HOOK_NATIVE_FIRED` / `HOOK_NATIVE_RET`。

## 已验证（沙盒）

- `:test-target:assembleDebug` BUILD SUCCESSFUL。
- APK 940,871 B；包名 `com.bail.testtarget`；入口 `MainActivity`；
  含 `lib/arm64-v8a/libtarget.so`，导出 `Java_com_bail_testtarget_Target_nativeCompute`。
- **真机注入未验证**：沙盒无 Android 设备、无 root，脚本与 hook 需在真机上跑。
