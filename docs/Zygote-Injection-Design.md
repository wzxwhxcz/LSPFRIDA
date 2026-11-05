# LSPFRIDA Zygote 注入（zymbiote 式）—— 第二阶段冻结设计

> 本文件是**冻结决策卷**：接口、结构体布局、线协议、环境变量、CLI、文件归属在实施前一次定死。
> 与本文冲突的临时倾向一律作废；实现完成后本文是仓库根 `DONE.md`（第二阶段）的 §0 依据。
> 权威对照基线：**当前 frida-core（commit `d5bc4840`，2026-11-04）**
> `src/linux/helpers/zymbiote.c` + `src/linux/linux-host-session.vala`（`RoboLauncher`）。
> 不是看雪 thread-289866 的 17.6.0 快照；逐条差异见 `docs/Zygote-FridaCore-Evidence.md`。

---

## 0. 一页结论：zygote 注入与 LSPosed 入口的关系

**结论：互补（complementary）。不是替代（replacement），也不是完整的 preceding（前置替代）。**

精确定义（三句话，实现期间不得改口径）：

1. **替代 = 否。** LSPosed 入口（`onModuleLoaded / onPackageLoaded / onPackageReady`）掌握
   scope 判定、启用判定、目标 classloader、脚本读取通道（remote prefs / Provider），以及
   LSPlant 改道与同步通道。zygote 门控**没有任何一项**这些能力；把它做成替代会直接推翻第一阶段。
2. **preceding = 仅"出生点"这一段。** gate payload 在 `fork()` 之后、应用代码（`RuntimeInit` /
   `ActivityThread`）之前执行，因此"进程出生即可观测 / 可暂停 / 可留下身份标记"是前置的；
   **但脚本与 hook 的注入时刻不前移**——这是 LSPosed 形态的硬约束：引擎与脚本的唯一入口是
   模块 Java 代码（最早 `onModuleLoaded`），libxposed 102 不提供 zygote 内模块回调。
   凡"脚本先于应用代码执行"的说法在本阶段一律不成立（见 §7 未做项）。
3. **互补 = 共享同一套引擎/脚本/路由状态。** gate 负责：出生时刻证明（logcat + env + socket hello）、
   可选"放行门"（hold → 由 root daemon 决定 SIGCONT）、以及"本进程是 gate 子进程"的事实；
   LSPosed 入口负责其余全部。**gate 缺失 / 注入失败 / daemon 不在 = 第一阶段行为逐字节不变（回退）。**

为什么不能照抄 frida-server 侧流程：zymbiote 的宿主是常驻 frida-server（有 spawn 请求、有
`try_resume`、由它拉起 agent）。LSPFRIDA 没有 server、没有 spawn 请求，agent 入口是 LSPosed；
照搬会在"谁来加载脚本"这一步断链。落点因此按 LSPFRIDA 形态重做（§1 映射表）。

---

## 1. 机制映射：frida-core zymbiote → LSPFRIDA 形态

| zymbiote 机制（frida-core 当前源码） | LSPFRIDA 形态 | 保持/改动 |
|---|---|---|
| 宿主 = frida-server（常驻、持有 patch、spawn gating） | 宿主 = `lspfrifa-zygote` root daemon（用户经 `su` 启动，仅 root 可写 zygote 内存） | 改：宿主进程身份 |
| 目标枚举：`zygote` / `zygote64` / `usap32` / `usap64` / `com.android.chrome_zygote` | 同前四类；chrome zygote 列为待办（§7） | v1 保持四类 |
| 落地 1：`android.os.Process.setArgV0()` 的**函数指针槽**（boot heap 里指向 `android_os_Process_setArgV0` 的指针值，memmem 定位后改写） | 同（机理完全保留：先解析 libandroid_runtime.so 导出，再在 boot.art/匿名 rw 页里 memmem 该指针值） | 保持 |
| 落地 2：libandroid_runtime.so 对 `selinux_android_setcontext` 的 **GOT/PLT 槽**改写 | 同（ELF 重定位表 r_offset + 映射基址） | 保持 |
| payload 存放：`libstagefright.so`（回退 `libmedia.so`）可执行映射**最后一页**，经 `/proc/<pid>/mem` 写入 | 同（不改：这是被当前源码验证过的稳定落点） | 保持 |
| 暂停/写入：`SIGSTOP` + `wait_until_stopped` + `/proc/pid/mem` pwrite + `SIGCONT`；**不使用 PTRACE_ATTACH** | 同（与看雪快照的 "gadget ptrace 附着" 不同，见证据文档 §KX 差异） | 保持 |
| payload → 宿主：抽象 socket `/frida-zymbiote-<uuid>`，hello {pid, ppid, name_len}+name，等 1 字节 ack | 同线协议，socket 名 `/lspfrifa-zygote-gate`（可 `--socket` 改），ack 字节 `0x42` | 改：名字与默认值 |
| ack 后 payload 自 `raise(SIGSTOP)` → 宿主 revert 该子进程的 patch → `SIGCONT` | 同（宿主 = root daemon） | 保持 |
| 子进程 gate 的"业务" = 拉起 frida-server / 等 server 就绪 | LSPFRIDA 的子进程 gate **不拉起任何东西**：只写 env 标记 + logcat + hello；引擎/脚本由 LSPosed 注入 | **改：核心差异** |
| ack 语义 = "允许 spawn 继续"（spawn gating / `try_resume`） | ack = "允许继续"；hold = daemon 暂不 ack（可 `resume <pid>` 放行），默认立即 ack | 改：无 spawn 请求 |
| 会话结束 revert zygote patch | daemon SIGINT/SIGTERM/`revert` 时 revert；`already_patched` 支持重复注入 | 保持 + 增强（state 文件） |

## 2. 三个角色

```
┌──────────────────────────────┐       抽象 socket（hello/ack）        ┌───────────────────────────┐
│ lspfrifa-zygote (root daemon)│◄──────────────────────────────────────│ gate payload（zygote 子进程）│
│  · 注入/撤销 zygote patch     │                                       │  在 setArgV0/setcontext 处  │
│  · 监听 hello、ack、revert 子 │──── SIGCONT（hold 放行时）───────────►│  · 写 env 标记 + logcat     │
│  · 记录 state（可 revert）    │                                       │  · ack 后自 SIGSTOP 等清理  │
└──────────────────────────────┘                                       └───────────────────────────┘
                                                                            │ fork 继承
                                                                            ▼
                                                                  ┌───────────────────────────┐
                                                                  │ 目标 App 进程              │
                                                                  │ LSPosed 注入（第一阶段链路）│
                                                                  │ 读 env → [lsp-zygote] 标记 │
                                                                  └───────────────────────────┘
```

## 3. 冻结契约（实现必须逐项一致）

### 3.1 gate payload 上下文（gate 与 injector 共用头文件）

```c
typedef struct {                       /* 顺序/宽度即 ABI，不得增删改序 */
  char      socket_path[64];           /* 抽象 socket 名，模板里是 32 个 '0' 占位 */
  void *    payload_base;
  size_t    payload_size;
  size_t    payload_original_protection;
  char *    process_name;
  int    (*original_setcontext)(uid_t uid, bool is_system_server,
                                const char * seinfo, const char * name);
  void   (*original_set_argv0)(JNIEnv * env, jobject clazz, jstring name);
  int    (*mprotect)(void *, size_t, int);
  char * (*strdup)(const char *);
  void   (*free)(void *);
  int    (*socket)(int, int, int);
  int    (*connect)(int, const struct sockaddr *, socklen_t);
  int *  (*__errno)(void);
  pid_t  (*getpid)(void);
  pid_t  (*getppid)(void);
  ssize_t(*sendmsg)(int, const struct msghdr *, int);
  ssize_t(*recv)(int, void *, size_t, int);
  int    (*close)(int);
  int    (*raise)(int);
  int    (*setenv)(const char *, const char *, int);
  int    (*log_write)(int prio, const char * tag, const char * text);  /* 可为 NULL */
} LspGateContext;

extern LspGateContext lsp_gate;        /* 模板里以 socket_path 占位串 memmem 定位 */
```

- 导出符号（`visibility("default")`，均放 `.text.entrypoint` 段）：
  `lsp_gate_replacement_setcontext`、`lsp_gate_replacement_setargv0`。
- injector 用 **解析 gate ELF 动态符号** 得到两者 vaddr，运行时地址 = `payload_base + (vaddr - text.vm_address)`（与 frida `make_zymbiote_payload` 同算法）。
- 指针宽度只支持 64 位（arm64 / x86_64）；32 位明确报错退出（第二阶段不做 32 位）。

### 3.2 线协议（与 frida `ZymbioteConnection.read_hello` 一致）

- 抽象 Unix socket；payload 端 `sun_path[0]='\0'`，其后为名字（无结尾 NUL 计入 addrlen）。
- hello：12 字节**本机字节序** `{u32 pid; u32 ppid; u32 process_name_len}` + `process_name_len` 字节进程名（无 NUL）。
- ack：1 字节 `0x42`。daemon 在 hold 策略下**暂不 ack**（payload 在 `recv` 上阻塞）。
- 子进程清理：payload 收到 ack 后 `raise(SIGSTOP)`（tail-call 汇编，arm64/x86_64 两份），
  daemon 轮询 `/proc/<pid>/stat` 状态 `T` → 打开 `/proc/<pid>/mem` 还原该子进程的 patch → `SIGCONT`。

### 3.3 gate 在子进程做的四件事（顺序冻结）

1. 调用 `original_set_argv0`（保持"先执行原逻辑"与 frida 一致）；
2. 解析进程名：`process_name != NULL` 用 `strdup` 的副本，否则 `GetStringUTFChars`（Android 上 libselinux 存在时恒为前者）；
3. **写环境变量（无论 daemon 是否可达，必须写）** + logcat：
   - `LSPFRIDA_ZYGOTE_GATE=1`
   - `LSPFRIDA_ZYGOTE_GATE_NAME=<socket 名>`
   - `LSPFRIDA_ZYGOTE_GATE_PPID=<ppid 十进制>`
   - `LSPFRIDA_ZYGOTE_GATE_PROC=<完整进程名，可含 :suffix>`
   - logcat：tag `LSPFRIFA-Zygote`，text `GATE_HIT pid=… ppid=… name=… host=… ack=0|1`
     （`log_write==NULL` 时跳过；host 测试无 liblog）；
4. best-effort 握手：连接失败/短读 → 不 SIGSTOP、正常返回（**fail-open，不许阻塞应用启动**）；
   ack 成功 → `revert_now=true` → 还原 payload 页权限 → tail-call `raise(SIGSTOP)`。

> 与 frida 的显式偏差：frida 的 `revert_now` 只由 ack 决定、且无 env/logcat 概念；
> LSPFRIDA 需要"daemon 不在也能证明 gate 跑过"，故 env/logcat 无条件写。记入 DONE 偏差表。

### 3.4 CLI（`lspfrifa-zygote`，arm64 与 x86_64 双构建）

```
lspfrifa-zygote targets
lspfrifa-zygote inject  [--pid N | --all] [--socket NAME] [--gate PATH] [--serve]
                        [--hold PKG,...]
                        [--libc-match PAT] [--runtime-match PAT] [--selinux-match PAT]
                        [--payload-match PAT] [--state DIR]
lspfrifa-zygote serve   [--socket NAME] [--hold PKG,...] [--state DIR]
lspfrifa-zygote revert  [--pid N | --all] [--state DIR]
lspfrifa-zygote resume  <pid>                 # 放行被 hold 的子进程
lspfrifa-zygote self-test
```

- 默认 `--gate` = 可执行文件同目录下的 `lspfrifa-gate.elf`。
- 默认 match：libc `/libc.so`、runtime `/libandroid_runtime.so`、selinux `/libselinux.so`、
  payload `/libstagefright.so`（fallback `/libmedia.so`）——default 面向 Android；host 测试用参数覆盖。
- `serve` 每收到一个 hello 向 stdout 打一行 JSON（测试与真机诊断共用）：
  `{"event":"gate_hit","pid":…,"ppid":…,"name":"…","ack":true|false,"held":bool,"reverted":bool}`
- state：`<state>/<pid>.patch`，文本可解析（payload base/len/orig-prot、slot 地址+原字节），
  用于 daemon 被 SIGKILL 后 `revert`；`already_patched` 时重注入走覆盖路径。
- 退出码：0 成功 / 2 用法 / 3 未找到目标 / 4 布局不支持 / 5 权限不足。

### 3.5 模块侧（Kotlin，仅追加，不改第一阶段链路）

- 新文件 `app/src/main/kotlin/com/bail/lspfrifa/xposed/ZygoteGate.kt`：
  `Info(hit, name, ppid, proc)`、`probe(env = System::getenv)`、`toJson()`（手写转义，不依赖 JSON 库）。
- `LSPFRIFAModule.onModuleLoaded`：`probe()` → `record(INFO/DEBUG, "event=zygote_gate_hit …" /
  "event=zygote_gate_absent")`；把 JSON 交给 `GumJsBridge.setZygoteGateJson(json)`。
- `GumJsBridge.loadScript`：在 `LSP_SHIM_BASE` **之前**追加一行
  `globalThis.__lspZygoteGate = <json 或 null>;`（固定出现，脚本可判定）。
- 其它（同步通道、LSPlant 改道、RouteB 兜底、注入顺序）**零改动**。

### 3.6 JS 侧（tools/javabridge）

- `entry.js`：读取 `globalThis.__lspZygoteGate`（坏 JSON → null，不抛）→ 传给 `adapter.install({ zygoteGate })`。
- `adapter.js`：`install()` 在入参 `zygoteGate` 合法时于返回 info 里加 `zygote: {hit,name,ppid,proc}`；
  非法/缺失 → 不加字段（既有 `mode/version` 语义不变）。
- `test/adapter.test.mjs`：新增 ≥3 条（合法 / 缺失 / 坏 JSON），`npm test` 只增不减。
- 重新生成 `JavaBridgeBundle.kt`（`node tools/javabridge/build.mjs`），`verify-upstream.mjs` 仍 PASS。

## 4. 文件归属（写作用域，互不重叠）

| 归属 | 路径 |
|---|---|
| native（A） | `tools/zygote/**`（src/build.sh/test.sh/tests/out，out 不入库） |
| kotlin+js（B） | `app/src/main/kotlin/com/bail/lspfrifa/xposed/{ZygoteGate.kt(新),GumJsBridge.kt,LSPFRIFAModule.kt}`、`app/src/main/kotlin/com/bail/lspfrifa/xposed/JavaBridgeBundle.kt`(生成)、`tools/javabridge/{entry.js,adapter.js,test/adapter.test.mjs,BRIDGE-CONTRACT.md}` |
| 证据（D） | `docs/Zygote-FridaCore-Evidence.md` |
| Lead | `docs/Zygote-Injection-Design.md`、`DONE.md`（第二阶段；第一阶段证据在 `DONE-phase1.md`） |

**app/src/main/cpp/** 与 CMakeLists 本阶段零改动**（gate/injector 由 `tools/zygote/build.sh` 独立构建，
不进 AGP，避免动第一阶段的真编链）。这是刻意的：assembleDebug 的输入变化仅限 Kotlin/JS。

## 5. 验证计划（缺一不可，写进 DONE）

1. `bash tools/zygote/test.sh`：host x86_64 组件测试 + **mock-zygote 端到端**：
   mock zygote 进程（dlopen 名为 `libstagefright.so` 的 mock 库 + 名为 `libandroid_runtime.so` 的
   mock 库，导出 mangled `_Z27android_os_Process_setArgV0P7_JNIEnvP8_jobjectP8_jstring`，GOT 导入
   mock `libselinux.so` 的 `selinux_android_setcontext`，指针存 malloc 页）
   → injector 真实 patch → fork 子进程 → 子进程调 setcontext/setArgV0 → gate 真的跑起来 → hello/ack
   → 子进程 SIGSTOP → daemon revert 子进程 → SIGCONT → 断言：env 标记、原函数被调用、指针已还原、
   daemon 收到正确 pid/ppid/name；第二场景：无 daemon → fail-open（无 SIGSTOP、进程继续）。
2. `bash tools/zygote/build.sh --android`：NDK 28 arm64 产出 gate/injector，`file` 证明 aarch64 PIE；
   `readelf -s` 证明两个 entrypoint 符号存在、gate 无 libc 动态依赖。
3. `cd tools/javabridge && node build.mjs && node verify-upstream.mjs && npm test`：verify PASS，tests ≥21/0。
4. `bash tools/check-kt.sh` 全绿。
5. `./gradlew :app:assembleDebug` BUILD SUCCESSFUL，APK 含 `lib/arm64-v8a/libgumjs_bridge.so`（大小/sha256）。
6. `git status --short` diff 清单；**不 commit、不 push**。

## 6. 回退与安全

- 一切新增路径 fail-open：daemon 不在 → gate 只写标记；gate 未注入 → 模块 `zygote_gate_absent`；
  任何一步失败 → 第一阶段行为不变（这是"互补"的可验证定义）。
- daemon 崩溃残留 patch：重新 `inject` 能识别 `already_patched` 并覆盖；`revert` 从 state 还原；
  文档给"最坏情况重启 zygote/手机"的兜底。
- 本阶段不改用户脚本分发/持久化/UI/熔断链路；不加 manager 常驻 socket（避免新攻击面）。

## 7. 明确不做 / 待办（不夸大）

1. **脚本先于应用代码**：LSPosed 形态下做不到（无 zygote 内模块回调）；本阶段只做出生点门控与观测。
2. hold/放行门的**真机时序**未验证（沙盒无设备）；机制代码与 host E2E 覆盖，真机列入清单。
3. chrome zygote（`com.android.chrome_zygote`）的 patch 传播（frida 的 ppid→pid 继承分支）未实现。
4. 32 位 zygote/ABI、Android 17/API 37 的 setArgV0 槽位置差异：未验证，失败即 fail-open 报错。
5. 39 MB libgumjs_bridge.so 的"出生即加载"（gate 内 dlopen）未做：SELinux/加载器风险未评估，
   留待真机验证后再定（本阶段 gate 不 dlopen 任何库）。
