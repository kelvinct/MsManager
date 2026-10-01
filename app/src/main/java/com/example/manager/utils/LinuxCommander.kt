package com.example.manager.utils

import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object LinuxCommander {
    private const val TAG = "MS_COMMANDER"

    suspend fun executeWithResult(command: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val output = StringBuilder()
        try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()

            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
            }

            val finished: Boolean
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                finished = process.waitFor(20, TimeUnit.SECONDS) // 20秒超时
            } else {
                process.waitFor() // API 24/25 兼容
                finished = true
            }

            if (finished) {
                Pair(process.exitValue() == 0, output.toString())
            } else {
                process.destroy()
                Pair(false, "Error: Command timed out.\n$output")
            }
        } catch (e: Exception) {
            Log.e(TAG, "指令失败: $command", e)
            Pair(false, "Exception: ${e.message}\n$output")
        }
    }
}