package com.example.manager.utils

import android.text.TextUtils
import java.util.Locale
import java.util.regex.Pattern

object CastUtils {
    private val zzaeb = Pattern.compile("urn:x-cast:[-A-Za-z0-9_]+(\\.[-A-Za-z0-9_]+)*")

    // 將長整型毫秒轉為秒
    fun zzA(j: Long): Double {
        return j / 1000.0
    }

    // 安全的對象對比
    fun <T> zza(t: T?, t2: T?): Boolean {
        return (t == null && t2 == null) || (t != null && t2 != null && t == t2)
    }

    // 格式化 Locale (例如 zh-TW)
    fun zzb(locale: Locale): String {
        val sb = StringBuilder(20)
        sb.append(locale.language)
        val country = locale.country
        if (!TextUtils.isEmpty(country)) {
            sb.append('-').append(country)
        }
        val variant = locale.variant
        if (!TextUtils.isEmpty(variant)) {
            sb.append('-').append(variant)
        }
        return sb.toString()
    }

    private fun zzb(c: Char): Boolean {
        return (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '_' || c == '-'
    }

    // 驗證 Namespace 格式
    @Throws(IllegalArgumentException::class)
    fun zzch(str: String?) {
        if (TextUtils.isEmpty(str)) {
            throw IllegalArgumentException("Namespace cannot be null or empty")
        }
        if (str!!.length > 128) {
            throw IllegalArgumentException("Invalid namespace length")
        }
        if (!str.startsWith("urn:x-cast:")) {
            throw IllegalArgumentException("Namespace must begin with the prefix \"urn:x-cast:\"")
        }
        if (str.length == "urn:x-cast:".length) {
            throw IllegalArgumentException("Namespace must begin with the prefix \"urn:x-cast:\" and have non-empty suffix")
        }
    }

    // 加上 urn:x-cast: 前綴
    fun zzci(str: String): String {
        return "urn:x-cast:$str"
    }

    // 將秒轉回毫秒
    fun zzg(d: Double): Long {
        return (1000.0 * d).toLong()
    }
}