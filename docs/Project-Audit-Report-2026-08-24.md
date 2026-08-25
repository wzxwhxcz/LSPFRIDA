# 项目审查报告 — LSPFRIFA（2026-08-24）

状态：**审查完成 / 修复未开始**。方法：2 个独立审查 agent（核心逻辑域 / UI+数据域）只读静态审查，Captain 复核修正；全部为静态推理，未真机复现。

---

## 1. 项目概览

**定位**：Android Xposed/LSPosed 模块（libxposed api 102，minApiVersion=82）+ NDK 静态 frida-gumjs 17.9.3（QuickJS）+ Miuix Compose 宿主 UI。目标进程注入链与状态：

```
LSPosed 框架 → LSPFRIFAModule.onPackageLoaded（过滤 critical）
  → hook(Instrumentation.callApplicationOnCreate)          [真机验证 ✅]
  → onApplicationCreated → checkTargetEnabled 三态+重试     [真机验证 ✅]
  → System.loadLibrary("gumjs_bridge") + GumJsBridge.init
  → TargetIpcServer（Binder）→ Provider register_ipc 握手   [真机验证 ✅]
  → loadInitialScript(get_script)                           [真机验证 ✅]
  → JS: LSP.hook()/LSP.toast() → HookRouter → libxposed hook()（LSPlant）[真机验证 ✅]
```

| 模块 | 文件 | 运行进程 |
|---|---|---|
| 模块入口 | xposed/LSPFRIFAModule.kt | 目标进程 |
| JS 引擎桥 | xposed/GumJsBridge.kt + cpp/gumjs_bridge.cpp（JNI 4 函数） | 目标进程 |
| 官方通道路由 | xposed/HookRouter.kt | 目标进程 |
| IPC 实体 | xposed/TargetIpcServer.kt | 目标进程 |
| Java 桥 bundle | xposed/JavaBridgeBundle.kt（40×16KB base64 + JVMTI 短路补丁） | 目标进程 |
| 宿主 IPC/持久化 | ipc/IpcManager.kt、ScriptStore.kt、LogStore.kt | 宿主 |
| Provider | provider/ScriptConfigProvider.kt | 宿主 |
| UI | ui/*（5 Screen + MiuixComponents）+ MainActivity（Pager 三页悬浮底栏） | 宿主 |

## 2. Findings 汇总（CRITICAL / MAJOR 全表）

| # | 级 | 域 | 文件:行 | 问题 | 置信度 |
|---|---|---|---|---|---|
| F1 | CRITICAL | 逻辑 | AndroidManifest.xml:34-37 + ScriptConfigProvider.kt | **Provider exported=true 无鉴权**：任意 App 可 get_script 读任意包脚本、register_ipc 传伪造 binder（可 DoS 宿主）、save_script/enable_target 给任意目标种脚本 | 高 |
| F2 | CRITICAL | UI | ProjectViewModel.kt:43-54、ProjectDetailScreenV093.kt:120-131 | **跨进程 Binder 同步调用在宿主主线程**（stopScript/loadScript/ping），目标挂起→ANR/jank | 高 |
| F3 | MAJOR | 逻辑 | LSPFRIFAModule.kt:93-122 | **初始化链跑目标主线程**：loadLibrary+gum_init+握手+拉脚本+首解 635KB base64+regex，拖慢每个启用目标启动 | 高 |
| F4 | MAJOR | 逻辑 | gumjs_bridge.cpp:90-102 | **gum_script_load_sync 返回值被忽略**：脚本加载失败仍返回 true，Kotlin loaded=true，错误被吞=诊断黑洞 | 高 |
| F5 | MAJOR | 逻辑 | LSPFRIFAModule.kt:48-74 | onPackageLoaded 只过滤 critical，**不按 is_target_enabled 过滤/不 detach 非目标包**，scope 大时每包重复装 hook | 中高 |
| F6 | MAJOR | 逻辑 | ScriptStore.kt（enable/disable） | getStringSet→toMutableSet→putStringSet **RMW 非原子**，多 binder 线程+UI 并发丢更新 | 中高 |
| F7 | MAJOR | UI | ProjectDetailScreenV093.kt:365-374 | 日志 500 条 `items` 无 key + **SelectionContainer 逐条包裹** + add→removeAt(0)，高频日志全量重组 | 高 |
| F8 | MAJOR | UI | ProjectDetailScreenV093.kt:85-109 | 历史读取(IO)与实时监听对 `mutableStateListOf` **跨线程并发写**（binder 回调线程） | 中 |
| F9 | MAJOR | UI | MainActivity/Dashboard/Detail | 首页 activeProcessCount 仅 onResume 取一次，详情 3s 轮询不同源，**在线状态可能不一致** | 中 |

MINOR（摘要）：gumjs_bridge.cpp on_message 无 ExceptionCheck/重复 GetMethodID/全局引用未加锁；JavaBridgeBundle 每加载对 480KB 跑正则（未命中即静默 no-op）；HookRouter 幂等键缺 act；构建配置 buildTools 35 vs AGP 36 告警；MainActivity 双 containerColor 冗余；SelectProjectScreen filter 无 remember；FAB 116dp vs contentPadding 128dp 口径不一；日志区硬编码色（0xFF171717/0xFF74E391）不随主题。
NIT（摘要）：LogStore "≤250ms flush" 实际靠 poll 超时（持续低频流推迟）；nativeLoadScript 错误分支可能未 unref 已创建 script。

## 3. Captain 复核修正（重要）

- **F1 修正**：agent A 建议"signature 级权限"**不可行**——目标进程是任意第三方 App（uid 不同），签名权限会直接断掉注入链（is_target_enabled/register_ipc/get_script 必须对目标 app 开放）。**正确修复（不动架构）**：
  1. `save_script`/`enable_target`（仅宿主 UI 使用）：`getCallingUid() == 宿主自身 uid` 校验，否则拒绝；
  2. `get_script`/`is_target_enabled`/`register_ipc`：**caller-arg 匹配**——`arg` 包名经 PackageManager 解析的 uid 必须等于 `getCallingUid()`（任意 App 只能读/注册"自己包名"，无法扫别人）；`register_ipc` 额外校验 `binder.getInterfaceDescriptor() == IScriptExecutor`（防伪造 Binder DoS）。
- **F2/F3 同源**（两个进程各自的"主线程做重活"），合并为"重活移出主线程"落实。
- **F9 与 F5 关联**：状态同源问题根因是 uid/装载去重缺进程级注册表，P1 一并处理。

## 4. 优化建议（P0/P1/P2）

### P0 — 安全 + 稳定性（建议下一轮直接做）
1. **F1 Provider 鉴权三件套**（caller-arg uid 匹配 + binder 描述符校验 + save/enable 限宿主 uid）——安全 CRITICAL，~30 行，不破坏注入链。置信度：高
2. **F2/F3 主线程剥离**：目标进程 runInitChain 移后台线程（observe 主线程回投）；宿主侧 Binder 调用包 `withContext(Dispatchers.IO)`。置信度：高
3. **F4 加载结果校验**：`gum_script_load_sync` 返回值检查，失败返回 JNI_FALSE + 日志（消除"已加载"假象）。置信度：高

### P1 — 功能正确性 / 性能
4. **F5 按启用过滤 + detach**（onPackageLoaded 先查 is_target_enabled，不需的包 detach；进程级去重）
5. **F6 ScriptStore 并发安全**（synchronized 块或迁 DataStore）
6. **F7/F8 日志列表**：items 加 stable key、SelectionContainer 降为单层/remember、logs 写入统一主线程
7. **F9 状态同源**：首页/详情共用同一个轮询数据源（如 IpcManager 的 activeTargets 快照协程）

### P2 — 可维护性
8. JNI：on_message ExceptionCheck/清异常、缓存 GetMethodID、g_callback 加锁；错误分支 unref
9. HookRouter 幂等键加 act；JavaBridgeBundle regex 命中失败时日志提示并避免重复跑
10. 构建配置清理（buildTools 36 或去掉显式声明）；filter remember；FAB inset 口径统一；日志色改主题 token

**执行注意**：P0 三项相互独立可分别提交；F1 完成后需回归"目标 app 冷启动→注入链"（is_target_enabled/register_ipc/get_script 未被误拒）。

## 5. 与已知问题清单的关系
- 本报告**不含**此前已验证/已知项：官方通道 P0 已验证（✅ 2026-08-24 真机 ARMED+HIT）；底栏四项、日志持久化、在线进程、开关同步等"未编译验证"项属功能回归清单，不在此审查静态发现内。
- F4 若修复，将直接改善"脚本加载失败显示为已加载"这一真实用户困惑（与 L3 无输出相关的排查体验）。

## 6. 与已完成功能的重叠性核对（防重复开发）

核对于 2026-08-24 审查当日，对照已实现/验证状态（日志持久化 LogStore、在线进程恢复（宿主死亡重注册 8×3s+详情 3s 轮询）、开关三方同步、Unknown authority 三态+10×3s 重试、日志清除）。**结论：没有任何一条优化建议是从零重复开发**；以下为【补强】【扩展】【独立】标注：

| 建议 | 关联已完成项 | 判定 | 说明 |
|---|---|---|---|
| F1 Provider 鉴权 | 无 | 【独立】新增安全项 | 与任何已有功能无交集 |
| F2 宿主主线程 Binder | 开关同步（enable/disable/stopScript/pushScript） | 【补强】 | **功能已存在**，本条只改调用线程（withContext(IO)），非功能重做 |
| F3 init 链移出主线程 | 注入链（已实现） | 【补强】 | 注入链功能已完工，本条为线程位置优化 |
| F4 load 返回值检查 | 无 | 【独立】诊断改善 | 无重叠 |
| F5 按启用过滤+detach | Unknown authority 三态（is_target_enabled 查询已实现） | 【扩展】 | 查询已有；缺的是 onPackageLoaded 前置过滤与 detach/进程级去重，**与三态逻辑部分重叠，实施时勿重写三态** |
| F6 ScriptStore RMW | 开关同步（ScriptStore） | 【补强】 | 功能已完成，本条为原子性缺陷修复（同一文件小改） |
| F7/F8 日志列表性能 | 日志持久化 LogStore + 日志清除 | 【独立】 | 持久化/清除已完工；本条是**纯展示层渲染优化**（key/SelectionContainer/线程），与 LogStore 文件无交集 |
| F9 状态同源 | 在线进程恢复 | 【补强】 | 恢复机制已实现（目标侧重注册+详情轮询）；本条是**首页 UI 未接入**该源（仅 onResume 一次），属展示层补全，勿重建恢复机制 |
| P2 各项（JNI/键/构建/色系/口径） | 无 | 【独立】 | 与已完成功能无重叠 |

**实施纪律**：P1 中 F5/F6/F9 三条必须基于**现有实现**增量修改（三态逻辑、ScriptStore、TargetIpcServer 重注册），不要另起炉灶；F2/F3 只搬迁线程位置不改变流程本身。
