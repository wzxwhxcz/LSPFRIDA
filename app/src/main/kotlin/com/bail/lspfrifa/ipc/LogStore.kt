package com.bail.lspfrifa.ipc

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 宿主侧日志持久化层。
 *
 * 目标进程经 [ILogReceiver.onLog] 推送的 GumJS 日志，在此按「包名 + 日期」落盘到
 * 宿主私有目录 `files/logs/<packageName>/<yyyy-MM-dd>.log`，行格式：
 *   `[yyyy-MM-dd HH:mm:ss.SSS] <原始消息>`
 *
 * 写入模型（有界队列 + 批量 flush，高频日志下内存有界、单条 flush 开销最小化）：
 * - `append()` 只做两件事：格式化行 + `offer` 进有界队列 [ArrayBlockingQueue]（容量 [QUEUE_CAPACITY]），
 *   绝不在 IPC binder 回调线程做磁盘 IO。
 * - 单写线程（LSPFRIFA-LogWriter）从队列消费：每轮最多摘取 [MAX_DRAIN_PER_CYCLE] 条，
 *   累计到 [FLUSH_BATCH] 条触发一次 flush；队列静默超过 [FLUSH_INTERVAL_MS] 时，
 *   对残余未满一批的行补一次 flush（时间窗兜底，保证低频日志也 ≤250ms 落盘）。
 * - 队列满：丢弃新日志并计数，每满 [DROP_WARN_INTERVAL] 条打一次低频告警（避免高频日志撑爆内存）。
 * - 持久性权衡：flush 批量化为 32 条/250ms 后，「进程被杀不丢已送达日志」不再逐条保证——
 *   最多丢失最后一批（≤31 条或 ≤250ms 窗口内）已被写入 OS 缓冲区但未 flush 的日志，特此注明。
 *
 * 读取：详情页打开时回放历史（跨天按日期文件正序合并，仅取最近 limit 行），与写入线程互不影响。
 * 保留策略：单包当日文件行数超过 [MAX_LINES_PER_FILE] 时，写线程保留尾部最近一半行以
 * 「临时文件 + 原子 rename」替换（高频日志下磁盘有界；读侧不会看到截断中间态；
 * 与读取侧 [readHistory] 的 limit 封顶互不影响）。
 * 清除：真清除=删除 `files/logs/<pkg>/` 目录 + 记录"清空水位"（丢弃写队列中清除前已
 * 入队的待写行，防在途队列回潮）。
 *
 * 由 [com.bail.lspfrifa.LSPFRIFAApplication.onCreate] 调用 [init]；目标进程侧零改动。
 */
object LogStore {

    private const val TAG = "LSPFRIFA-LogStore"
    private const val LOG_SUBDIR = "logs"

    /** 有界队列容量：防止高频日志时队列无界增长 */
    private const val QUEUE_CAPACITY = 4096

    /** 批量 flush 阈值：累计写入达此条数触发一次性 flush */
    private const val FLUSH_BATCH = 32

    /** 时间窗兜底：队列静默超过该时长后，残余未满一批的行补 flush */
    private const val FLUSH_INTERVAL_MS = 250L

    /** 单轮最多摘取条数：限制单轮工作量，让时间窗与批量阈值能按时生效 */
    private const val MAX_DRAIN_PER_CYCLE = 256

    /** 单包当日日志文件行数上限（保留策略）：超过后裁剪至尾部最近一半行，防磁盘无限增长 */
    private const val MAX_LINES_PER_FILE = 5000

    /** 丢弃告警间隔：队列满累计丢弃每满该条数才打一次 log */
    private const val DROP_WARN_INTERVAL = 1000

    private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    @Volatile
    private var rootDir: File? = null

    /** 待落盘日志的有界队列（单消费者） */
    private val queue = ArrayBlockingQueue<QueuedLine>(QUEUE_CAPACITY)

    /** 每个包当前打开的写入器；跨天或首次写入时关闭旧 writer、新建当日文件 */
    private class OpenWriter(val day: String, val writer: BufferedWriter, var lines: Int = 0)

    private class QueuedLine(val packageName: String, val day: String, val line: String, val seq: Long)

    private val openWriters = ConcurrentHashMap<String, OpenWriter>() // pkg -> OpenWriter

    /** 裁剪（写线程）与 [clear]（任意线程）的互斥锁：防止"clear 删目录 ↔ 裁剪重建文件"竞态导致清除后回潮 */
    private val fileOpsLock = Any()

    /** 页面生命周期无关的执行作用域：真清除必须落盘，即使页面在清除请求后立即销毁 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 入队序号（单调递增）：与 [clearWatermarks] 配合丢弃清除前已入队的待写行（在途队列回潮防御） */
    private val enqueueSeq = AtomicLong()

    /** 各包"清空水位"（清除时刻的入队序号）：写线程丢弃 seq ≤ 水位的该包行。
     *  ConcurrentHashMap 保证跨线程可见；条目按包保留不删除（多生产者下入队顺序与 seq 顺序
     *  可能互换，删除水位会放行越序残留的旧行），规模 ≤ 被清除过的包数。 */
    private val clearWatermarks = ConcurrentHashMap<String, Long>()

    /** 队列满时累计丢弃条数（进程生命周期内累计），配合低频告警 */
    private val droppedCount = AtomicInteger()
    private val lastWarnedDropped = AtomicInteger()

    /** 自启动的单写线程：所有文件 IO 串行化，保证每个包的 writer 只被一个线程访问。
     *  注意：必须最后声明——线程启动时上方共享状态已全部初始化完毕。 */
    private val writerThread: Thread = Thread({ writerLoop() }, "LSPFRIFA-LogWriter").apply {
        isDaemon = true
        start()
    }

    fun init(context: Context) {
        rootDir = File(context.filesDir, LOG_SUBDIR)
    }

    /**
     * 追加一条日志：入有界队列后立即返回，绝不在 IPC 回调线程做磁盘 IO。
     * 队列满时丢弃本条并计数（低频告警）。
     */
    fun append(packageName: String, message: String) {
        val root = rootDir ?: return
        val day = dayFormatter.format(LocalDateTime.now())
        val line = "[${timeFormatter.format(LocalDateTime.now())}] $message"
        if (!queue.offer(QueuedLine(packageName, day, line, enqueueSeq.incrementAndGet()))) {
            val total = droppedCount.incrementAndGet()
            if (total - lastWarnedDropped.get() >= DROP_WARN_INTERVAL) {
                lastWarnedDropped.set(total)
                Log.w(TAG, "日志队列已满（容量 $QUEUE_CAPACITY），已累计丢弃 $total 条（高频日志或磁盘拥塞）")
            }
        }
    }

    /** 单写线程主循环：摘队 → 批量写 → 按 [FLUSH_BATCH]/[FLUSH_INTERVAL_MS] 触发 flush */
    private fun writerLoop() {
        var pending = 0 // 距上次 flush 以来已写入的行数
        while (true) {
            try {
                // 时间窗兜底：静默 FLUSH_INTERVAL_MS 则先补 flush 残余批次，再继续等待
                val first = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)
                if (first == null) {
                    if (pending > 0) {
                        flushAll()
                        pending = 0
                    }
                    continue
                }
                writeLine(first)
                pending++
                var drained = 0
                while (drained < MAX_DRAIN_PER_CYCLE) {
                    val next = queue.poll() // 非阻塞，尽量凑批
                    if (next == null) break
                    writeLine(next)
                    pending++
                    drained++
                }
                if (pending >= FLUSH_BATCH) {
                    flushAll()
                    pending = 0
                }
            } catch (_: InterruptedException) {
                break
            } catch (t: Throwable) {
                Log.e(TAG, "日志写入循环异常", t)
            }
        }
    }

    /** 写入一行到对应包的当日文件（不 flush；跨天/首次自动关旧建新）；失败时关闭坏 writer 并丢弃该行 */
    private fun writeLine(queued: QueuedLine) {
        // 清空水位防御（在途队列回潮）：seq ≤ 水位的行属于清除前入队，直接丢弃——否则 clear
        // 删目录后本线程会 mkdir+重建文件把这些旧行写回。判定在消费侧（单写线程）串行执行，
        // 与 clear 写入侧无锁竞争（ConcurrentHashMap 安全发布任意时刻水位）。
        val wm = clearWatermarks[queued.packageName]
        if (wm != null && queued.seq <= wm) {
            return
        }
        val root = rootDir ?: run {
            Log.w(TAG, "LogStore 未初始化，丢弃日志: ${queued.packageName}")
            return
        }
        try {
            val existing = openWriters[queued.packageName]
            val entry: OpenWriter
            if (existing != null && existing.day == queued.day) {
                entry = existing
            } else {
                existing?.let { old ->
                    runCatching { old.writer.close() }
                }
                openWriters.remove(queued.packageName)
                val dir = File(root, queued.packageName)
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.w(TAG, "无法创建日志目录: $dir")
                    return
                }
                val file = File(dir, "${queued.day}.log")
                val writer = BufferedWriter(
                    OutputStreamWriter(FileOutputStream(file, true), StandardCharsets.UTF_8)
                )
                // lines 初始化为文件既有行数（追加模式，覆盖进程重启后续写场景），保证裁剪阈值准确
                entry = OpenWriter(queued.day, writer, countLines(file))
                openWriters[queued.packageName] = entry
            }
            entry.writer.write(queued.line)
            entry.writer.newLine()
            entry.lines++
            // 保留策略：超过上限 → 保留尾部最近一半行重写（防磁盘无限增长；同线程内执行，无并发）
            if (entry.lines > MAX_LINES_PER_FILE) {
                trimToTail(queued.packageName, entry, queued.day)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "写入日志失败 pkg=${queued.packageName}", t)
            // 关闭损坏的 writer，下一次写入时重建（磁盘恢复后自愈）
            openWriters.remove(queued.packageName)?.let { runCatching { it.writer.close() } }
        }
    }

    /** 统计文件行数（写入器创建时初始化阈值计数；文件不存在/读失败返回 0） */
    private fun countLines(file: File): Int {
        var n = 0
        try {
            file.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                while (reader.readLine() != null) n++
            }
        } catch (_: Throwable) {
            return 0
        }
        return n
    }

    /**
     * 保留策略裁剪（仅单写线程调用，经 [fileOpsLock] 与 [clear] 互斥）：
     * 关闭当前追加 writer（先 flush）→ 读当日文件尾部最近一半行 → 写同目录临时文件 →
     * rename 原子替换（读侧 readHistory 不会看到截断中间态）→ 重建 writer 续写。
     * 与 clear 互斥的原因：clear 删除目录时若裁剪正重建文件，会删除失败/内容回潮。
     */
    private fun trimToTail(pkg: String, entry: OpenWriter, day: String) {
        synchronized(fileOpsLock) {
            runCatching { entry.writer.flush() }
            runCatching { entry.writer.close() }
            openWriters.remove(pkg)
            val root = rootDir ?: return
            val file = File(File(root, pkg), "$day.log")
            if (!file.isFile) return
            val keep = MAX_LINES_PER_FILE / 2
            val tail = ArrayDeque<String>(keep)
            try {
                file.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        tail.addLast(line)
                        if (tail.size > keep) tail.removeFirst()
                        line = reader.readLine()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "裁剪日志读取失败（本轮跳过，后续行继续追加）: ${file.name}", t)
                return
            }
            // 原子写：先落同目录临时文件（readHistory 的 *.log 过滤天然排除 *.log.tmp），
            // 写满 flush+close 后 rename 替换——读侧要么读到旧完整文件、要么读到新完整文件。
            val tmp = File(File(root, pkg), "$day.log.tmp")
            val writer = try {
                BufferedWriter(OutputStreamWriter(FileOutputStream(tmp, false), StandardCharsets.UTF_8))
            } catch (t: Throwable) {
                Log.w(TAG, "裁剪日志重建临时文件失败: ${tmp.name}", t)
                return
            }
            try {
                tail.forEach { writer.write(it); writer.newLine() }
                writer.flush()
                writer.close()
            } catch (t: Throwable) {
                runCatching { writer.close() }
                runCatching { tmp.delete() }
                Log.w(TAG, "裁剪日志重写失败: ${tmp.name}", t)
                return
            }
            if (!tmp.renameTo(file)) {
                runCatching { tmp.delete() }
                Log.w(TAG, "裁剪日志原子替换失败（本轮跳过，下次写入重试）: ${file.name}")
                return
            }
            val fresh = try {
                BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), StandardCharsets.UTF_8))
            } catch (t: Throwable) {
                Log.w(TAG, "裁剪后重建写入器失败: ${file.name}", t)
                return
            }
            openWriters[pkg] = OpenWriter(day, fresh, tail.size)
        }
    }

    /** 刷新所有打开的 writer；单个失败只关闭该包 writer，不影响其他包 */
    private fun flushAll() {
        val iterator = openWriters.entries.iterator()
        while (iterator.hasNext()) {
            val (pkg, entry) = iterator.next()
            try {
                entry.writer.flush()
            } catch (t: Throwable) {
                Log.e(TAG, "flush 失败 pkg=$pkg", t)
                runCatching { entry.writer.close() }
                iterator.remove()
            }
        }
    }

    /**
     * 清除某包全部持久化日志：记录清空水位 + 关闭打开中的 writer 并删除 `files/logs/<pkg>/` 整个目录。
     * 与写线程并发安全：先移除/关闭 writer 再删目录；清理后继续到达的新日志会重建文件
     * （语义 = 只清旧日志，实时流不受影响）。
     * 水位 = 清除时刻的入队序号：写线程丢弃 seq ≤ 水位的该包行，杜绝"删目录后写线程又把
     * 清除前入队的行重建文件"（在途队列回潮）。
     * 经 [fileOpsLock] 与 [trimToTail] 互斥：清除后不会因裁剪重写而回潮。
     */
    fun clear(packageName: String) {
        val root = rootDir ?: return
        synchronized(fileOpsLock) {
            // 先记水位再删文件：水位之后的入队行才允许落盘（与写侧无锁，边界瞬时行与清除
            // 同刻"在途"，语义上允许轻微偏差；整段清除前历史行严格丢弃）
            clearWatermarks[packageName] = enqueueSeq.get()
            openWriters.remove(packageName)?.let { runCatching { it.writer.close() } }
            val dir = File(root, packageName)
            if (dir.exists()) {
                dir.listFiles()?.forEach { runCatching { it.delete() } }
                runCatching { dir.delete() }
            }
        }
        Log.i(TAG, "已清除日志 pkg=$packageName")
    }

    /** 异步清除（与页面生命周期解耦）：UI 点击清除后即使立即返回/销毁也保证真清除落盘。 */
    fun clearAsync(packageName: String) {
        ioScope.launch { clear(packageName) }
    }

    /**
     * 读取某包历史日志：`files/logs/<pkg>/` 下所有 `<yyyy-MM-dd>.log` 按文件名（日期）升序合并，
     * 返回最近 [limit] 行（时间正序，行内自带时间戳）。
     * 内存有界：逐行读入并只保留尾部 [limit] 行。
     */
    fun readHistory(packageName: String, limit: Int = 500): List<String> {
        if (limit <= 0) return emptyList()
        val root = rootDir ?: return emptyList()
        val dir = File(root, packageName)
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.sortedBy { it.name }
            ?: return emptyList()

        // 按日期升序逐文件取「该文件尾部」，再全局修剪为最近 limit 行
        val result = ArrayDeque<String>(limit)
        for (file in files) {
            val tail = tailLines(file, limit)
            result.addAll(tail)
            while (result.size > limit) result.removeFirst()
        }
        return result.toList()
    }

    /** 读单个日志文件的尾部 [limit] 行（时间正序），文件不存在/损坏时返回空列表 */
    private fun tailLines(file: File, limit: Int): List<String> {
        val deque = ArrayDeque<String>(limit)
        try {
            file.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    deque.addLast(line)
                    if (deque.size > limit) deque.removeFirst()
                    line = reader.readLine()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读取日志文件失败: ${file.name}", t)
        }
        return deque.toList()
    }
}
