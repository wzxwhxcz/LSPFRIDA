# Zygote-FridaCore-Evidence — 当前 frida-core zymbiote 注入路径的源码级证据

> 用途：第二阶段 brief §参考基线 要求「新注入路径的每一步都要有源码级证据（对照的 frida-core 文件与行号）」。
> 本文只做证据，不做设计；LSPFRIDA 侧的冻结决策见 `docs/Zygote-Injection-Design.md`。
> 本文所有结论均针对**当前 frida-core 源码树**（见 §0），**不是**看雪 thread-289866 的 frida 17.6.0 快照。

---

## 0. 基线与复现（可复核）

| 项 | 值 |
|---|---|
| 仓库 URL | `https://github.com/frida/frida-core` |
| 本地权威克隆 | `/tmp/frida-core-ref` |
| HEAD commit | `d5bc4840721b7767c2601b754dfb2b34b1763ec7`（与任务书给定期望值一致） |
| 提交日期 | `2026-11-04 12:53:53 +0100`（`git show -s --format='%ci'`） |
| 提交标题 | `subprojects: Bump outdated` |
| 取得方式 | `git clone --depth 1 --filter=blob:none https://github.com/frida/frida-core`（brief §参考基线 指定） |
| 克隆形态（实测） | `git rev-parse --is-shallow-repository` → `true`，`.git/shallow` 仅含上述 commit；`remote.origin.partialclonefilter` → `blob:none`、`remote.origin.promisor` → `true` |
| 工作区物化程度 | 工作区只物化了 `src/linux/**`（86 个文件）；其余路径未 checkout。**本文对全树的检索一律用 `git grep <pattern> HEAD`（读对象库，覆盖全树 867 个文件）或 `git show HEAD:<path>` 读取**，不依赖工作区是否物化 |
| 复核命令 | `cd /tmp/frida-core-ref && git log -1 --format='%H %ad %s'` 以及下表每行的 `nl -ba <file> \| sed -n 'X,Yp'` |

**口径声明**

1. 行号口径：`src/linux/linux-host-session.vala` 行号按该 commit 的 3338 行版本；`src/linux/helpers/zymbiote.c` 按 367 行版本；其余文件同理。表内简写 `:a-b` 表示同一文件（该行首列给出的文件）。
2. 「LSPFRIDA 对照落点」列引用的是当前工作区 `tools/zygote/{src,tests}` 与 `docs/Zygote-Injection-Design.md`（设计卷）——它们**不是** frida-core 源码，只用于说明对应关系；LSPFRIDA 侧行号不写死（并行开发中会移动），只写文件名与符号名。
3. `#if ANDROID` 事实：`RoboLauncher`、`open_process_memory`、`wait_until_stopped`、`is_process_stopped` 全部位于 `src/linux/linux-host-session.vala` 的 `#if ANDROID`（开始于 :547，结束于 :2757）之内——**整条 zymbiote 机制只存在于 Android 构建**。

---

## 1. 注入路径逐步证据表

> 共 61 行，按阶段分 7 张子表。每行：步骤 / 机制一句话 / 精确证据 / LSPFRIDA 对照落点。

### 1.1 触发、目标枚举、抽象 socket（行 1–8）

| 步骤 | 机制一句话 | 精确证据（frida-core，commit d5bc4840） | LSPFRIDA 对照落点 |
|---|---|---|---|
| 1 | 会话 **preload** 即完成全部 zygote 注入（不依赖任何 UI 交互） | `LinuxHostSession.preload` 先取 android helper 再 `robo_launcher.preload` → `RoboLauncher.preload` → `ensure_loaded`：`src/linux/linux-host-session.vala:93-98`、`:1420-1422`、`:1562-1563` | 无 `Client.preload` 概念；等价时机 = daemon `inject`/`serve` 启动时（设计卷 §3.4 CLI） |
| 2 | 目标枚举：按**进程名精确匹配** 5 类 | `System.enumerate_processes()` 后 `name == "zygote" \|\| name == "zygote64" \|\| name == "usap32" \|\| name == "usap64" \|\| name == CHROME_ZYGOTE_PACKAGE_NAME`：`src/linux/linux-host-session.vala:1610-1622`；常量 `CHROME_ZYGOTE_PACKAGE_NAME = "com.android.chrome_zygote"` 见 `:1402` | `tools/zygote/src/inject.c:207-208`（`enumerate_targets`）只认前 4 类；chrome zygote 明确未实现（设计卷 §7.3） |
| 3 | 幂等：已 patch 的 pid 直接跳过 | `if (zymbiote_patches.has_key (pid)) continue;`：`src/linux/linux-host-session.vala:1616-1617` | `tools/zygote/src/state.c`（`<state>/<pid>.patch`）负责 already_patched 判定（设计卷 §3.4/§6） |
| 4 | 每个目标并发注入，全部完成后统一 resolve/reject | `CompletionNotify` + `pending` 计数；`do_inject_zygote_agent.begin(pid, name, cancellable, on_complete)`：`src/linux/linux-host-session.vala:1595-1608`、`:1619-1637` | daemon 无 `Promise` 语义；但「一个目标失败不能拖垮其它目标」需保留 |
| 5 | **usap 瞬态失败特例**：只吞「slot 找不到」这一种错误 | `is_transient_usap_patch_failure`：`process_name.has_prefix("usap")` 且 `error is Error.NOT_SUPPORTED` 且消息**逐字**等于 `"Unable to locate android.os.Process.setArgV0() slot; please file a bug"` → 记成功：`src/linux/linux-host-session.vala:1640-1659`；消息产生处 `:1935` | 建议同语义降级为 warning（usap 可能是同一 zygote 的另一种进程名/短暂态）；退出码语义见设计卷 §3.4 |
| 6 | 抽象 socket 名 = `/frida-zymbiote-` + 32 位随机 hex | `string name = "/frida-zymbiote-" + Uuid.string_random ().replace ("-", "");`：`src/linux/linux-host-session.vala:1575-1576` | 默认 `/lspfrifa-zygote-gate`（`--socket` 可改），**不随机**（设计卷 §3.4/§3.2） |
| 7 | 抽象地址 + `bind(allow_reuse)` + `listen()` | `UnixSocketAddress.with_type (name.to_utf8 (), UnixSocketAddressType.ABSTRACT)`、`socket.bind (address, true)`、`socket.listen ()`：`src/linux/linux-host-session.vala:1577-1582` | `tools/zygote/src/wire.c` 的 `lsp_wire_listen` / `build_abstract_address`（`sun_path[0]='\0'`） |
| 8 | 连接接受循环：每连接一个独立协程 | `SocketListener.add_socket` → `accept_async` → `handle_zymbiote_connection.begin (connection)`：`src/linux/linux-host-session.vala:2092-2106`（accept 与派发 `:2097-2100`） | `serve` 主循环（每 hello 打一行 JSON，设计卷 §3.4） |

### 1.2 payload 落点与符号解析（行 9–20）

| 步骤 | 机制一句话 | 精确证据（frida-core，commit d5bc4840） | LSPFRIDA 对照落点 |
|---|---|---|---|
| 9 | 准备阶段丢到工作线程，避免阻塞 GLib 主循环 | `Task.run_in_thread` → `do_prepare_zymbiote_injection (pid_to_prep, launcher.server_name)`，结果经 `task.propagate_pointer()` 取回：`src/linux/linux-host-session.vala:1724-1746` | 无 GLib 主循环约束；`inject` 命令串行即可 |
| 10 | 读目标 `/proc/<pid>/maps`：结构化快照 + 原始文本 | `ProcMapsSnapshot.from_pid (pid)`；另 `FileUtils.get_contents ("/proc/%u/maps")` 留原文给正则：`src/linux/linux-host-session.vala:1758-1764` | `tools/zygote/src/proc_maps.c`（`lsp_maps_load`，自行解析 maps 文本） |
| 11 | payload 落点 = `libstagefright.so`（回退 `libmedia.so`）**可执行映射的最后一页** | `m.executable && (path.has_suffix("/libstagefright.so") \|\| path.has_suffix("/libmedia.so"))` → `payload_base = m.end - Gum.query_page_size()`；`payload_original_protection = PROT_READ\|PROT_EXEC`（`m.writable` 时再 `\|PROT_WRITE`）；`payload_path = path`；`payload_file_offset = m.file_offset`：`src/linux/linux-host-session.vala:1766-1775` | 保持（设计卷 §1「payload 存放」行）；`proc_maps.c:lsp_maps_find_exec_suffix` |
| 12 | libc / libselinux / libandroid_runtime 三条路径按后缀**首见即取** | 三个 `else if (path.has_suffix ("/libc.so"))` / `"/libselinux.so"` / `"/libandroid_runtime.so"`：`src/linux/linux-host-session.vala:1776-1784` | 保持但可参数化（`--libc-match` 等，设计卷 §3.4）；`proc_maps.c:lsp_maps_find_path_suffix` |
| 13 | boot heap 候选谓词 `is_boot_heap`：可读可写、非可执行、非 shared，且 path 含 4 类 ART 映像名 | `!m.readable \|\| !m.writable \|\| m.executable \|\| m.shared` 先否决；再要求 path 含 `boot.art` / `boot-framework.art` / `boot-image-methods.art` / `dalvik-LinearAlloc`：`src/linux/linux-host-session.vala:1785-1790`、`:2079-2087` | `proc_maps.c:lsp_map_is_boot_heap` |
| 14 | 再追加匿名 `rw-p 00000000` 映射为候选 | 多行正则 `/^([0-9a-f]+)-([0-9a-f]+) rw-p 00000000 00:00 0 *$/m` 匹配 raw maps，逐组解析 start/end 入候选（`assert_not_reached` 保护）：`src/linux/linux-host-session.vala:1793-1813`（`match` 断言 `:1794-1797`） | `proc_maps.c:lsp_map_is_anon_rw`（LSPFRIDA 按 flags 判定，不是对原文正则） |
| 15 | 必需项缺失 → 明确错误消息 | `"Unable to pick a payload base"`（`:1815-1816`）、`"Unable to detect libc.so path"`（`:1817-1818`）、`"Unable to detect libandroid_runtime.so path"`（`:1819-1820`）、mapping 缺失 `"Unable to detect mapping for %s"`（`:1822-1828`、`:1846-1848`）、ELF 解析失败 `"Unable to parse %s: %s"`（`:1830-1835`、`:1840-1844`、`:1859-1864`）：`src/linux/linux-host-session.vala` 对应行 | `inject.c` 对应错误 + 退出码 3/4（设计卷 §3.4） |
| 16 | `selinux_android_setcontext` 导出地址 = `libselinux` mapping.start + e.address | `libselinux.enumerate_exports` 命中即记 `original_setcontext = libselinux_mapping.start + e.address` 并停止枚举：`src/linux/linux-host-session.vala:1837-1857` | 保持；同名导出解析（设计卷 §3.1） |
| 17 | `setArgV0` 导出符号名 = **逐字 mangled** 名 → 运行时地址 | `runtime.enumerate_exports` 命中 `"_Z27android_os_Process_setArgV0P7_JNIEnvP8_jobjectP8_jstring"` → `original_setargv0 = runtime_mapping.start + e.address`：`src/linux/linux-host-session.vala:1859-1874` | `elf_parser.c:lsp_elf_find_export`，符号名用同一常量 |
| 18 | libselinux **缺失时静默跳过** setcontext 改道（不是错误） | `original_setcontext == 0` 时既不查 import 槽（`:1875`），也不写 GOT 槽（`:1691`）；即 libselinux 不存在 → 只做 setArgV0 一路：`src/linux/linux-host-session.vala:1838`、`:1875`、`:1691-1698` | 保持：gate 的 setcontext 分支变为不可达，只走 setArgV0 |
| 19 | setcontext 的 **GOT/PLT 导入槽**：`runtime_mapping.start + i.slot` | `runtime.enumerate_imports` 命中 `"selinux_android_setcontext"` 即记 `setcontext_slot` 并停止：`src/linux/linux-host-session.vala:1875-1883` | `elf_parser.c:lsp_elf_find_import_slot`（解析 `.rela.plt/.rela.dyn` 得 `r_offset`） |
| 20 | setArgV0 导出找不到 → `NOT_SUPPORTED`（usap 特例据此字符串匹配） | `"Unable to locate android.os.Process.setArgV0(); please file a bug"`：`src/linux/linux-host-session.vala:1884-1885` | fail-open / 退出码 3（设计卷 §3.4、§6） |

### 1.3 payload 模板构建（行 21–31）

| 步骤 | 机制一句话 | 精确证据（frida-core，commit d5bc4840） | LSPFRIDA 对照落点 |
|---|---|---|---|
| 21 | 每 arch 内嵌一份 zymbiote ELF blob；选择条件 = **编译时架构**（ARM/ARM64 vs x86）× **目标 libc 的 pointer_size==8** | `pointer_size = libc.pointer_size`（`:1969`，`libc` 来自目标 `/libc.so` 的解析 `:1830-1835`）；`pointer_size == 8 ? get_zymbiote_arm64_elf_blob() : get_zymbiote_arm_elf_blob()`（ARM/ARM64 构建）或 x86_64/x86 分支：`src/linux/linux-host-session.vala:1966-1978` | `tools/zygote/src/payload.c`（`lsp_payload_build`）从 `lspfrifa-gate.elf` **文件**读取，不内嵌；LSPFRIDA 明确只做 64 位（设计卷 §3.1） |
| 22 | 内嵌 blob 的来源可复核：artifact 目录 → 资源编译器 | `helpers_native_prefix = 'linux' / 'helpers' / 'artifacts' / 'native'`；把 `{x86,x86_64,arm,arm64}/zymbiote.elf` 复制为 `zymbiote-<arch>.elf` 作为 custom_target 输入：`src/meson.build:37-46`、`:49-62`；namespace `Frida.Data.Android`：`src/android.resources:1-2`；`get_<id>_blob` 生成规则（`-`/`.`→`_` 并小写）：`tools/resource-compiler.vala:428`、`:534-545` | 无资源编译链，直接读文件（设计卷 §3.4 `--gate`） |
| 23 | zymbiote ELF 的构建：`-nostdlib` + `helper.lds`，把入口/代码/rodata/data 全塞进单个 `.payload` 段 | `shared_module('zymbiote', ['zymbiote.c'], name_suffix: 'elf', link_args: ['-nostdlib', '-Wl,-T,' + helper_lds])`：`src/linux/helpers/meson.build:95-103`（flags/`helper_lds` 定义 `:36-44`）；段布局 `. = 0; .payload : ALIGN(4096) { KEEP(*(.text.entrypoint)) *(.text*) *(.rodata*) *(.data*) }`：`src/linux/helpers/helper.lds:1-19` | `tools/zygote/src/gate.lds` 同构（`.text.entrypoint` 段放两个导出） |
| 24 | 可执行段选择 = 第一个 protection 含 EXECUTE 的段（并断言存在） | `zymbiote.enumerate_segments`：`(s.protection & Gum.PageProtection.EXECUTE) != 0` 即 `text = s; return false;`：`src/linux/linux-host-session.vala:1987-1995` | `elf_parser.c:lsp_elf_text_segment`（返回 vm_address/file_offset/file_size） |
| 25 | 导出 → 运行时地址数学：`payload_base + (e.address - text.vm_address)` | 两个导出 `frida_zymbiote_replacement_setargv0` / `frida_zymbiote_replacement_setcontext` 各得一个运行时地址，`assert(setargv0 != 0)`、`assert(setcontext != 0)`：`src/linux/linux-host-session.vala:1997-2009` | `payload.c` 同算法（设计卷 §3.1 最后一条） |
| 26 | 模板字节 = blob 中该段的 `file_offset .. +file_size` 切片 | `unowned uint8[] payload_template = blob.data[text.file_offset : text.file_offset + text.file_size];`：`src/linux/linux-host-session.vala:2011` | `payload.c` 读 gate ELF 的同一段 |
| 27 | memmem 找 **48 字节** socket 占位串，得 `data_offset`（`assert` 保护） | `memmem (payload_template, "/frida-zymbiote-00000000000000000000000000000000".data)`；`data_offset = (uint8*) p - (uint8*) payload_template`：`src/linux/linux-host-session.vala:2013-2015`。注意：占位串是 16+32=**48 字符**，而字段本身是 **64B**（`char socket_path[64]`：`src/linux/helpers/zymbiote.c:13`；初值 `:38-41`） | `payload.c:lsp_memmem` + `LSP_GATE_SOCKET_TEMPLATE`（`tools/zygote/src/gate_context.h`，同样 16+32=48B、字段 64B，`LSP_GATE_SOCKET_FIELD_SIZE 64`） |
| 28 | 写字段 1：`server_name` 写进 64B 字段（**不校验长度**） | `payload.write_string (cursor, server_name); cursor += 64;`：`src/linux/linux-host-session.vala:2020-2021` | `--socket` 名必须 ≤63B（见 §4 风险 9） |
| 29 | 写字段 2/3/4：`payload_base`、`payload_size`（= `payload_template.length`）、`payload_original_protection` | 三次 `write_pointer` + `cursor += pointer_size`：`src/linux/linux-host-session.vala:2023-2030` | 前 19 个字段偏移与 frida 完全一致（`gate_context.h` 的 `LSP_GATE_OFF` 表 0…200），可逐字段对照 |
| 30 | **跳过 1 个指针**（`process_name`），再写 `original_setcontext`、`original_set_argv0` | `cursor += pointer_size;`（空推进，无注释）然后两次 `write_pointer`：`src/linux/linux-host-session.vala:2032-2038` | 同：`process_name` 由 gate 运行时 `strdup` 填（`zymbiote.c:68-72`） |
| 31 | 12 个 libc 函数按 `wanted[]` **固定顺序**写运行时地址 | wanted = `{mprotect, strdup, free, socket, connect, __errno, getpid, getppid, sendmsg, recv, close, raise}`；`addrs[idx] = libc_mapping.start + e.address`；逐个 `assert(addrs[i] != 0)` 后 `write_pointer`：`src/linux/linux-host-session.vala:2040-2076` | 设计卷 §3.1 结构体尾部顺序必须逐字段一致（gate 端 C 结构体顺序即 ABI） |

### 1.4 槽定位、写入与 patch 记录（行 32–41）

| 步骤 | 机制一句话 | 精确证据（frida-core，commit d5bc4840） | LSPFRIDA 对照落点 |
|---|---|---|---|
| 32 | 指针值按 **HOST 字节序** 打成 `pointer_size` 字节数组，供 memmem 使用 | 四个 `uint8[pointer_size]` 缓冲（original/replaced × setargv0/setcontext）+ `Buffer.write_pointer`：`src/linux/linux-host-session.vala:1896-1908` | `inject.c` 内联同样的字节化逻辑 |
| 33 | 打开 `/proc/<pid>/mem`（`O_RDWR`），失败给 `PERMISSION_DENIED` | `open_process_memory`：`Posix.open ("/proc/%u/mem".printf (pid), Posix.O_RDWR)`；失败 `throw new Error.PERMISSION_DENIED ("%s", strerror (errno))`：`src/linux/linux-host-session.vala:2691-2696`（调用点 `:1894`） | `inject.c:proc_mem_open`（同样只用 `/proc/pid/mem`，无 ptrace） |
| 34 | 槽定位：逐候选**整段** `pread`，在字节流里 memmem `original_setargv0` 指针值 | `heap = new uint8[candidate.size]`；`n = fd.pread (heap, candidate.start)`，`n != heap.length` 则 `continue`；`memmem (heap, original_setargv0_ptr)` → `setargv0_slot = candidate.start + ((uint8*) p - (uint8*) heap)`：`src/linux/linux-host-session.vala:1910-1932` | `inject.c` 同路（设计卷 §1「落地 1」） |
| 35 | 若原指针值找不到，再 memmem **替换后**的指针值 → 置 `already_patched` | `if (p == null) { p = memmem (heap, replaced_setargv0_ptr); already_patched = p != null; }`：`src/linux/linux-host-session.vala:1923-1927` | `state.c` 的 already_patched 判定 |
| 36 | 槽找不到 → `NOT_SUPPORTED`（与 usap 特例字符串一致） | `"Unable to locate android.os.Process.setArgV0() slot; please file a bug"`：`src/linux/linux-host-session.vala:1934-1935` | fail-open / 退出码 3 |
| 37 | 暂停：`SIGSTOP` + `wait_until_stopped`（5s 超时、退避轮询 `/proc/<pid>/stat` 的 `'T'`） | `Posix.kill (pid, Posix.Signal.STOP)`（`:1664`）；`wait_until_stopped`：`TimeoutSource.seconds (5)`、`delays = {0,1,2,5,10,20,50,250}`、`is_process_stopped` 读 `state == 'T'`：`src/linux/linux-host-session.vala:1665`、`:2698-2737`、`:2739-2756` | `inject.c:wait_until_stopped` + `read_proc_stat_state` |
| 38 | 写 payload 页；`already_patched` 时「原字节」改从 **backing file** 读 | `patches.apply (payload, prep.process_memory, prep.payload_base)`；already_patched 分支：`Posix.open (prep.payload_path, O_RDONLY)` → `backing_file.pread_all (original, prep.payload_file_offset)` → `apply(..., new Bytes.take(original))`：`src/linux/linux-host-session.vala:1669-1682`（文件读 `:1671-1678`；报错 `"Unable to open payload backing file: %s"` `:1673-1674`） | `payload.c` + `state.c`（LSPFRIDA 用 state 文件记录原字节） |
| 39 | 写 setArgV0 槽；`setcontext_slot != 0` 时才写 setcontext GOT 槽 | 两次 `patches.apply (replaced_*_ptr, prep.process_memory, slot[, original])`：`src/linux/linux-host-session.vala:1684-1698` | `inject.c` 同（setcontext 可选） |
| 40 | 记录 patch 集并 `SIGCONT`（`finally` 保证一定恢复运行） | `zymbiote_patches[pid] = patches;` + `finally { Posix.kill (pid, Posix.Signal.CONT); }`：`src/linux/linux-host-session.vala:1700-1703` | `state.c:lsp_state_save` + 同样的 finally 恢复 |
| 41 | `ZymbiotePatches`：写前读原字节、按序记录 entry、revert 按记录写回 | `apply`：`orig == null` 时 `fd.pread (buf, address)`；`fd.pwrite (patch, address)`；`entries.offer ({address, original})`。`revert`：逐 entry `fd.pwrite (e.original.get_data (), e.address)`：`src/linux/linux-host-session.vala:2152-2181`（apply `:2160-2175`，revert `:2177-2180`） | `state.c`（额外持久化，daemon 崩溃后可 revert；设计卷 §3.4/§6） |

### 1.5 gate payload 行为（`src/linux/helpers/zymbiote.c`，行 42–48）

| 步骤 | 机制一句话 | 精确证据（frida-core，commit d5bc4840） | LSPFRIDA 对照落点 |
|---|---|---|---|
| 42 | 两个替换函数都放 `.text.entrypoint` 段 + `visibility("default")`（供 injector 从动态符号表找到） | `__attribute__((section(".text.entrypoint")))` / `__attribute__((visibility("default")))`：`src/linux/helpers/zymbiote.c:57-58`（setcontext）、`:77-78`（setargv0） | `gate.c` 两个 `lsp_gate_replacement_*` + `gate.lds` |
| 43 | **setcontext 替换**：先调原函数；仅当 `process_name == NULL` 时把 payload 页改 RWX 并 `strdup(name)` 缓存名字 | 原函数返回 -1 时直接 `return -1`（`:64-66`）；`if (zymbiote.process_name == NULL) { mprotect(payload_base, payload_size, R\|W\|X); process_name = strdup(name); }`（`:68-72`）；`return res`（`:74`） | `gate.c:lsp_gate_replacement_setcontext`（LSPFRIDA 另加 env/logcat 标记，设计卷 §3.3） |
| 44 | **setargv0 替换**：先调原函数 → 取名 → 握手 → 释放/恢复页权限 → 需要时 **tail-call `raise(SIGSTOP)`** | `original_set_argv0 (env, clazz, name)`（`:85`）；`process_name != NULL` 用缓存名，否则 `GetStringUTFChars`（`:87-90`）；握手（`:92`）；free + `mprotect(payload_original_protection)`（`:94-99`）或 `ReleaseStringUTFChars`（`:102`）；`if (revert_now) { musttail return frida_stop_and_return_from_setargv0(...); }`（`:105-109`）；否则 `return 0`（`:111`） | `gate.c:lsp_gate_replacement_setargv0` |
| 45 | 握手线协议：抽象 socket connect → 12B hello `{u32 pid, u32 ppid, u32 name_len}` + name（无 NUL）→ 等 **1 字节 ack** → `revert_now=true` | `socket(AF_UNIX, SOCK_STREAM, 0)`（`:124`）；`sun_path[0]='\0'`（`:128-129`）；名字逐字节拷贝并做两处边界检查（`:131-142`）；`addrlen = offsetof(struct sockaddr_un, sun_path) + 1 + name_len`（`:144`）；`connect`（`:146`）；header/iovec/`sendmsg_all(..., MSG_NOSIGNAL)`（`:149-173`）；`recv(fd, &rx, 1, 0) != 1` 即失败（`:175-180`）；`*revert_now = true`（`:182`）。**注意：不校验 ack 字节的值** | `gate.c` + `wire.c:lsp_wire_write_hello` / `lsp_wire_send_ack`（ack 固定 `0x42`，设计卷 §3.2） |
| 46 | fail-open：连不上/短读/被拒 → 只 `goto beach` 关 fd，`revert_now` 保持 false，进程继续跑 | `beach: if (fd != -1) zymbiote.close (fd);`（`:184-186`）；所有错误分支（`:125-126`、`:146-147`、`:171-172`、`:178-180`） | 设计卷 §3.3「best-effort 握手，fail-open，不许阻塞应用启动」 |
| 47 | 所有可阻塞系统调用带 **EINTR 重试**；sendmsg 处理短写 | `frida_connect`（`:274-287`）、`frida_sendmsg`（`:289-303`）、`frida_recv`（`:353-367`）循环至非 EINTR；`frida_sendmsg_all` 按 iovec 推进直到写完（`:305-351`）；`frida_get_errno`（`:268-272`） | `gate.c` 同名 wrapper |
| 48 | tail-call `raise(SIGSTOP)`：4 个架构的内联汇编（i386/x86_64/arm/aarch64），`naked, noinline` | 宏 `FRIDA_TAILCALL_TO_RAISE_SIGSTOP`：i386 `:191-206`、x86_64 `:210-222`、arm `:226-238`、aarch64 `:242-255`；经 `offsetof (ZymbioteContext, raise)` 从 `zymbiote` 全局取函数指针后 `jmp/br`；`__attribute__((naked, noinline))` 的 `frida_stop_and_return_from_setargv0`：`:261-266` | LSPFRIDA 只做 arm64/x86_64 两份（设计卷 §3.2）；注意 LSPFRIDA 结构体尾部多 2 个指针，但 `raise` 偏移仍 200（`gate_context.h` 静态断言） |

### 1.6 宿主侧 gate 处理与尝试恢复（行 49–57）

| 步骤 | 机制一句话 | 精确证据（frida-core，commit d5bc4840） | LSPFRIDA 对照落点 |
|---|---|---|---|
| 49 | `read_hello`：先等 12B header，peek 出 `name_len`，再等 `12+name_len`，按 HOST 端序解析 | `header_size = 12`；`prepare_to_read (12)`；`input.peek ((uint8[]) &process_name_len, 8)` 取 header 第 3 个 u32；`message_size = header_size + process_name_len`；`prepare_to_read (message_size)`；`BufferReader`：`read_uint32`(pid) / `read_uint32`(ppid) / `skip(4)` / `read_fixed_string(name_len)`：`src/linux/linux-host-session.vala:2198-2229`（peek `:2204-2205`，解析 `:2211-2221`）；辅助 `prepare_to_read` `:2258-2268` | `wire.c:lsp_wire_read_hello`（同样 12B header + 定长 name） |
| 50 | **chrome zygote 特例**：不 ack、直接丢连接，并把 ppid 的 patch 继承给 pid | `if (hello.process_name == CHROME_ZYGOTE_PACKAGE_NAME) { zymbiote_patches[hello.pid] = zymbiote_patches[hello.ppid]; /* 不 ack，让 zymbiote 保持 hook */ return; }`：`src/linux/linux-host-session.vala:2112-2121`；常量 `:1402` | LSPFRIDA v1 **未实现**（设计卷 §7.3）；`inject.c:207-208` 只认 4 类，故 chrome zygote 根本不会被注入 |
| 51 | 普通进程：把 `hello.ppid` 的 patch 集挂到连接上，等 ack 后对**子进程**还原 | `connection.patches_to_revert = zymbiote_patches[hello.ppid];`：`src/linux/linux-host-session.vala:2123` | 子进程 fork 自带已 patch 的内存；daemon ack 后需对子进程还原（同机制） |
| 52 | `needs_resume` 来源 A：**spawn 请求表**命中 | `spawn_requests`：字段 `:1396`；注册 `spawn_requests[process_name] = request` `:1511-1515`；命中 `spawn_requests.unset (package_name, out spawn_request)` → `spawn_request.resolve (hello.pid); needs_resume = true;` `:2132-2135` | **LSPFRIDA 无 spawn 请求**（见 §3）；对应 `--hold` + `resume <pid>` |
| 53 | `needs_resume` 来源 B：**spawn gating** 开启（普通 app 启动也门控） | `else if (spawn_gating_enabled) { pending_spawn[hello.pid] = info; watchdog.arm (hello.pid); spawn_added (info); needs_resume = true; }`：`src/linux/linux-host-session.vala:2136-2142`；开关字段 `:1395`、`enable/disable_spawn_gating` `:1457-1475`；watchdog `:1398` + 超时回调 `:1415-1418`；watchdog 本体 `lib/base/session.vala:1032-1068`（默认 8000ms，`arm/cancel/clear`） | **LSPFRIDA 无 spawn gating 概念**；ack 策略改为「默认立即 ack / `--hold` 时不 ack」（设计卷 §3.4） |
| 54 | 需要门控的连接挂起，否则立刻 resume | `if (needs_resume) zymbiote_connections[hello.pid] = connection; else connection.resume.begin (io_cancellable);`：`src/linux/linux-host-session.vala:2144-2147` | `serve` 的 hold 决策点 |
| 55 | `ZymbioteConnection.resume`：写 `0x42` → 读 1 字节 bye → `wait_until_stopped` → revert 子进程 patch → `SIGCONT` | `uint8 ack[1] = { 0x42 }` + `write_async`（`:2241-2242`）；`input.read_async (bye, ...)`（`:2244-2245`）；`yield wait_until_stopped (hello.pid, ...)`（`:2250`）；`patches_to_revert.revert (open_process_memory (hello.pid))`（`:2252-2253`）；`Posix.kill (hello.pid, SIGCONT)`（`:2255`） | `wire.c:lsp_wire_send_ack` + `inject.c` 的 revert/SIGCONT（daemon = 宿主） |
| 56 | `try_resume(pid)`：取出挂起连接并 resume，清 pending_spawn/watchdog | `zymbiote_connections.unset (pid, out connection)` → `connection.resume.begin (io_cancellable)`；`pending_spawn.unset` → `watchdog.cancel` + `spawn_removed`；返回 true/false：`src/linux/linux-host-session.vala:1546-1559`；调用方 `LinuxHostSession.perform_resume`：`:385-395` | LSPFRIDA：`lspfrifa-zygote resume <pid>` CLI（设计卷 §3.4），无 `Client.resume` |
| 57 | 会话关闭：对所有 zygote 的 patch 逐个 **SIGSTOP → revert → SIGCONT**，最后清表 | `foreach (var e in zymbiote_patches.entries) { Posix.kill(pid, STOP); yield wait_until_stopped; try { patches.revert (open_process_memory (pid)); } finally { Posix.kill(pid, CONT); } }` + `zymbiote_patches.clear ()`：`src/linux/linux-host-session.vala:1424-1455`（循环 `:1438-1454`） | daemon SIGINT/SIGTERM/`revert` + state 文件还原（设计卷 §6、§3.4） |

### 1.7 证明「不使用 ptrace」（行 58–61）

| 步骤 | 机制一句话 | 精确证据（frida-core，commit d5bc4840） | LSPFRIDA 对照落点 |
|---|---|---|---|
| 58 | **全树 `PTRACE_ATTACH` 出现 0 次** | `git grep -n PTRACE_ATTACH HEAD` → **0 行**（覆盖全树 867 文件）。同次 `git grep -n -i ptrace HEAD` 共 170 行，全部落在**非本路径**：`src/freebsd/binjector-glue.c`、`src/darwin/policyd.c`、`src/prospero/binjector.vala`、`lib/base/*-syscalls/*.vala`（系统调用号枚举表）、`lib/selinux/patch.c:51`（SELinux 策略规则，给 zygote 域 `sys_ptrace` capability，**不是调用点**）、`src/linux/frida-helper-backend.vala`（通用 Linux helper 的注入实现，未被 RoboLauncher 调用）、`src/linux/helpers/syscall-tracer.bpf.c:7`（`#include <linux/ptrace.h>` 取 eBPF 结构体定义） | LSPFRIDA 同样不引入 ptrace；`inject.c` 只用 `/proc/pid/mem` |
| 59 | 注入路径的暂停/恢复**只用** `SIGSTOP`/`SIGCONT` | `Posix.kill (pid, Posix.Signal.STOP)`：`:1664`、`:1442`；`Posix.kill (pid, Posix.Signal.CONT)`：`:1702`、`:1448`、`:2255` | `inject.c` 同名调用 |
| 60 | 内存读写**只用** `/proc/<pid>/mem` 的 fd `pread`/`pwrite`（无 `process_vm_writev`、无 `PTRACE_POKEDATA`） | `open_process_memory`：`:2691-2696`；`ZymbiotePatches.apply/revert` 全部经 `fd.pread`/`fd.pwrite`：`:2160-2180` | `inject.c:proc_mem_read` / `proc_mem_write` |
| 61 | gate payload 侧也不含 ptrace：头文件里没有 `sys/ptrace.h`，全文无 ptrace 调用 | `src/linux/helpers/zymbiote.c:1-7`（includes 仅 `errno.h`/`jni.h`/`signal.h`/`stdbool.h`/`sys/mman.h`/`sys/socket.h`/`sys/un.h`）+ 全文 367 行无 `ptrace` | `gate.c` 同 |

**「无 ptrace」一句话结论**：暂停是 `kill(SIGSTOP)`，写入是 `open("/proc/<pid>/mem", O_RDWR)` + `pwrite`，等待是轮询 `/proc/<pid>/stat` 的 `state == 'T'`；注入者与被注入者之间不存在 tracer/tracee 关系（证据行 58–61，重点是 `src/linux/linux-host-session.vala:1664-1703`、`:2691-2756`）。

---

## 2. 看雪 thread-289866 断言 vs 当前源码

> 说明：本仓库与环境里**没有保存该帖正文**（`docs/`、`/tmp/frida-core-ref` 均无 `289866` 原文），本节按 phase-2 brief §参考基线 转述的 5 条断言逐条对照，**未逐字核对原帖代码**（该帖为 frida 17.6.0 快照）。凡「当前源码里没有」的，直接写明。

| # | 帖子断言（brief 转述） | 判定 | 当前源码的精确证据 | 说明 |
|---|---|---|---|---|
| i | 写 `/proc/zygote/mem` | **部分一致** | `open_process_memory`：`/proc/%u/mem` + `O_RDWR` `src/linux/linux-host-session.vala:2691-2696`；写入经 `ZymbiotePatches.apply` → `fd.pwrite` `:2169`；目标 pid 来自 5 类进程名枚举 `:1610-1622`（`zygote`/`zygote64`/`usap32`/`usap64`/`com.android.chrome_zygote`） | 机制一致（向目标进程的 `/proc/<pid>/mem` 写）；但当前源码**没有**固定的 `/proc/zygote/mem` 路径，也不假设只有一个 zygote：每个枚举到的 pid 各开各的 mem，`/proc/zygote` 这种单数写法只在旧口头描述里成立 |
| ii | `setArgV0` 的 **ArtMethod `entry_point`** 改写 | **部分一致**（效果一致，实现不一致） | 解析导出 `:1868-1874` → 把该导出地址按 HOST 端序打字节 `:1896-1908` → 在 boot heap / 匿名 rw 页里 **memmem 这个指针值** `:1910-1932` → `pwrite` 替换指针 `:1684-1688`；`is_boot_heap` 谓词 `:2079-2087`；匿名 `rw-p 00000000` 正则 `:1793-1813` | 当前代码**没有**任何 `ArtMethod` 类型/字段偏移常量（`git grep -n ArtMethod HEAD` → 0 行；`entry_point` 在 `src/linux` 亦无命中），也不解析 libart 符号来算结构体偏移。它做的是「在堆里找到存放该导出函数指针的槽，把槽值改成 payload 里的替换函数地址」。效果可表述为：**`android.os.Process.setArgV0` 所读取的那个 native 入口指针被改道**；实现是**堆内函数指针槽 patching**，不是 ArtMethod 结构体字段改写。详见 §2.1 |
| iii | payload 藏进 `libstagefright.so` **最后一页** | **一致** | `payload_base = m.end - Gum.query_page_size ()`，目标映射须 `m.executable` 且 path 后缀 `/libstagefright.so` 或 `/libmedia.so`；`payload_original_protection = R\|X`（可写时再加 `\|W`）；`payload_file_offset = m.file_offset`：`src/linux/linux-host-session.vala:1766-1775` | 回退库是 `libmedia.so`，不是只认 libstagefright；写前从 page 首字节起覆盖整个模板（payload 大小 = 模板长度，见行 26/29） |
| iv | gadget / **ptrace 附着** | **不一致** | 暂停/恢复只用 `SIGSTOP`/`SIGCONT`（`:1664`、`:1702`）；写入只用 `/proc/<pid>/mem` `pwrite`（`:2691-2696`、`:2169`）；`git grep -n PTRACE_ATTACH HEAD` → 0 行；payload 是内嵌的 zymbiote ELF（`:1971-1978`，由 `src/linux/helpers/meson.build:95-103` 构建），不是 Frida Gadget（`git grep -i gadget HEAD -- src/linux` → 0 行） | 当前实现完全不需要 tracer：注入者只要对目标有 `kill` + `/proc/<pid>/mem` 权限（root/同 uid + yama 例外）即可。没有 `PTRACE_ATTACH`，也没有「先附着再注入」的 gadget 流程。详见 §2.2 |
| v | 事后 zygote 清理 | **部分一致** | 会话关闭时全量还原：SIGSTOP → `patches.revert` → SIGCONT + `clear()` `:1424-1455`；每个子进程 ack 后只还原**该子进程**继承的 patch `:2250-2255`；chrome zygote 分支**故意不 ack** 以让 hook 保持 `:2112-2120`；重注入时 `already_patched` 支持覆盖 `:1923-1927`、`:1670-1698` | 「清理」存在，但时机是**会话关闭/显式 close**，不是每次启动后立即还原 zygote 自身；子进程是在 ack 后立刻还原；chrome zygote 是明确的例外（保持 hook 供其子进程继承）。所以不是「事后立刻把 zygote 还原干净」的语义 |

### 2.1 精确说明 (ii)：为什么这是「堆内函数指针槽」，不是 `ArtMethod` 字段偏移

1. 先拿到 `android_os_Process_setArgV0` 的**运行时地址**：`runtime_mapping.start + e.address`，符号名逐字为 `_Z27android_os_Process_setArgV0P7_JNIEnvP8_jobjectP8_jstring`（`src/linux/linux-host-session.vala:1859-1874`）。
2. 把这个 8 字节指针值按目标字节序打成 needle（`:1896-1908`），在候选堆区间里做 `memmem`（`:1910-1932`）。候选 = ART boot heap（`boot.art` / `boot-framework.art` / `boot-image-methods.art` / `dalvik-LinearAlloc`，`:2079-2087`）+ 匿名 `rw-p 00000000` 映射（`:1793-1813`）。
3. 命中偏移即「槽地址」：`setargv0_slot = candidate.start + (p - heap)`（`:1930`）；随后 `pwrite` 把槽值改成 payload 里 `frida_zymbiote_replacement_setargv0` 的运行时地址（`:2000-2001` 算出、`:1684-1688` 写入）。
4. 因此：**没有** ArtMethod 结构体布局、**没有** `entry_point` 字段偏移、**没有** libart 符号解析。源码里连 `ArtMethod` 这个标识符都不存在（全树 0 命中）。若有人把机制描述为「按 ArtMethod 偏移改写 entry_point」，那是把**效果**（setArgV0 的 native 目标被改道）误当成**实现**；当前实现是「按指针值搜堆、改指针槽」，对 ART 内部结构布局不敏感（这也解释了为什么它对不同 Android 版本更稳）。
5. 一个可测的副作用（源码可读出的推论，供 LSPFRIDA 设计参考）：同一指针值在堆里若出现多处，当前实现只改**第一个命中候选里的第一处**（`:1929-1932` 命中即 break），其余同值槽位不改。

### 2.2 精确说明 (iv)：为何确定没有 ptrace

- 全树 `PTRACE_ATTACH` 0 行；Linux 侧唯一带 ptrace 的业务实现是 `src/linux/frida-helper-backend.vala`（通用 helper 注入），`RoboLauncher.inject_zymbiote` 的调用链（`:1661-1704`）只触碰 `Posix.kill`、`wait_until_stopped`、`open_process_memory`、`ZymbiotePatches`，不经过该 helper。
- `wait_until_stopped` 用 `/proc/<pid>/stat` 的 `'T'` 判定（`:2739-2756`），不是 `waitpid(..., WUNTRACED)` + ptrace stop。
- 写内存用 `pwrite` 到 `/proc/<pid>/mem`（`:2169`、`:2179`），不是 `PTRACE_POKEDATA`。
- gate payload 侧亦无 ptrace（`src/linux/helpers/zymbiote.c:1-7` + 全文）。
- 唯一与「zygote + ptrace」同框的条目是 SELinux 策略：`lib/selinux/patch.c:51` `{ { "zygote", NULL }, "zygote", "capability", { "sys_ptrace", NULL } }` —— 那是 frida 给 zygote 域补的 capability 规则（供其它注入路径使用），**不是**本路径的调用点。

---

## 3. LSPFRIDA 形态差异：这些 frida-server 假设不迁移

| # | frida-server 侧假设 | 证据（frida-core） | 为什么 LSPFRIDA 不能照搬 |
|---|---|---|---|
| D1 | 注入由**长驻 frida-server 的 HostSession 生命周期**驱动：preload 即注入、close 即全量还原；整条机制只在 Android 构建里 | `LinuxHostSession.preload` → `robo_launcher.preload`：`src/linux/linux-host-session.vala:93-98`；`close` → `robo_launcher.close`（含全量 revert）`:100-115`、`:1424-1455`；整段位于 `#if ANDROID`（`:547`–`:2757`） | LSPFRIDA 宿主是用户 `su` 起的独立 root daemon（`lspfrifa-zygote`）：没有 Client 会话、没有「关闭会话」这个时机。注入/还原必须由 daemon 自己的 `inject`/`revert`/信号决定，并用 state 文件兜底 daemon 被 SIGKILL 的情况（设计卷 §6） |
| D2 | 存在 **spawn 请求表**：spawn 时先注册 `spawn_requests[process_name]`，等 zygote 子进程 hello 回来才 resolve pid | 字段 `:1396`；注册 `:1511-1515`；hello 命中 `:2132-2135`；spawn 本体经 android-helper `stop_package`/`start_package` `:1485-1544` | LSPFRIDA 没有 `Client.spawn`，也没有「替用户拉起 app」的职责；ack 的语义从「允许 spawn 继续」退化为「允许该子进程继续」（设计卷 §1 映射表最后两行） |
| D3 | 存在 **`try_resume` / `Client.resume` 链路**：`perform_resume` 先试 spawn gater，再试 robo_launcher | `perform_resume`：`src/linux/linux-host-session.vala:385-395`；`RoboLauncher.try_resume`：`:1546-1559` | LSPFRIDA 没有调用方会来 resume（不走 Client API）；放行动作改为 daemon 的 `resume <pid>` 命令 / hold 超时策略（设计卷 §3.4） |
| D4 | 存在 **spawn gating 开关 + pending_spawn + 8s watchdog**：普通 app 启动也被门控，超时自动取消 gating | `spawn_gating_enabled` `:1395`、`:1457-1475`；`pending_spawn` `:1397`；`watchdog` `:1398`、`:1415-1418`；watchdog 本体 `lib/base/session.vala:1032-1068`（默认 8000ms） | LSPFRIDA 无 `enable_spawn_gating` API；门控语义改为「默认立即 ack；`--hold` 名单内不 ack，等 daemon/用户 `resume`」（设计卷 §3.4），超时策略需自己定 |
| D5 | **chrome zygote 走特殊分支**：不 ack、把 ppid 的 patch 继承给 pid，让 hook 继续存活 | `CHROME_ZYGOTE_PACKAGE_NAME` `:1402`；特例 `:2112-2121` | LSPFRIDA v1 明确不做（设计卷 §7.3，`inject.c:207-208` 只认 4 类）。注意：这不是「少一个优化」——若 LSPFRIDA 把 chrome zygote 当普通目标 ack，就会让它把自己继承的 patch 还原掉；反过来若不 ack 又没实现 ppid→pid 继承，后续 chrome 子进程就拿不到 patch。所以「不支持」必须表现为**根本不注入它**，而不是注入后走普通分支 |
| D6 | 「gate 的业务」= 让 frida-server 继续 spawn/attach：ack 之后由 server 侧拉起 agent；`resume` 才真正 `SIGCONT` | ack `:2241-2242`；`wait_until_stopped` + revert + CONT `:2250-2255`；needs_resume 只是把连接挂起 `:2144-2147` | LSPFRIDA 的 gate **不拉起任何东西**：只写 env 标记 + logcat + hello/ack，引擎与脚本由 LSPosed 在 Java 入口注入（设计卷 §0、§3.3）。这是形态差异的核心：ack 只代表「可以继续」，不代表「server 已接手」 |
| D7 | patch 由 server 进程内的 `zymbiote_patches` 表持有（会话私有，进程退出即丢） | `private Gee.Map<uint, ZymbiotePatches> zymbiote_patches`：`:1392`；只在内存里，close 时 clear `:1454` | LSPFRIDA 必须把这些「原字节/槽地址」持久化到 state 目录，否则 daemon 崩溃后 zygote 永久带 patch 无法还原（设计卷 §3.4/§6） |

---

## 4. 未核实 / 风险（不臆造）

1. **原帖正文未核对**：看雪 thread-289866 的原文/代码不在本仓库或环境中，§2 的 5 条断言按 brief 转述对照，**未逐字核对** 17.6.0 快照里的实现细节。
2. **Android 17 / API 37**：当前源码没有任何版本判断；机制依赖「boot heap 里存在值为 `android_os_Process_setArgV0` 的指针槽」（`:1910-1935`）与「存在 libstagefright/libmedia 可执行映射最后一页」（`:1768-1775`）。版本/ART 布局变化会让这两条任一失败（分别报 `NOT_SUPPORTED`），**未在任何真机上验证**，不预测成功。
3. **32 位 ABI**：源码支持 arm/x86 blob（`:1971-1978`）与 i386/arm 的 tail-call asm（`zymbiote.c:191-206`、`:226-238`），且 `pointer_size` 取自目标 libc（`:1969`）。但 LSPFRIDA 设计明确只做 64 位（设计卷 §3.1，`gate_context.h` 有 `_Static_assert(sizeof(void*)==8)`）；32 位路径在 LSPFRIDA 侧未实现、未验证。
4. **厂商 ROM 变体**：三个启发式都可能不成立——payload 库路径后缀（`:1769`）、boot heap 的 4 个路径子串（`:2083-2086`）、匿名 `rw-p 00000000` 正则（`:1794`）。某些 ROM 可能把 `/libstagefright.so` 的最后一页换成不可执行/已占用，或把槽位放在别的映射里；失败形态是明确报错（`:1815-1820`、`:1934-1935`），但注入成功率**未验证**。
5. **libselinux 缺失/未启用**：`libselinux_path == null` 时静默跳过 setcontext 改道（`:1838`、`:1875`、`:1691`），不报错。此时只有 setArgV0 一路生效；对「gate 一定在 setcontext 时机被调」的假设不成立——**未验证**具体 ROM/容器里的行为。
6. **候选区间无大小上限**：`heap_candidates` 里的每个映射都被整段分配并 `pread`（`:1913-1916`），没有 `size` 上限。一个巨大的匿名 rw 映射（或多份 boot heap）会导致大内存分配与长时间读取；此风险在 LSPFRIDA 实现里应加上限（源码本身没有）。
7. **读取发生在 SIGSTOP 之前**：`open_process_memory` 与整段 heap 扫描在 `prepare_zymbiote_injection`（`:1894`、`:1916`）里完成，而 `SIGSTOP` 在其后的 `inject_zymbiote`（`:1664`）；即**堆是在目标仍在运行时读取的**，期间内容是否稳定、是否可能 memmem 到撕裂的指针，源码未做同步，也未验证。
8. **`assert` 作为失败路径**：blob 里找不到占位串/两个导出时用 `assert`（`:2006-2007`、`:2014`），不是可诊断错误；若构建未开 `NDEBUG` 会 abort。LSPFRIDA 侧应改为显式错误返回（自己的代码里不要照抄 assert）。
9. **`server_name` 长度不校验**：`write_string` 后直接 `cursor += 64`（`:2020-2021`），名字超过 63 字节会写穿到 `payload_base` 字段。frida 自己生成的名字恰好 16+32=48 字符；LSPFRIDA 允许 `--socket` 自定义，**必须自己加长度校验**。
10. **结构体布局不可跨实现照抄**：本文附录的偏移（0/64/72/…/200）只对 frida 的 `ZymbioteContext` 成立。LSPFRIDA 的 `LspGateContext` 在其尾部**追加**了 `setenv`/`log_write`（偏移 208/216，`sizeof==224`，见 `tools/zygote/src/gate_context.h` 的 `LSP_GATE_OFF` 静态断言），前 19 个字段与 frida 一致。数值可对照，但**不能把 frida 的 0x3a8/cursor 步进当成 LSPFRIDA 的常量**。
11. **gate 侧 ack 不校验字节值**：`frida_recv(fd,&rx,1,0) == 1` 即视为放行（`zymbiote.c:175-182`），任何 1 字节都行；LSPFRIDA 协议约定 `0x42`，但若要严格校验需自己加。
12. **真机时序未验证**：hold（不 ack → 子进程阻塞在 recv）→ `resume` → 子进程 `raise(SIGSTOP)` → 宿主轮询到 `'T'` → revert → `SIGCONT` 的整条时序（`:2144-2147`、`:2237-2256`、`zymbiote.c:105-109`）在沙盒无设备，只有 host 组件测试可覆盖，真机行为**未验证**。
13. **usap 的「瞬态失败」语义未确证**：源码只是把这一种错误吞掉（`:1640-1659`），注释也没有解释为什么 usap 会短暂定位不到槽（`usap32`/`usap64` 是 Android 的 unspecialized app process 池）。为什么可安全忽略、忽略后 gate 是否会漏掉某些子进程，**未验证**。

---

## 5. 附录：本 commit 下可复核的具体数值

以下数值**不是**源码注释，而是用仓库内已提交的 artifact + 标准工具实测（命令照抄即可复核）：

| 项 | 值 | 复核命令 |
|---|---|---|
| 内嵌 blob 文件 | `src/linux/helpers/artifacts/native/arm64/zymbiote.elf` | `ls -l` |
| ELF 类型 | `DYN (Shared object file)`，无解释器 | `readelf -hW src/linux/helpers/artifacts/native/arm64/zymbiote.elf` |
| 可执行段（模板来源） | `LOAD 0x002000 vaddr 0x0000000000001000 filesz 0x478 flags RWE` | `readelf -lW ...` |
| 两个导出 | `frida_zymbiote_replacement_setcontext` = vaddr `0x1000`；`..._setargv0` = vaddr `0x1078` | `readelf -sW ... \| grep zymbiote_replacement` |
| 占位串在 blob 内偏移 | 文件偏移 `0x23a8` ⇒ 相对段起点 `data_offset = 0x3a8` | `python3 -c` 或 `grep -abo` |
| 模板长度 | `0x478` = 1144 字节 ⇒ `payload_size` 字段写入值 | 同上 |
| 结构体尾部与段边界 | `data_offset 0x3a8` + `sizeof(ZymbioteContext)=0xd0(208)` = `0x478` = 模板长度：**结构体恰好占满模板末尾** | 见下方 offset 表 |
| 字段偏移（frida `ZymbioteContext`，LP64） | 0 socket_path(64B)；64 payload_base；72 payload_size；80 payload_original_protection；88 process_name；96 original_setcontext；104 original_set_argv0；112 mprotect；120 strdup；128 free；136 socket；144 connect；152 `__errno`；160 getpid；168 getppid；176 sendmsg；184 recv；192 close；200 raise | **推导**：用与 `zymbiote.c:11-36` 同成员顺序/类型的一份 19 字段副本结构体编译 `offsetof`；或直接对照写入顺序 `src/linux/linux-host-session.vala:2019-2074` 与 `LSP_GATE_OFF` 表（LSPFRIDA 前 19 字段偏移完全一致，可交叉验证） |
| 导出→运行时地址 | `setcontext = payload_base + 0x000`；`setargv0 = payload_base + 0x078`（`e.address - text.vm_address` = `0x1000-0x1000` / `0x1078-0x1000`） | 由 `:1997-2009` + 上面的 `readelf` 数值直接计算 |

> 注意：以上数值只对 **arm64 blob + 64 位目标** 成立；`arm`/`x86`/`x86_64` blob 的段偏移与导出地址不同（同样可用 `readelf` 复核）。32 位目标下 `pointer_size=4`，写入步进与字段宽度全部减半（`src/linux/linux-host-session.vala:1969`、`:2024` 等），LSPFRIDA 不做 32 位，故不作为证据沿用。

---

## 6. 交付边界

- 本文件是本次任务**唯一**写入；未修改仓库内其它文件，未执行 `git commit` / `git push`。
- 本文的证据对象是 `/tmp/frida-core-ref` 的 commit `d5bc4840`（2026-11-04）当前树；引用行号均可用 §0 的复核命令逐行验证。
