# LSPFRIFA

> Android **Xposed/LSPosed + Frida GumJS** 注入框架模块 —— 在应用内跑 Frida JavaScript 脚本，
> 语法与 [frida-java-bridge](https://github.com/frida/frida-java-bridge) 兼容（`Java.perform` / `Java.use(...).implementation`），
> 引擎复用 LSPosed 的 LSPlant（Android 15 / HyperOS 真机验证）；UI 为 Miuix（HyperOS 风格）Compose。

## ✨ 特性

### 注入与脚本
- **官方通道**：`LSP.hook()` → HookRouter → libxposed `hook()`（LSPlant 引擎）——A15 真机 ARMED/HIT 全链验证
- **Frida GumJS 17.9.3（QuickJS）**：NDK 静态 devkit 内置，零外部依赖
- **frida-java-bridge 语义（路线 B）**：
  - `Java.perform(fn)` / `Java.use("类名").方法.implementation = fn | null`
  - **观察模式**（fn 返回 `undefined` → 自动执行原方法）/ **替换返回**（返回非 `undefined` → 覆盖）
  - **async 支持**：`await this.method(新参数…)` 调原方法（参数可改写，基础类型映射）
  - **overload 精确选择**：`方法.overload('I','java.lang.String').implementation = …`
  - 透传（`return this.method(a)`）单次执行；超时/异常安全兜底（500ms → 自动执行原方法，永不卡死目标）
- 脚本自动保存 + 热更新（目标在线时运行即推）＋ 注入成功 Toast（可开关）
- 日志：实时流 + 历史持久化（按包/按天，5000 行保留）+ 级别色点 + 复制单条 + 导出全部 + 一键清除

### UI（Miuix Compose，HyperOS 风格）
- 玻璃拟态/超椭圆卡片/悬浮底栏；主题 = Miuix 三态（系统/浅/深）+ **Monet 动态壁纸色**（M3 dynamicColorScheme）+ 手选色板
- 脚本编辑器：sora-editor + **TextMate 语法高亮**（自包含 vscode Light+/Dark+ 主题）+ 撤销/重做 + 符号栏 + 自动保存
- 日志页：Logcat 风格行排版（级别色点/时间戳列/多行续行）+ 追尾滚动 + 回到底部 + 实时徽标
- 详情页：应用信息 + 连接状态轮询 + 插件开关 + 作用域申请 + 脚本/日志入口行

## 🧱 架构

```
目标 App 进程                      宿主（模块）App 进程
┌────────────────────────────┐   ┌─────────────────────────────┐
│ LSPFRIFAModule (onPackageLoaded) │  Compose UI (Miuix)          │
│  → hook Instrumentation       │   │  → ScriptStore/LogStore IPC   │
│  → GumJsBridge (QuickJS 引擎) │◄──│  → TargetIpcServer (Binder)   │
│  → LSP.hook→HookRouter→LSPlant│   │  → XposedService (激活检测)   │
└────────────────────────────┘   └─────────────────────────────┘
        ▲ IPC（ContentProvider/Binder 双通道：脚本/启用/日志/热更）
```

- `LSP.hook()` 由 JS 发消息 → HookRouter（目标进程）解析 → libxposed `hook()`（LSPlant）→ 拦截回调经 **frida:rpc 协议**回 JS（同步等待 ≤500ms，超时安全兜底）
- 注入提示/脚本/启用状态跨进程经 **remote prefs 双通道**（框架存储），冷启动/热更一致

## 📦 快速构建

**前置：Frida GumJS devkit 17.9.3（唯一手动步骤）**：

```sh
# 1. 下载 devkit（按构建 ABI 取对应包；二选一或者都要）
#    arm64:  https://github.com/frida/frida/releases/download/17.9.3/frida-gumjs-devkit-17.9.3-android-arm64.tar.xz
#    arm32:  https://github.com/frida/frida/releases/download/17.9.3/frida-gumjs-devkit-17.9.3-android-arm.tar.xz
# 2. 解压到（CMake 要求的目录结构，libfrida-gumjs.a 与 frida-gumjs.h 同级）：
#    app/src/main/cpp/frida-gumjs-devkit/<abi>/
# 3. 构建：
sh gradlew --no-daemon :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 需 JDK 17 + Android SDK（build-tools 36 / NDK 28.2）+ CMake（3.22+）。

> AndroidIDE/RV2IDE 备注：项目已配置 SDK CMake（3.22.1 目录为自包含 arm64 构建，bin 替换为 CMake 4.x 内容）+
> `cmake.dir` 修复 + `--undefined=_frida_sqlite3_initialize` 符号保留（无 GC 回收崩溃）；建议使用 **AndroidIDE 运行任务面板**构建。

## 🚀 使用

1. [LSPosed Manager](https://github.com/LSPosed/LSPosed)（2.1.1+）激活模块 → 勾选目标应用（支持动态作用域申请）
2. 打开目标应用 + 模块应用（详情页出现「已连接」）
3. 详情页 → **脚本编辑**：写入脚本 → **▶ 运行**（在线即热更；未连则保存待注入）
4. 详情页 → **日志**：实时查看 / 复制 / 导出；设置页可开关「注入提示」（注入成功在目标应用弹 Toast）

```js
// 示例：观察 Activity.onResume
Java.perform(function () {
  var A = Java.use("android.app.Activity");
  A.onResume.implementation = async function () {
    console.log("onResume");
    await this.onResume();      // 调原方法
    return undefined;           // 观察语义
  };
});

// 示例：覆盖返回 + overload 精确选择
Java.perform(function () {
  var Sys = Java.use("java.lang.System");
  Sys.currentTimeMillis.implementation = function () { return 1234567; };
  var A = Java.use("android.app.Activity");
  A.onCreate.overload("android.os.Bundle").implementation = function (b) {
    return this.onCreate(b);   // 透传（单次执行）
  };
});
```

## 📚 文档

- [交接与验证状态](docs/Handoff-2026-08-25.md)（架构/坑位库/验证矩阵）
- [路线B 事实簿](docs/RouteB-Facts.md) / [实现方案](docs/RouteB-Plan.md) / [真机验证清单](docs/RouteB-Verify-List.md)
- [UI 参考对齐](docs/UI-Reference-Comparison.md) / 日志持久化 / IPC 在线状态 / 官方通道设计等见 `docs/`

## ⚖️ 许可证

本项目代码以 **GPL-3.0** 发布（未定，可改）；依赖许可：frida-gumjs（LGPL-2.1，动态链接未修改 AAR）、sora-editor（LGPL-2.1）、LSPlant（LGPL-2.1）、tm4e（EPL-2.0）、miuix（Apache-2.0）——详见各自项目。
