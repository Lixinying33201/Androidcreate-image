package com.micu.studio

import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日志级别：INFO 一般流程 / OK 成功 / WARN 警告 / ERR 错误 / DBG 调试细节 */
enum class LogLevel { INFO, OK, WARN, ERR, DBG }

/** 一条实时日志：时间 + 级别 + 内容 */
data class LogEntry(val time: String, val level: LogLevel, val message: String)

/**
 * 内存环形日志仓库：供设置页"实时日志"面板读取。
 * - SnapshotStateList 驱动 Compose UI 实时刷新，新日志写入立即上屏并自动滚动到底；
 * - 上限 MAX 条，超出后丢弃最旧，避免长时间运行内存膨胀；
 * - 多线程（IO 线程打日志 / 主线程读 UI）由 synchronized 保护写入。
 */
object LogStore {

    private const val MAX = 600
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    val entries = mutableStateListOf<LogEntry>()

    @Synchronized
    private fun log(level: LogLevel, message: String) {
        entries.add(LogEntry(fmt.format(Date()), level, message))
        while (entries.size > MAX) entries.removeAt(0)
    }

    fun info(message: String) = log(LogLevel.INFO, message)
    fun ok(message: String) = log(LogLevel.OK, message)
    fun warn(message: String) = log(LogLevel.WARN, message)
    fun err(message: String) = log(LogLevel.ERR, message)
    fun debug(message: String) = log(LogLevel.DBG, message)

    @Synchronized
    fun clear() = entries.clear()
}
