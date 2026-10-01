package com.example.manager

import android.os.Environment

object Constant {
    const val DATA10 = "data10.bin"
    const val DATA13 = "data13.bin"
    const val SEPARATOR = "%%" // 解決名稱解析問題的關鍵分隔符

    fun getSourcePath(isTW: Boolean) = if (isTW)
        "/data/data/jp.co.mixi.monsterstrikeTW" else "/data/data/jp.co.mixi.monsterstrike"

    fun getBackupPath(isTW: Boolean) =
        "${Environment.getExternalStorageDirectory()}/msaccount2/${if (isTW) "tw" else "jp"}"
}
