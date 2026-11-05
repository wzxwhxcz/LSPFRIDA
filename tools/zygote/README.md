# LSPFRIDA zygote gate + root injector (`tools/zygote`)

Zymbiote-style **zygote birth gate** for LSPFRIDA, ported from frida-core
`src/linux/helpers/zymbiote.c` + `src/linux/linux-host-session.vala`
(`RoboLauncher`, commit `d5bc4840`) to the form frozen in
[`docs/Zygote-Injection-Design.md`](../../docs/Zygote-Injection-Design.md).

It is **complementary to the LSPosed entry point, not a replacement** (design
§0). The gate only proves/observes process birth: it runs *after* `fork()` and
*before* application code, writes env markers + one logcat line, optionally
holds the child until a root daemon says `resume`, and then gets out of the
way. Script/hook injection time is unchanged — the only engine/script entry is
still the LSPosed module callback.

Everything here is standalone: **no CMake, no AGP change, no `app/src/main/cpp`
change**. `tools/zygote/build.sh` builds the payload and the injector directly
with clang/gcc.

---

## 1. Components

| Path | Role |
|---|---|
| `src/gate_context.h` | Frozen `LspGateContext` ABI shared by payload and injector; `_Static_assert`s on all 21 field offsets and `sizeof == 224`; placeholder `"/lspfrifa-zygote-" + 32 × '0'`. |
| `src/gate.c` | Gate payload (port of `zymbiote.c`, ~470 lines). Two exported entry points in `.text.entrypoint`: `lsp_gate_replacement_setcontext`, `lsp_gate_replacement_setargv0`. Freestanding: every libc call goes through the context table. |
| `src/gate.lds` | Linker script, port of frida `helper.lds` (`.payload` at vaddr 0, dynsym/gnu.hash/got after it). |
| `src/inject.c` | `lspfrifa-zygote` CLI + daemon: targets/inject/serve/revert/resume/self-test, patch preparation, SIGSTOP/write/SIGCONT, hello/ack daemon, hold policy, control channel, signal-time revert. |
| `src/proc_maps.{c,h}` | `/proc/<pid>/maps` snapshot; payload-page / module-base / boot-heap / anon-rw predicates (port of `proc-maps.vala` + frida `is_boot_heap`). |
| `src/elf_parser.{c,h}` | 64-bit ELF reader: exports via `PT_DYNAMIC` (`DT_SYMTAB`/`DT_STRTAB`/`DT_HASH`/`DT_GNU_HASH`), **imports via relocation slots** (`DT_RELA`/`DT_REL`/`DT_JMPREL` `r_offset`), text-segment lookup. Port of frida `elf-parser.c` + the import part `Gum.ElfModule` provided. |
| `src/payload.{c,h}` | Builds the bytes pwritten at `payload_base`: extracts the gate text segment, memmem-finds the 64-byte `socket_path` placeholder, writes fields in frozen struct order. |
| `src/state.{c,h}` | `<state>/<pid>.patch` text record (payload base/len/orig-prot/path/file-offset/original page bytes + slot addr/orig/repl) so `revert` works after the daemon was SIGKILLed. |
| `src/wire.{c,h}` | Abstract-socket hello (`{u32 pid; u32 ppid; u32 name_len}` host-endian + name), ack byte `0x42`, listen/connect helpers, JSON escaping. |
| `build.sh` | Host x86_64 + Android arm64 (NDK 28) builds; verifies the gate (no `DT_NEEDED`, exactly 2 exported entry symbols, no undefined dynsym, **no relocations**, exec segment ≤ 1 page). |
| `test.sh` | One-command host suite: component tests + 3 real mock-zygote E2E scenarios. Exits non-zero on any failure. |
| `tests/` | Component tests, mock `libstagefright.so` / `libandroid_runtime.so` / `libselinux.so`, `mock_zygote.c`. |
| `out/host`, `out/arm64` | Build outputs (git-ignored). |

---

## 2. Build

Requirements on this machine (already present):

* `gcc`/`g++` (host injector + tests),
* NDK 28 `$HOME/android-sdk/ndk/28.2.13676358` (Android arm64 and the **host**
  gate clang: the system has no standalone `clang`, and the payload needs
  `naked`/`musttail` x86_64 support that gcc 13 lacks, so the host gate is
  built with the NDK clang under `--target=x86_64-unknown-linux-gnu`),
* JDK 25 `$HOME/android-sdk/jdk-25` for `<jni.h>` on the host build
  (`-I$HOME/android-sdk/jdk-25/include -I$HOME/android-sdk/jdk-25/include/linux`).

```bash
bash tools/zygote/build.sh            # host:  out/host/lspfrifa-gate.elf
                                      #        out/host/lspfrifa-zygote
                                      #        out/host/component_tests, out/host/mock_zygote
bash tools/zygote/build.sh --android  # arm64: out/arm64/lspfrifa-gate.elf
                                      #        out/arm64/lspfrifa-zygote (PIE, interpreter /system/bin/linker64)
```

The gate flags are the frida helper set adapted to this toolchain:
`-nostdlib -shared -fPIC -fvisibility=hidden -fno-builtin -fno-stack-protector
-fno-asynchronous-unwind-tables -fno-function-sections -fno-data-sections
-ffreestanding -O2`, plus `-mbranch-protection=bti` and
`-Wl,-z,max-page-size=16384` on arm64. `-mno-memcpy` and
`-fno-optimize-strlen` are **not supported** by host gcc 13 or NDK clang 28 and
are dropped (the same intent is covered by `-fno-builtin -ffreestanding`).

Build-time verification (fails the build otherwise), real output:

```
host : LOAD 0x001000 vaddr 0 filesz 0x0a30 RWE
       dynsym: 0x0 82B lsp_gate_replacement_setcontext
               0x60 2128B lsp_gate_replacement_setargv0
       no DT_NEEDED, no undefined dynsym symbols, no relocations
arm64: LOAD 0x005000 vaddr 0x1000 filesz 0x0830 RWE, align 0x4000
       dynsym: 0x1000 128B setcontext, 0x1080 1540B setargv0
       no DT_NEEDED, no undefined dynsym symbols, no relocations
```

A gate payload larger than one page is rejected at build time and at inject
time; the current size is **2608 B (host) / 2096 B (arm64)**.

---

## 3. Tests (`bash tools/zygote/test.sh`)

The suite builds everything, runs module tests, then a **real end-to-end
mock-zygote injection**: a real process is patched through the real injector,
its forked child executes the real gate payload, a real abstract-socket
hello/ack happens, the child is SIGSTOPped, the daemon reverts the child's
patches, SIGCONTs it, and the child records what it observed.

```bash
bash tools/zygote/test.sh          # prints PASS/FAIL lines, exit non-zero on failure
TEST_KEEP=1 bash tools/zygote/test.sh   # keep /tmp/lspfrifa-e2e.* for inspection
```

Observed result on this machine: **`PASS: 32  FAIL: 0` / `RESULT: PASS`**,
with these scenario assertions:

* **Scenario 1 — `inject --serve`**: daemon JSON
  `{"event":"gate_hit","pid":<child>,"ppid":<zygote>,"name":"com.test.app",
  "ack":true,"held":false,"reverted":true}`; child JSON proves
  `LSPFRIDA_ZYGOTE_GATE=1`, `..._NAME=lspfrifa-zygote-test`,
  `..._PPID=<zygote>`, `..._PROC=com.test.app`; the original
  `selinux_android_setcontext` and `android_os_Process_setArgV0` were called;
  the child observed its own SIGSTOP; after the daemon revert, the child's
  payload page and `setArgV0` pointer slot are byte-identical to the originals.
  `SIGTERM` to the daemon reverts the **zygote** patch and the mock zygote
  verifies its pointer + payload page are restored.
* **Scenario 2 — no daemon (fail-open)**: inject without `--serve`; the child
  runs the gate, writes all four env markers, does **not** SIGSTOP, does not
  crash, and the pointer slot stays patched (no daemon to revert). The persisted
  `state/<pid>.patch` then drives a later `revert --pid`, after which the mock
  zygote's pointer + payload page are restored.
* **Scenario 3 — `--hold` + `resume`**: the gate is held (no ack, child blocked,
  no child result yet); `lspfrifa-zygote resume <child-pid>` triggers the ack,
  the child then SIGSTOPs, the daemon reverts it, SIGCONTs it, and the child
  continues with env markers and a restored pointer.

Plus component tests for maps parsing, ELF export resolution against host
libc/libm (including glibc `STT_GNU_IFUNC` `sin`/`cos`), ELF import-slot
resolution against the mock runtime **cross-checked by reading the live GOT
entry**, payload template field/offset patching, gate ELF structure, state-file
round trip, and the wire hello. `grep -rIn 'PTRACE_(ATTACH|SEIZE)|ptrace('`
over `src/` + `tests/` is a test assertion: **no ptrace anywhere**.

### Sandbox note (important, honest evidence statement)

This execution sandbox denies `O_WRONLY` on `/proc/<pid>/mem` **at the syscall
level** (EACCES even for `/proc/self/mem`; `open(O_RDONLY)` works). The frozen
design and frida use `/proc/<pid>/mem` + `pwrite`, which is what the device path
does. The injector therefore keeps that as the **primary** path and only on
`EACCES`/`EPERM` falls back to `process_vm_readv`/`process_vm_writev` (logged
once as `WARN: ... using process_vm_writev sandbox fallback (host test only;
on Android the primary path is /proc/<pid>/mem pwrite)`). The E2E above ran on
the fallback: patch → gate → revert semantics are identical, but the exact
`pwrite` syscall is **not** exercised by the host suite (it needs a real
rooted device or a sandbox that permits `/proc/pid/mem` writes). The mock
fixture also makes its `libstagefright.so` text page RWX, because
`process_vm_writev` refuses read-only private file mappings (EFAULT) whereas
`/proc/pid/mem` `pwrite` does not — on device the real RX page is written
through `/proc/pid/mem`.

---

## 4. On a rooted arm64 device

```bash
adb push tools/zygote/out/arm64/lspfrifa-zygote      /data/local/tmp/
adb push tools/zygote/out/arm64/lspfrifa-gate.elf    /data/local/tmp/
adb shell su -c 'chmod 755 /data/local/tmp/lspfrifa-zygote'

# 1. see zygotes / USAPs
adb shell su -c '/data/local/tmp/lspfrifa-zygote targets'
# 2. patch them all and keep the daemon in the foreground
adb shell su -c '/data/local/tmp/lspfrifa-zygote inject --all --serve'
#    default --gate is lspfrifa-gate.elf next to the executable,
#    default --socket is lspfrifa-zygote-gate, state under /data/local/tmp/lspfrifa-zygote
# 3. optionally hold selected packages until you release them
adb shell su -c '/data/local/tmp/lspfrifa-zygote inject --all --serve \
    --hold com.example.app,com.example.other'
adb shell su -c '/data/local/tmp/lspfrifa-zygote resume <child-pid>'
# 4. revert (also happens automatically on daemon SIGINT/SIGTERM)
adb shell su -c '/data/local/tmp/lspfrifa-zygote revert --all'
adb shell su -c '/data/local/tmp/lspfrifa-zygote revert --pid <zygote-pid>'
```

Each gate hit prints one JSON line on the daemon's stdout (diagnosis + tests):

```json
{"event":"gate_hit","pid":1234,"ppid":600,"name":"com.example.app","ack":true,"held":false,"reverted":true}
```

and the child gets the four env markers the Kotlin module reads
(`LSPFRIDA_ZYGOTE_GATE`, `_NAME`, `_PPID`, `_PROC`), plus one logcat line:

```
tag LSPFRIFA-Zygote : GATE_HIT pid=… ppid=… name=… host=… ack=0|1
```

`log_write` is resolved from the target's own `liblog.so` when mapped; if it is
absent or `__android_log_write` cannot be resolved, the pointer stays 0 and the
gate skips logcat (env markers are unconditional). See the design doc for how
`ZygoteGate.kt` consumes these markers.

Rollback if the daemon dies with patches still applied: `revert --pid <pid>`
reads the persisted state; worst case reboot the device / restart zygote.

---

## 5. CLI (frozen, design §3.4)

```
lspfrifa-zygote targets
lspfrifa-zygote inject  [--pid N | --all] [--socket NAME] [--gate PATH] [--serve]
                        [--hold PKG,...]
                        [--libc-match PAT] [--runtime-match PAT] [--selinux-match PAT]
                        [--payload-match PAT] [--state DIR]
lspfrifa-zygote serve   [--socket NAME] [--hold PKG,...] [--state DIR]
lspfrifa-zygote revert  [--pid N | --all] [--state DIR]
lspfrifa-zygote resume  <pid> [--socket NAME]
lspfrifa-zygote self-test [--gate PATH] [--state DIR]
```

Defaults: `--socket lspfrifa-zygote-gate` (control channel is `<socket>-ctl`),
`--gate` = `lspfrifa-gate.elf` in the executable's directory, `--state` =
`$LSPFRIFA_ZYGOTE_STATE` or `/data/local/tmp/lspfrifa-zygote` (host fallback
`/tmp/lspfrifa-zygote`), matches `/libc.so`, `/libandroid_runtime.so`,
`/libselinux.so`, `/libstagefright.so` (+ `/libmedia.so` fallback).
Exit codes: `0` ok, `2` usage, `3` target not found, `4` layout unsupported,
`5` permission.

Mechanism (no ptrace): resolve the three modules by path suffix, resolve exports
through the ELF dynsym, resolve `selinux_android_setcontext`'s GOT slot through
the runtime's relocation tables, memmem the `setArgV0` pointer value in
boot-heap/anonymous-rw candidates, build the payload, `SIGSTOP` the zygote,
wait for `/proc/<pid>/stat` state `T`, write through the process memory
interface, `SIGCONT`. On each child hello the daemon acks (or holds), waits for
the child's `T` state, writes the zygote-original page/slot bytes into the
child, and `SIGCONT`s it.

---

## 6. Deviations from `docs/Zygote-Injection-Design.md`

1. **`process_vm_writev` sandbox fallback** (see §3): primary device path is
   the frozen `/proc/<pid>/mem` pwrite; the fallback exists only because this
   sandbox denies it. One WARN line is printed when it engages.
2. **`musttail` is used** in `lsp_gate_replacement_setargv0` (clang). The
   original plan considered a plain call because host gcc lacks `musttail`, but
   the E2E proved a plain call SIGSEGVs: the daemon reverts the payload page
   while the child is stopped inside `raise(SIGSTOP)`, so the resume return
   address must not point into the payload. Both shipped gates are clang-built;
   a non-clang build degrades to a plain call (`LSP_MUSTTAIL` empty) and that
   is only safe if the daemon does not revert the child's payload page.
3. **Logcat line is emitted after the handshake** (still one line before
   SIGSTOP), because design §3.3 requires `ack=0|1` in the text while listing
   logcat in step 3 (before the ack exists). Env markers stay in step 3 and are
   unconditional.
4. **Host gate toolchain** is NDK clang with `--target=x86_64-unknown-linux-gnu`
   (no system clang; gcc 13 lacks x86_64 `naked`). Android uses
   `aarch64-linux-android28-clang` as specified.
5. **`--hold` matching** uses the package name before any `:` suffix, exact
   match; a held child produces the `gate_hit` JSON line with
   `ack:false,held:true,reverted:false` and later a separate
   `{"event":"gate_resume",…}` line (design asks for one line per hello with
   ack/held/reverted; for a held hit the ack/revert facts do not exist yet, so
   they are reported on the resume line).
6. `serve` can also revert children of a zygote it did not itself patch by
   loading the persisted state file for `hello.ppid`.
7. Host E2E fixture `libstagefright.so` is mapped RWX at the payload page and
   all E2E participants call `prctl(PR_SET_PTRACER, PR_SET_PTRACER_ANY)` — test
   scaffolding for the sandbox fallback only; the production CLI path and
   `gate.c` are unchanged by this.

## 7. Known limits / not verified here

* No device in this sandbox: real-device timing (SELinux policy, 16 KB page
  devices, vendor `libstagefright`/`libmedia` layout, ART boot-heap layout,
  `libselinux` presence) is **unverified**; every failure is fail-open per
  design §6.
* 32-bit ABIs are explicitly unsupported (`_Static_assert(sizeof(void*)==8)`).
* Chrome zygote (`com.android.chrome_zygote`) patch propagation is not
  implemented (design §7.3).
* The gate does not `dlopen` `libgumjs_bridge.so` or anything else; script
  injection time is unchanged (design §7.5).
* Payload must fit one page; larger gates are rejected loudly.
* Nothing outside `tools/zygote/**` is modified; build outputs are git-ignored
  and nothing is committed or pushed.
