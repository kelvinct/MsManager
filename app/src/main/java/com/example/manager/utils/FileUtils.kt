package com.example.manager.utils

import android.util.Log
import java.io.*

object FileUtils {
    private const val TAG = "MS_FileUtils"

    /**
     * 複製檔案 (使用 BufferedStream 提高效能)
     */
    @Throws(IOException::class)
    fun copy(fileFrom: String, fileTo: String) {
        Log.d(TAG, "開始複製任務: [來源] $fileFrom -> [目的地] $fileTo")
        val ff = File(fileFrom)

        if (ff.exists()) {
            val startTime = System.currentTimeMillis()
            try {
                FileInputStream(ff).buffered().use { bis ->
                    FileOutputStream(fileTo).buffered().use { bos ->
                        val buffer = ByteArray(1024 * 8) // 8KB 緩衝區
                        var len: Int
                        var totalBytes = 0L

                        while (bis.read(buffer).also { len = it } != -1) {
                            bos.write(buffer, 0, len)
                            totalBytes += len
                        }
                        bos.flush()

                        val endTime = System.currentTimeMillis()
                        Log.i(TAG, "複製成功! 大小: ${totalBytes / 1024} KB, 耗時: ${endTime - startTime}ms")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "複製失敗: ${e.message}")
                throw e
            }
        } else {
            Log.e(TAG, "失敗: 來源檔案不存在 ($fileFrom)")
        }
    }

    /**
     * 遞迴刪除檔案或資料夾
     */
    fun delete(filePath: String) {
        val f = File(filePath)
        if (f.isDirectory) {
            Log.d(TAG, "正在清理目錄: $filePath")
            f.listFiles()?.forEach { delete(it.absolutePath) }
        }
        val result = f.delete()
        Log.v(TAG, "刪除檔案: $filePath (結果: $result)")
    }

    /**
     * 取得清單並建立目錄
     */
    fun getListAndMkdirs(fileFolder: String): Array<String>? {
        val file = File(fileFolder)
        if (!file.exists()) {
            val result = file.mkdirs()
            Log.d(TAG, "建立目錄: $fileFolder (成功: $result)")
        }
        return file.list().also {
            Log.d(TAG, "讀取目錄清單: $fileFolder, 檔案數: ${it?.size ?: 0}")
        }
    }

    /**
     * 重新命名
     */
    fun rename(file: String, newFile: String): Boolean {
        val f = File(file)
        val result = f.renameTo(File(newFile))
        Log.i(TAG, "重新命名: $file -> $newFile (結果: $result)")
        return result
    }

    /**
     * 檢查檔案是否存在
     */
    fun isExist(file: String): Boolean {
        val exists = File(file).exists()
        Log.v(TAG, "檢查檔案是否存在: $file -> $exists")
        return exists
    }
}