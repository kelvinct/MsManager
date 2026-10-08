package com.example.manager.utils

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

object RoomLock {
    private const val TAG = "RoomLock"
    private const val LOCK_DURATION_MS = 5 * 60 * 1000L  // 5 分鐘
    private const val COOLDOWN_MS = 30 * 1000L            // 30 秒冷卻
    private const val CACHE_FILE = "locks_cache.json"

    data class LockInfo(
        val holderDeviceId: String = "",
        val holderDeviceName: String = "",
        val lastHolderDeviceId: String = "",
        val accountName: String = "",
        val gameId: String = "",
        val lockedAt: Long = 0L,
        val expiresAt: Long = 0L,
        val releasedAt: Long = 0L
    ) {
        fun isValid(): Boolean = System.currentTimeMillis() < expiresAt
    }

    private fun lockDocId(gameId: String, accountName: String) = "${gameId}_${accountName}"

    // ==================== 本地快取 ====================

    fun saveLocalCache(context: Context, locks: Map<String, LockInfo>) {
        try {
            val json = JSONObject()
            locks.forEach { (name, lock) ->
                val obj = JSONObject().apply {
                    put("holderDeviceId", lock.holderDeviceId)
                    put("holderDeviceName", lock.holderDeviceName)
                    put("lastHolderDeviceId", lock.lastHolderDeviceId)
                    put("accountName", lock.accountName)
                    put("gameId", lock.gameId)
                    put("lockedAt", lock.lockedAt)
                    put("expiresAt", lock.expiresAt)
                    put("releasedAt", lock.releasedAt)
                }
                json.put(name, obj)
            }
            File(context.cacheDir, CACHE_FILE).writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "保存快取失敗", e)
        }
    }

    fun loadLocalCache(context: Context): Map<String, LockInfo> {
        return try {
            val file = File(context.cacheDir, CACHE_FILE)
            if (!file.exists()) return emptyMap()
            val json = JSONObject(file.readText())
            val result = mutableMapOf<String, LockInfo>()
            json.keys().forEach { key ->
                val obj = json.getJSONObject(key)
                val lock = LockInfo(
                    holderDeviceId = obj.optString("holderDeviceId", ""),
                    holderDeviceName = obj.optString("holderDeviceName", ""),
                    lastHolderDeviceId = obj.optString("lastHolderDeviceId", ""),
                    accountName = obj.optString("accountName", key),
                    gameId = obj.optString("gameId", ""),
                    lockedAt = obj.optLong("lockedAt", 0),
                    expiresAt = obj.optLong("expiresAt", 0),
                    releasedAt = obj.optLong("releasedAt", 0)
                )
                // 只保留仍有效嘅鎖
                if (lock.isValid() && lock.holderDeviceId.isNotEmpty()) {
                    result[key] = lock
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "讀取快取失敗", e)
            emptyMap()
        }
    }

    // ==================== 上鎖 ====================

    suspend fun tryLock(
        db: FirebaseFirestore,
        roomCode: String,
        myDeviceId: String,
        myDeviceName: String,
        accountName: String,
        gameId: String
    ): String? {
        return suspendCancellableCoroutine { cont ->
            val docId = lockDocId(gameId, accountName)
            val lockRef = db.collection("rooms").document(roomCode)
                .collection("locks").document(docId)

            db.runTransaction { transaction ->
                val snapshot = transaction.get(lockRef)
                val existing = snapshot.toObject(LockInfo::class.java)
                val now = System.currentTimeMillis()

                // 1. 檢查此帳號是否被其他設備持有
                if (existing != null && existing.isValid()
                    && existing.holderDeviceId != myDeviceId) {
                    throw IllegalStateException("LOCKED_BY|${existing.holderDeviceName}|$accountName")
                }

                // 2. 檢查冷卻期
                if (existing != null
                    && existing.releasedAt > 0
                    && existing.lastHolderDeviceId != myDeviceId
                    && now - existing.releasedAt < COOLDOWN_MS) {
                    val remaining = (COOLDOWN_MS - (now - existing.releasedAt)) / 1000 + 1
                    throw IllegalStateException("COOLDOWN|$remaining")
                }

                // 3. 上鎖
                val newLock = LockInfo(
                    holderDeviceId = myDeviceId,
                    holderDeviceName = myDeviceName,
                    lastHolderDeviceId = myDeviceId,
                    accountName = accountName,
                    gameId = gameId,
                    lockedAt = now,
                    expiresAt = now + LOCK_DURATION_MS,
                    releasedAt = 0L
                )
                transaction.set(lockRef, newLock)
                null
            }.addOnSuccessListener {
                Log.d(TAG, "上鎖成功: $accountName by $myDeviceName")
                if (cont.isActive) cont.resume(null)
            }.addOnFailureListener { e ->
                val msg = e.message ?: "UNKNOWN"
                Log.w(TAG, "上鎖失敗: $msg")
                if (cont.isActive) cont.resume(msg)
            }
        }
    }

    // ==================== 解鎖 ====================

    suspend fun unlock(
        db: FirebaseFirestore,
        roomCode: String,
        myDeviceId: String,
        accountName: String,
        gameId: String
    ): Boolean {
        return suspendCancellableCoroutine { cont ->
            val docId = lockDocId(gameId, accountName)
            val lockRef = db.collection("rooms").document(roomCode)
                .collection("locks").document(docId)

            db.runTransaction { transaction ->
                val snapshot = transaction.get(lockRef)
                val existing = snapshot.toObject(LockInfo::class.java)

                if (existing != null && existing.holderDeviceId == myDeviceId) {
                    val updated = existing.copy(
                        lastHolderDeviceId = myDeviceId,
                        holderDeviceId = "",
                        holderDeviceName = "",
                        expiresAt = 0L,
                        releasedAt = System.currentTimeMillis()
                    )
                    transaction.set(lockRef, updated)
                    true
                } else {
                    false
                }
            }.addOnSuccessListener {
                Log.d(TAG, "解鎖成功: $accountName")
                if (cont.isActive) cont.resume(true)
            }.addOnFailureListener {
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    // ==================== 監聽 ====================

    fun listenLocks(
        db: FirebaseFirestore,
        roomCode: String,
        gameId: String,
        onLocksChanged: (Map<String, LockInfo>) -> Unit
    ): ListenerRegistration {
        return db.collection("rooms").document(roomCode)
            .collection("locks")
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e(TAG, "監聽鎖失敗", e)
                    return@addSnapshotListener
                }
                val locks = mutableMapOf<String, LockInfo>()
                snapshot?.documents?.forEach { doc ->
                    val lock = doc.toObject(LockInfo::class.java)
                    if (lock != null
                        && lock.isValid()
                        && lock.holderDeviceId.isNotEmpty()
                        && lock.gameId == gameId) {
                        locks[lock.accountName] = lock
                    }
                }
                Log.d(TAG, "鎖集合更新: ${locks.size} 個帳號被鎖")
                onLocksChanged(locks)
            }
    }
}