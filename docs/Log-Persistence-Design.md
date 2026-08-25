# LSPFRIFA 日志持久化设计（t1/t5）

> 背景：目标进程日志原先仅在详情页打开时经 ILogReceiver 内存收集——页面不在即丢弃（离开页面回来看不到日志，用户实测确认）。本次改造为「落盘 + 历史回放 + 实时」。

## 机制
- 落盘路径：宿主私有目录 `files/logs/<packageName>/<yyyy-MM-dd>.log`（按包+日期轮转，跨天自动关旧建新）
- 行格式：`[yyyy-MM-dd HH:mm:ss.SSS] <目标进程原始消息>`（宿主加时间戳前缀，原文不动）
- 写入：`IpcManager.logReceiverStub.onLog` → `LogStore.append`（binder 回调仅入队，不阻塞）+ 实时分发 `synchronized(logListeners)` 共存（t1）
- 队列与 flush（t5 增强）：
  - 有界队列 `ArrayBlockingQueue(4096)`；满则丢弃并计数（`droppedCount`，每满 1000 条 Log.w 告警）
  - 批量 flush：累计 ≥32 条或队列静默 >250ms 时 flushAll（单轮最多摘 256 条）
  - 持久性权衡：进程被杀最多丢最后 ≤31 条/≤250ms 未 flush 行；详情页打开瞬间未 flush 行不出现于该次回放（下次可见）
- 读取：详情页 `DisposableEffect` → IO 读 `readHistory(pkg, 500)` → Main 填充 → 订阅实时；`MAX_LOG_LINES=500` 统一上限；`disposed` 标志防边检泄漏（t3 终审落实）
- 初始化：`LSPFRIFAApplication.onCreate → LogStore.init(this)`
- 目标进程（GumJsBridge/TargetIpcServer 日志回调侧）零改动

## 已知边界（有意为之）
- 页面打开瞬间落在 IO 窗口内的日志只落盘、本次不显示
- UI 本地状态行（「脚本热更新成功」等）不落盘（内存）
- 日志按日期无限累积（私有目录），尚无清理/导出策略（后续建议：按包清理/导出按钮）

## 验证
1. 详情页打开查看历史+实时；离开页面后目标产生日志→回来可见（修复点）
2. 高频脚本（如 getpid 刷屏）不再撑爆内存（有界队列）——可观察 dropped 告警
