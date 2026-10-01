package com.example.manager.models

import com.example.manager.Constant
import java.io.File
import java.util.Calendar

class AccountModel {
    private var name: String = ""
    private var frequency: Int = 0
    private var createDate: Long = 0L
    private var lastUsedTime: Long = 0L

    // 20260930 add cloud
    var cloudId: String = ""            // Firestore 文档 ID
    var gameId: String = "monst_tw"     // 游戏标识：台版=monst_tw，日版=monst_jp
    var isCloudSynced: Boolean = false  // 是否开启云同步

    fun getName() = name
    fun setName(v: String) { name = v }
    fun getFrequency() = frequency
    fun setFrequency(v: Int) { frequency = v }
    fun getCreateDate() = createDate
    fun setCreateDate(v: Long) { createDate = v }
    fun getLastUsedTime() = lastUsedTime
    fun setLastUsedTime(v: Long) { lastUsedTime = v }

    // 顺序：name, frequency, createDate, lastUsedTime
    fun toFullName(): String = "$name${Constant.SEPARATOR}$frequency${Constant.SEPARATOR}$createDate${Constant.SEPARATOR}$lastUsedTime"

    fun isUsedToday(): Boolean {
        if (lastUsedTime == 0L) return false
        val now = Calendar.getInstance()
        val reset = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 4); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (now.get(Calendar.HOUR_OF_DAY) < 4) add(Calendar.DAY_OF_YEAR, -1)
        }
        return lastUsedTime > reset.timeInMillis
    }

    companion object {
        fun fromFullName(fullName: String): AccountModel {
            val model = AccountModel()
            try {
                val fileName = File(fullName).name.replace(".bin", "")
                val parts = fileName.split(Constant.SEPARATOR)
                if (parts.size >= 4) {
                    model.setName(parts[0])
                    model.setFrequency(parts[1].toIntOrNull() ?: 0)
                    model.setCreateDate(parts[2].toLongOrNull() ?: 0L)
                    model.setLastUsedTime(parts[3].toLongOrNull() ?: 0L)
                } else { model.setName(fileName) }
            } catch (e: Exception) { model.setName(fullName) }
            return model
        }
    }
}