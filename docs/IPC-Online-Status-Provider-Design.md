# IPC 在线状态恢复 + Provider 可达性设计说明（P1）

> 关联任务：P1 在线进程=0；uitest 进程 Unknown authority 排查。
> 日期：2026-08-23。约束遵守：GumJsBridge 零改动、架构不变、目标进程侧改动最小化（仅
> `LSPFRIFAModule` + `TargetIpcServer` 两个文件）。

## A. 在线进程=0（宿主重启后 activeTargets 清空）

### 根因

`IpcManager.activeTargets` 是宿主 App 进程内的内存注册表，仅在目标进程调用 Provider
`register_ipc` 时写入（`ScriptConfigProvider.call` → `IpcManager.registerTarget`）。
宿主 App 进程重启后该表必然为空；而目标进程持有的 Binder 是与**旧宿主进程**建立的
跨进程通道，宿主重启即失效，无任何残留句柄可恢复——**Binder 无法随进程重启存活**。

### 方案评估（诚实结论）

| 方案 | 可行性 | 结论 |
|---|---|---|
| a) 宿主启动时 ping 探测恢复 | ❌ 不可行 | 宿主重启后没有任何办法触达"已运行中"的目标进程：旧的 Binder 死亡，新的宿主与目标之间没有通道。ping 只能探测**已注册**的连接，探测不到"未注册但活着"的进程。目标进程若在宿主重启**之后**冷启动，本就会走 Provider 重新注册（现有逻辑已覆盖），无需宿主侧探测。 |
| b) 目标侧感知宿主死亡并重新注册 | ✅ 可行（本轮落地） | 目标进程监听宿主侧 `ILogReceiver` binder 的死亡（`linkToDeath` 对跨进程 proxy 有效），宿主死亡后主动再次 `contentResolver.call(register_ipc)`。宿主未运行时该调用会由 AMS 拉起宿主进程（force-stop 状态除外），重新完成注册 → 宿主 `activeTargets` 恢复。这也是唯一能覆盖"目标进程跨宿主重启存活"场景的方案。 |
| c) 清理链路+UI 刷新 | ✅ 落地（与 b 配套） | 核对现有链路：目标死亡 → `binderDied` 移除；ping/push/stop 的 `RemoteException` → 移除。链路本身正确，本轮加固了两处：`linkToDeath` 异常兜底、注册失败时 `unlinkToDeath` 防泄漏；并把 Dashboard 在线进程数改为 `onResume` 刷新的 Compose 状态、详情页连接状态改为 3s 轮询，让 UI 如实反映（含 b 的异步恢复结果）。 |

### 改动清单

- `ipc/IpcManager.kt`：`registerTarget` 死亡监听兜底（linkToDeath 异常不再外抛、注册失败 unlink）。
- `xposed/TargetIpcServer.kt`：新增宿主死亡监听与重注册（`registerLogReceiver` 内
  `linkToDeath`，死亡后后台线程重试 `register_ipc`，8 次 × 3s，daemon 线程，防重入）。
  构造参数新增 `appContext: Context?`（重注册需要 contentResolver）；**GumJsBridge 调用链未动**。
- `xposed/LSPFRIFAModule.kt`：构造 `TargetIpcServer(targetPackage, app)`；初始化链抽取为
  `runInitChain`（B 部分复用）。
- `MainActivity.kt`：`onlineProcessCount` 状态 + `onResume` 刷新（替代一次性取值）。
- `ui/screen/ProjectDetailScreenV093.kt`：连接状态轮询（3s）。

### 边界与限制（如实说明）

- 目标进程在宿主 **force-stop**（如开发期 `adb install -r` 后未打开过宿主）期间无法把宿主拉起
  ——重注册会失败并记日志，需用户打开宿主 App 触发恢复；这是 Android 进程模型限制，代码无法绕过。
- 宿主重注册成功仅恢复注册表与日志通道；目标进程内已加载的 GumJS 脚本**不受影响**（引擎在
  目标进程内，脚本状态与宿主无关）。
- 目标侧恢复线程与重注册调用失败只写 logcat，不影响目标进程本身与 GumJS 运行。

## B. uitest 进程 Unknown authority 排查

### 事实核查（读码结论）

- manifest：`ScriptConfigProvider` `exported="true"`、`authorities="${applicationId}.config_provider"`、
  `multiprocess="false"`，无权限声明。`app/build.gradle.kts` **无** `applicationIdSuffix`，
  实际 authority = `com.bail.lspfrifa.config_provider`，与模块硬编码
  `PROVIDER_URI = "content://com.bail.lspfrifa.config_provider"` **一致**（排除 authority 拼写/变体问题）。
- 调用路径：目标进程 Application 创建 → `contentResolver.call(uri, "is_target_enabled")`；
  失败时（异常/无 Bundle）旧代码在注释写明"不能当作开关未开处理"**但仍返回 false** →
  `target_check_deferred` → **跳过注入**，且目标进程生命周期内不再重试——这是实际的功能性 bug。

### "Unknown authority" 的机制

`ContentResolver.call` → `acquireProvider` 返回 null（AMS `getContentProvider` 给出空 holder）→
`IllegalArgumentException("Unknown authority: ...")`。含义是**该 authority 在系统侧无法解析/启动**，
不是调用侧权限问题。

### 根因假设（按置信度排序；本机无 adb/设备日志，无法实锤）

1. **宿主 Provider 未拉起 / 被阻止启动（高）**：调用发生时宿主进程不在运行。是否能把宿主
   进程启动起来受系统限制：force-stop（开发期重装/停用）与 MIUI 后台启动限制均可能导致
   AMS 直接返回 null。这与"时好时坏、com.example.application 正常/uitest 异常"的**时序性**
   现象最吻合（host 进程存活时例程成功，host 被杀/被停用期间 uitest 启动即失败）。
2. **Android 11+ 包可见性（中）**：目标 App 需声明 `<queries>`/`QUERY_ALL_PACKAGES` 才能解析
   宿主 Provider。已核对 Miuix example 的 AndroidManifest（GitHub master）**不含任何 queries 声明**，
   普通模板 App 同样不含——因此该因素**无法解释两个目标间的差异**，只能作为长期风险保留；
   且宿主侧无法通过自身 manifest 修复（可见性由**调用方**声明）。诊断手段见下。
3. **多用户 / 应用双开（中低）**：若 uitest 运行在 MIUI 双开/工作资料的另一个 user 下，
   宿主（仅 user 0 安装）的 Provider 在该 user 不可解析 → Unknown authority。需设备侧
   `ps -o USER`/`dumpsys` 验证，本轮无法获取。
4. **authority 缓存污染等（低）**：ContentResolver 缓存按 authority 键，死 holder 会被驱逐，
   概率低。

### 修复与降级（本轮落地）

- **三态启用检查**：`ENABLED / DISABLED / UNKNOWN`。Provider 无响应或抛异常 → `UNKNOWN`，
  **不再当作"未选中"直接跳过**（修正旧代码注释与行为不一致的 bug）。
- **延迟重试**：`UNKNOWN` 时后台线程每 3s 重试（最多 10 次 ≈ 30s）；重试成功（宿主已恢复
  或用户打开宿主）→ 回主线程执行完整初始化链；明确 `DISABLED` → 停止；耗尽 → 跳过并写
  `target_check_giveup` 日志（含 `resolveContentProvider` 结果、sdk、机型，用于区分
  假设 1 vs 假设 2/3：provider_resolved=false 且给出 NameNotFound/可见性日志 ⇒ 假设 2/3；
  provider 可解析但 call 失败 ⇒ 假设 1/4）。
- **Provider 侧无需调整**：exported/authority/multiprocess 均正确；`is_target_enabled`
  语义无歧义。**未**做"Provider 配置调整"（无必要且有兼容风险）。

### 事件名约定（供验证/取证）

目标侧新增/变更 logcat 事件（tag `LSPFRIFA-Hook` / `LSPFRIFA-IPC`）：

- `event=target_check_unknown`（首次失败）
- `event=target_check_retry attempt=N/10`（重试中）
- `event=target_check_recovered attempt=N`（重试成功 → 初始化链执行）
- `event=target_check_giveup provider_resolved=… sdk=… model=…`（耗尽，含诊断）
- `event=skip_not_selected`（明确未选中，正常跳过）
- `[pkg] 宿主进程死亡，尝试重新注册 binder 通道` / `宿主重注册成功 attempt=N` /
  `宿主重注册失败（已重试 8 次…）`（宿主侧恢复链路）

## 自检

- 本轮环境无 JDK/Gradle 工具链，未做编译验证；已逐文件静态核对括号配对、import 使用、
  API 可用性（`linkToDeath/unlinkToDeath` API 1+、`Bundle.putBinder` API 5+、
  `resolveContentProvider(authority, Int)` 旧签名可用）。
- 交叉引用：`TargetIpcServer` 唯一构造调用点已同步传参；`isTargetEnabled`（模块内旧函数）
  已删除，无残留引用；`IpcManager` 改动未触碰 t1 新增的 `LogStore` 调用。
- **t1 兼容性**：`logReceiverStub.onLog` 的 `LogStore.append`（持久化）+ `logListeners` 分发
  共存结构未动；`binderDied → activeTargets.remove` 与各 `RemoteException → remove` 清理路径
  全部保留（本轮仅加固 linkToDeath 异常兜底与注册失败 unlink）。**本轮未新增任何持久化**：
  无新 SharedPreferences、无新文件目录，与 LogStore 的 files/logs 目录互不干扰。
- 建议后续 reviewer 补一次编译/混淆检查（或由 AndroidIDE 构建）。

## 后续方案（本轮未做，供评估）

- 若确证假设 2（可见性）：给目标进程文档提示或（后续）hook PackageManager 兜底——超出
  "目标侧改动最小化"边界，先不实现。
- 若确证假设 3（多用户）：宿主侧改为多用户安装/文档说明，不在代码层硬修。
- （可选）Dashboard 在线进程数改为 3s 定时刷新（当前 onResume 刷新已覆盖用户主诉场景）。

## P1-a 启用开关同步（t4，本轮）

**问题**：列表页（ProjectScreenV2.kt）与详情页（ProjectDetailScreenV093.kt）的启用 Switch
只改本地 UI/调 stopScript，不写 `ScriptStore` 的 `enabled` StringSet；而模块冷启动的
`is_target_enabled` 查询以 ScriptStore 为准（ScriptStore.kt:53）→ 开关改动不生效。

**修复（宿主侧 3 处，目标侧零改动）**：
- `data/ProjectViewModel.setEnabled`：先同步 `IpcManager.enableTarget/disableTarget`
  （写 ScriptStore），停用时追加 `IpcManager.stopScript`（卸载目标进程内脚本，未在线则空操作），
  再持久化既有 `AddedProject.isEnabled`。
- `ui/screen/ProjectScreenV2.kt`：`MiuixSwitch(checked = IpcManager.isTargetEnabled(pkg))`
  ——列表 UI 状态改从 ScriptStore 读（与模块同源），不再读 `AddedProject.isEnabled`
  （该字段保留向后兼容的持久化，不参与 UI 判断）。
- `ui/screen/ProjectDetailScreenV093.kt`：`enabled` 初始值改从 `IpcManager.isTargetEnabled`
  读（原固定 true）；`onEnabledChange` 改写 ScriptStore（enable 时 enableTarget；disable 时
  disableTarget + stopScript）。
- `ScriptConfigProvider` 的 `enable_target/disable_target` 方法已存在（ScriptConfigProvider.kt:49-56），
  宿主 UI 与 Provider 同进程直接走 `IpcManager`，无需新增。
- 一致性保证：新增项目入口（MainActivity onAddProject）原有 `IpcManager.enableTarget` 保留；
  三方（列表/详情/模块）现在共享 ScriptStore 单一数据源。
