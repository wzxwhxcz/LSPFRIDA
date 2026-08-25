# 路线B 真机验证清单（2026-08-25，t6/t7 静态验收过，t9 视编译时包含情况）

> 前提注解：DSH 无 JDK，所有编译/真机由用户执行。以下顺序执行，任一失败截日志回传。

## 0. 编译
```
sh gradlew --no-daemon :app:assembleDebug
```
（t6 shim + t7 C/K；若 t9 已合入则 async 场景直接可用，否则该场景显示"dormant"）

## 1. R1-A 探针（编译前即可跑，验证 rpc 链就绪）
编辑器（任意目标应用脚本执行）粘贴 `.tmp-ref/probe-rpc.js` 的两段之一：
- R1-A 预期输出（日志页）：`[probe] R1-A OK: rpc exports ready`
- 失败：`[probe] R1-A FAIL ...` → 截图日志（rpc 全局缺失=降级观察语义，上报 cap）

## 2. replace 基础场景（B1 核心，必测）
模板（脚本编辑页）：
```js
// 场景1：观察继续（fn 返回 undefined → 自动 proceed 原方法，日志可见）
Java.perform(function () {
  var Cls = Java.use("android.app.Activity");
  Cls.onResume.implementation = function () {
    console.log("[verifyB] onResume observe", arguments.length);
  };
});
```
预期（日志页）：`[lsp-hook] ARMED android.app.Activity#onResume overloads=1 mode=replace tag=android.app.Activity#onResume` 之后每次返回 Activity 出现 `[verifyB] onResume observe 0`（或无参数）——**原方法正常执行**（应用不崩、页面正常）。

```js
// 场景2：替换返回
Java.perform(function () {
  var Cls = Java.use("android.app.Activity");
  Cls.onResume.implementation = function () {
    return null;   // 覆盖返回（onResume 是 void，验证"不执行原方法"：日志只有一次、后续 onResume 不再触发——观察场景1的对比）
  };
});
```
预期：ARMED mode=replace + 首次触发后 **onResume 原逻辑不再执行**（对照场景1输出差异）；无 `REPLACE_TIMEOUT`/`JS_ERR`。
> ⚠️ 场景2 会让 Activity 生命周期异常（onResume 被吞）——**测完立即**在编辑器里 `LSP.unhookAll()`（或停用开关）恢复。

## 3. 超时/错误检查点（出现即截图）
日志关键字：`REPLACE_TIMEOUT` / `CAST_FAIL` / `JS_ERR` / `REPLACE_ERR`——任一出现=对应降级路径（不崩应用），截日志回传排查。

## 4. async this.method（需 t9 合入后有效）
```js
Java.perform(function () {
  var Cls = Java.use("android.app.Activity");
  Cls.onResume.implementation = async function () {
    console.log("[verifyB] async before");
    var r = await this.onResume();   // 调原方法（await 契约）
    console.log("[verifyB] async after", r);
    return undefined;                // 观察继续
  };
});
```
预期：日志顺序 before →（原方法执行）→ after；无死锁/无超时；`r` 为原方法返回编码（void→null）。
同步 fn 内调 this.onResume()（不 await）：走桥不抛错，最终正常收尾（日志不崩即可）。

## 5. 回归观察（observe 未破坏）
现有默认模板（LSP.hook "android.app.Activity" "onResume" "demo"）跑一遍确认 ARMED/HIT 照旧（mode 缺省=observe 零改动）。

## 判定
全部预期输出命中 = 路线B B1 验收通过；任何偏差：把日志页**完整行**截图回传（含时间戳），cap 按检查点关键词定位。

## 6. B2 验证（t15 交付后；含 t14 注入提示）

| # | 场景 | 预期 |
|---|---|---|
| B2-1 | `Java.use("android.app.Activity").onCreate.overload('android.os.Bundle').implementation = function (b) { console.log("[vB2] onCreate(sig)"); return undefined; }` | ARMED 日志含 `sigs=[android.os.Bundle]` 且**只挂 1 个**（对照默认挂全部）；切应用 onCreate 命中一次 |
| B2-2 | `await this.bar(新参)`：如 `Java.use("java.lang.System").currentTimeMillis.implementation = async function () { var r = await this.currentTimeMillis(); console.log("[vB2] millis=" + r); return 123; }`（无参方法为简版；带参请用 `java.lang.String.valueOf` 传参场景） | 原方法以**新参数**执行（带参场景验证） |
| B2-3 | `return this.bar(a)` 透传模式（在 B2-1 的 onCreate 内 `return this.onCreate(b)`） | **只执行一次**（无双重执行） |
| B2-4 | 默认注册（无 overload） | B1 场景回归（观察/替换/嵌套零变化） |
| t14 | 设置「注入提示」**开** → 启用目标 → **冷启动目标应用** | 目标应用弹「LSPFRIFA 已注入: 包名」Toast；**关**→不弹 |

（B2-2 带参原方法建议：`java.lang.StringBuilder.append`？——用**必然被调+可安全改参**的：`java.lang.System.currentTimeMillis` 无参不演示传参；传参建议 `android.util.Log.d`（String,String）覆盖返回 void——不可观察。**务实**：观察日志打印即可——`onCreate` 传 bundle 原样传参验证（B2-1 即窗口）；严格传参改用 `java.io.File.length`?——留给用户选择，**以 B2-1/B2-3 为核心判据**）
