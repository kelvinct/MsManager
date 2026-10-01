package com.example.manager.utils

import androidx.compose.runtime.mutableStateListOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object R2Logger {
    val logs = mutableStateListOf<String>()

    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun log(tag: String, message: String) {
        val time = sdf.format(Date())
        val line = "[$time] [$tag] $message"
        logs.add(line)
        if (logs.size > 500) logs.removeAt(0)
        android.util.Log.d("R2Logger", line)
    }

    fun error(tag: String, message: String, e: Throwable? = null) {
        val time = sdf.format(Date())
        val stack = e?.stackTraceToString()?.take(2000) ?: ""
        val line = "[$time] [$tag] ❌ $message\n$stack"
        logs.add(line)
        if (logs.size > 500) logs.removeAt(0)
        android.util.Log.e("R2Logger", line, e)
    }

    fun clear() {
        logs.clear()
    }

    /**
     * 把日志保存到文件
     * @return 保存的完整路径，失败返回 null
     */
    fun saveToFile(): String? {
        return try {
            // 保存到 /sdcard/Download/MSManager_R2Log_时间戳.txt
            val dir = File("/sdcard/Download")
            if (!dir.exists()) dir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(dir, "MSManager_R2Log_$timestamp.txt")

            val header = buildString {
                appendLine("========== MS Manager R2 Log ==========")
                appendLine("导出时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                appendLine("日志条数: ${logs.size}")
                appendLine("=======================================")
                appendLine()
            }

            file.writeText(header + logs.joinToString("\n\n"))

            android.util.Log.d("R2Logger", "日志已保存: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("R2Logger", "保存日志失败", e)
            null
        }
    }
}