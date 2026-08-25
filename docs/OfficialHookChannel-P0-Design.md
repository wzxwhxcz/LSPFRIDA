# 官方通道 P0 — JS hook 转接 libxposed hook()（LSPlant）

状态：**已实现（未编译验证）** — 2026-08-24
主线：修复 frida-java-bridge `Java.use().implementation` 在 Android 15 / ART 35 静默失效。

---

## 一、背景与根因（第一性原理）

frida-java-bridge 的 `implementation` 实现 = **ArtMethod entry point 替换**（写
ArtMethod 的 entry_from_quick_compiled_code / 用 JVMTI 探测失败后走反射分支），
在 ART 35（Android 15）上：
- 上层探测链复杂（JVMTI `tryGetEnvJvmti` → art_api → JNI 反射三分支），任一分支
  探测不符合预期即退化，本设备已实证 `$borrowClassHandle of undefined` 崩溃
  （GC/JNI 引用问题）与 implementation 挂上不触发（上游 frida-java-bridge#334 open）。
- 它绕开了 ART 高版本对"修改方法入口"的防护细节（Nterp、inline、GC StackVisitor）。

而 **libxposed `hook()` = LSPosed 官方 LSPlant 引擎**：
- art_method.cc：Android 5.0~16 全版本 ArtMethod 动态偏移探测（APEX 去符号后仍可用）
- trampoline.cc：汇编级 JNI Trampoline + Nterp 解释器绕过
- GC art::StackVisitor 栈遍历防崩溃防护
- 本模块 hook `Instrumentation.callApplicationOnCreate` 已在真机正常触发 →
  **官方通道在本设备可用性已被隐式验证**（framework 类 hook OK）。

结论：Java 层业务 hook 一律转接 libxposed hook()，GumJS 只保留 JS 业务层与
进程内消息转发 → 彻底绕开 frida-java-bridge 的脆弱面。此即算法助手同构机制
（JavaHookCallback → HookRouter → XposedInterface.hook）与本用户给出的工业级
路线（LSPosed 宿主生命周期 + LSPlant 引擎 + GumJS 业务层）的融合。

## 二、链路

```
JS 脚本:  LSP.hook("android.app.Activity", "onResume", "a15")
                │  (shim: send({t:"lsp.hook", cls, method, tag}))
                ▼
GumJS send → gumjs_bridge.cpp on_message → GumJsBridge.dispatchMessage
                │
                ▼
TargetIpcServer.onScriptMessage
  ├─ hookRouter.tryHandle(msg) == true → 消费（不回传原始 JSON 给 UI）
  │     └─ HookRouter: 解析 → Class.forName(cls, false, targetLoader)
  │           → clazz.declaredMethods 按名（含全部重载）
  │           → hook(executable).intercept{ ...; chain.proceed() }   ← LSPlant
  │           → 命中: hostLog("[lsp-hook] HIT cls#method args=...")
  └─ false → 原样上行（console.log/send 业务消息不受影响）
```

日志回传：`ipcServer.hostLog()` → 本地 logcat + 宿主 UI（复用 ILogReceiver.onLog）。

## 三、代码变更（4 个文件）

| 文件 | 变更 |
|------|------|
| `xposed/HookRouter.kt` | **新增**：消息解析（Frida 协议 type=send/payload.t=lsp.hook）+ Class.forName + declared 方法枚举 + hooker.invoke(m).intercept + HIT/ARMED/MISS/ARM_FAIL 日志；key=cls#method#tag 去重 |
| `xposed/GumJsBridge.kt` | loadScript 注入顺序改为 `LSP_SHIM + bundle + 用户脚本`；新增 LSP_SHIM（`LSP.hook`，仅依赖内建 send） |
| `xposed/TargetIpcServer.kt` | onScriptMessage 先过 `hookRouter.tryHandle`（消费则 return）；新增 `setHookRouter()` 与 `hostLog()` |
| `xposed/LSPFRIFAModule.kt` | runInitChain 组装：`HookRouter(targetPackage, app.classLoader, hooker={hook(it)}, hostLog=ipcServer::hostLog)` + `setHookRouter` |

无 Native 改动；无 manifest/module.prop 改动；`minApiVersion=82` 不变。

## 四、验证（用户侧）

### 4.1 编译
AndroidIDE 终端：`sh gradlew --no-daemon :app:assembleDebug`，安装后在 LSPosed
勾选目标应用（必须 ≥1 个作用域），强停目标 app。

### 4.2 验证脚本（详情页"运行脚本"粘贴）

```js
// P0 官方通道验证：framework 类（必然已加载，命中确定性最高）
LSP.hook("android.app.Activity", "onResume", "a15");
LSP.hook("android.widget.Toast", "show", "toast");
console.log("[verify] hook requests sent via official channel");
```

保存为初始脚本 → 强停并重开目标 app（触发 loadInitialScript）。

### 4.3 进阶（App 自有类，可选）
```js
LSP.hook("com.example.target.MainActivity", "onCreate", "app"); // 类加载后重新运行此脚本
```
类未加载时返回 `MISS class=... (not loaded ...)`；MainActivity 进入后重跑脚本即可 ARMED。

### 4.4 验收判据（看模块日志 / 详情页日志流）

| 日志 | 含义 | 结论 |
|------|------|------|
| `[lsp-hook] ARMED android.app.Activity#onResume overloads=1 tag=a15` | 消息链通 + 方法已挂 | 通道成立（JS→Kotlin→libxposed） |
| `[lsp-hook] HIT android.app.Activity#onResume tag=a15 args=...` | hook 实际触发 | ✅ **主验证通过：LSPlant 官方通道在本机 A15 可用** |
| `[lsp-hook] ARM_FAIL ... err=...` | hook 引擎抛错 | HookFailedError 详情 → 上报 libxposed 或选 exceptionMode |
| `[lsp-hook] MISS class=...` | 类未加载 | 换已加载类（framework 类不应出现） |
| ARMED 有、HIT 无 | 挂上未触发 | ① 测试动作没到该方法 ② 方法被 inline（**下一步 `deoptimize`**） |

### 4.5 预期与后续
- 若 HIT 出现：A15 主线问题**在官方通道上闭环**，进入 P1（onEnter/onExit 回调回 JS，
  需 host→JS 消息通道：native `script_post`/rpc 或反查缓冲）。
- 若 HIT 始终不出现：LSPlant 在本 ROM 异常 → 依次试 `deoptimize(executable)`、
  `setExceptionMode(PASSTHROUGH)`、对照 LSPilot 同款 hook 写法（它 hook 了哪些方法）。

## 五、风险与边界

优势：
- Java hook 引擎换成官方验证量最大的 LSPlant（A12~A16 工业级），避开自研 ArtMethod 操作
- 代码量 ~120 行，无 Native/无新依赖，与现有 IPC/日志链完全复用
- 失败时可观测：ARMED/HIT/ARM_FAIL 三态日志，不再"静默"

劣势/风险：
- **类必须已加载**才能挂（LSPlant 无自动延迟挂）→ 需要业务方在类加载后重跑脚本；
  P1 需补"ClassLoader 感知加载时机"（如 hook `ClassLoader.loadClass` 或轮询）
- 仅 before 语义天然支持；after/返回值修改需 P1 桥
- intercept 抛异常默认 PROTECTIVE 吞掉 → 业务逻辑错误不易暴露（P1 可配 exceptionMode）
- 未编译验证：Kotlin 签名（chain.executable/getArgs）与 SAM 转换若与本地 api 102
  版本有出入，编译由用户侧暴露，属预期流程

置信度评估：
- 官方通道在本设备可用（framework 类）：**高**（Instrumentation hook 已真机触发 + LSPlant 官方覆盖 A15）
- P0 链路一次编译通过：**中**（依赖 libxposed Hooker/Chain Kotlin 调用细节；已有 LSPFRIFAModule 同款调用先例，主要风险是 getDeclaredMethods 过滤与重载枚举边际）
- HIT 出现：**中高**（ARMED 概率高；HIT 取决于目标方法是否解释器/JIT 执行，LSPlant 对此有 trampoline 兜底，但仍以实测为准）
