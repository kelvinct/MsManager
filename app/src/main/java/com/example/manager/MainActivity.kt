package com.example.manager

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.manager.models.AccountModel
import com.example.manager.utils.LinuxCommander
import com.example.manager.utils.R2Logger
import com.example.manager.utils.R2Storage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume

data class DeviceSelection(
    val deviceId: String = "",
    val deviceName: String = "",
    val selectedAccount: String = "",
    val gameId: String = "",
    val selectedTime: Long = 0L
)

class MainActivity : ComponentActivity() {

    private var isProcessing = false
    var lastOperationLog by mutableStateOf("暂无日志")
    var transferProgress by mutableStateOf("")

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var cloudListener: ListenerRegistration? = null
    private var devicesListener: ListenerRegistration? = null

    var cloudState by mutableStateOf<Map<String, AccountModel>>(emptyMap())
    var allDeviceSelections by mutableStateOf<List<DeviceSelection>>(emptyList())

    lateinit var myDeviceId: String
    lateinit var myDeviceName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkStoragePermission()

        R2Storage.init(this)

        val prefs = getSharedPreferences("MS_PREFS", MODE_PRIVATE)
        myDeviceId = prefs.getString("DEVICE_ID", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("DEVICE_ID", it).apply()
        }
        myDeviceName = prefs.getString("DEVICE_NAME", null) ?: Build.MODEL.also {
            prefs.edit().putString("DEVICE_NAME", it).apply()
        }

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        if (auth.currentUser == null) {
            auth.signInAnonymously().addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d("Firebase", "匿名登录成功: ${auth.currentUser?.uid}")
                    R2Logger.log("Firebase", "匿名登录成功")
                } else {
                    Log.e("Firebase", "匿名登录失败", task.exception)
                    R2Logger.error("Firebase", "匿名登录失败", task.exception)
                }
            }
        }

        setContent { MaterialTheme { FileManagerScreen() } }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } else {
            val permissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
                ActivityCompat.requestPermissions(this, permissions, 100)
            }
        }
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
    private fun bytesToInt(b: ByteArray): Int = if (b.size < 4) 0 else (b[0].toInt() shl 24) or ((b[1].toInt() and 0xff) shl 16) or ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)

    // ==================== 本地备份与恢复 ====================

    fun saveMergedAccount(name: String?, existingFullName: String?, isTW: Boolean, onComplete: (Boolean) -> Unit) {
        if (isProcessing) return
        isProcessing = true

        val srcDir = Constant.getSourcePath(isTW)
        val root = Constant.getBackupPath(isTW)
        val pkg = if (isTW) "jp.co.mixi.monsterstrikeTW" else "jp.co.mixi.monsterstrike"
        val model = if (existingFullName != null) AccountModel.fromFullName(existingFullName)
        else AccountModel().apply { setName(name ?: "New"); setCreateDate(System.currentTimeMillis()) }
        val targetFile = File(root, model.toFullName())
        val cache10 = File(cacheDir, "c10_${System.currentTimeMillis()}")
        val cache13 = File(cacheDir, "c13_${System.currentTimeMillis()}")

        lifecycleScope.launch(Dispatchers.IO) {
            var success = false
            val logMsg = StringBuilder()
            try {
                File(root).mkdirs()
                val cmd = "cp $srcDir/${Constant.DATA10} ${cache10.absolutePath}\n" +
                        "cp $srcDir/${Constant.DATA13} ${cache13.absolutePath}\n" +
                        "chmod 666 ${cache10.absolutePath} ${cache13.absolutePath}\n" +
                        "am force-stop $pkg"

                val (cmdSuccess, cmdLog) = LinuxCommander.executeWithResult(cmd)
                logMsg.append("执行命令:\n$cmd\n\n日志:\n$cmdLog")
                if (!cmdSuccess) return@launch

                if (cache10.exists()) {
                    FileOutputStream(targetFile).use { out ->
                        val b10 = cache10.readBytes()
                        out.write(intToBytes(b10.size)); out.write(b10)
                        val b13 = if (cache13.exists()) cache13.readBytes() else byteArrayOf()
                        out.write(intToBytes(b13.size)); out.write(b13)
                    }
                    success = true

                    val roomCode = getSharedPreferences("MS_PREFS", MODE_PRIVATE).getString("ROOM_CODE", "") ?: ""
                    if (roomCode.isNotEmpty()) {
                        val gameId = if (isTW) "monst_tw" else "monst_jp"
                        uploadFullAccountToCloud(roomCode, model, gameId, targetFile) { }
                    }
                } else { logMsg.append("\n缓存文件不存在，游戏可能未运行过。") }
            } catch (e: Exception) { logMsg.append("\n异常: ${e.message}")
            } finally {
                if (cache10.exists()) cache10.delete()
                if (cache13.exists()) cache13.delete()
                isProcessing = false
                withContext(Dispatchers.Main) {
                    lastOperationLog = logMsg.toString()
                    if (!success) AlertDialog.Builder(this@MainActivity).setTitle("备份失败").setMessage(logMsg.toString()).setPositiveButton("确定", null).show()
                    onComplete(success)
                }
            }
        }
    }

    fun switchMergedAccount(fullName: String, isTW: Boolean, onComplete: (Boolean) -> Unit) {
        if (isProcessing) { Toast.makeText(this, "正在处理，请勿重复点击", Toast.LENGTH_SHORT).show(); return }
        isProcessing = true

        val root = Constant.getBackupPath(isTW)
        val srcDir = Constant.getSourcePath(isTW)
        val pkg = if (isTW) "jp.co.mixi.monsterstrikeTW" else "jp.co.mixi.monsterstrike"
        val backupFile = File(root, fullName)
        val cache10 = File(cacheDir, "r10_${System.currentTimeMillis()}")
        val cache13 = File(cacheDir, "r13_${System.currentTimeMillis()}")

        lifecycleScope.launch(Dispatchers.IO) {
            var success = false
            val logMsg = StringBuilder()
            try {
                if (!backupFile.exists()) {
                    val roomCode = getSharedPreferences("MS_PREFS", MODE_PRIVATE).getString("ROOM_CODE", "") ?: ""
                    if (roomCode.isEmpty()) {
                        logMsg.append("备份文件不存在，且未设置房间号，无法从云端下载")
                        return@launch
                    }
                    val gameId = if (isTW) "monst_tw" else "monst_jp"
                    val model = AccountModel.fromFullName(fullName)
                    withContext(Dispatchers.Main) { transferProgress = "正在从云端下载 ${model.getName()} ..." }
                    val downloaded = downloadFileFromStorage(roomCode, gameId, model.getName(), backupFile)
                    withContext(Dispatchers.Main) { transferProgress = "" }
                    if (!downloaded) {
                        logMsg.append("从云端下载失败，请查看☁️日志")
                        return@launch
                    }
                    logMsg.append("已从云端下载成功\n")
                }

                FileInputStream(backupFile).use { inp ->
                    val head = ByteArray(4); if (inp.read(head) != 4) throw IOException("文件头读取失败")
                    val len10 = bytesToInt(head)
                    if (len10 in 1 until backupFile.length().toInt()) {
                        val b10 = ByteArray(len10); var read = 0
                        while (read < len10) { read += inp.read(b10, read, len10 - read) }
                        cache10.writeBytes(b10)
                        val head2 = ByteArray(4)
                        if (inp.read(head2) == 4) {
                            val len13 = bytesToInt(head2)
                            if (len13 > 0) {
                                val b13 = ByteArray(len13); read = 0
                                while (read < len13) { read += inp.read(b13, read, len13 - read) }
                                cache13.writeBytes(b13)
                            }
                        }
                    } else { cache10.writeBytes(backupFile.readBytes()) }
                }

                val has13 = if (cache13.exists()) "cp ${cache13.absolutePath} $srcDir/${Constant.DATA13}" else "true"
                val cmd = "am force-stop $pkg\n" +
                        "sleep 1.5\n" +
                        "cp ${cache10.absolutePath} $srcDir/${Constant.DATA10}\n" +
                        "$has13\n" +
                        "APP_UID=\$(stat -c %u /data/data/$pkg)\n" +
                        "APP_GID=\$(stat -c %g /data/data/$pkg)\n" +
                        "chown \$APP_UID:\$APP_GID $srcDir/${Constant.DATA10}\n" +
                        "chmod 600 $srcDir/${Constant.DATA10}\n" +
                        "restorecon $srcDir/${Constant.DATA10}\n" +
                        "chown \$APP_UID:\$APP_GID $srcDir/${Constant.DATA13}\n" +
                        "chmod 600 $srcDir/${Constant.DATA13}\n" +
                        "restorecon $srcDir/${Constant.DATA13}\n" +
                        "echo \"RESTORE_DONE\""

                logMsg.append("执行命令:\n$cmd\n\n日志:\n")
                val (cmdSuccess, cmdLog) = LinuxCommander.executeWithResult(cmd)
                logMsg.append(cmdLog)

                if (cmdSuccess && cmdLog.contains("RESTORE_DONE")) {
                    val m = AccountModel.fromFullName(fullName).apply {
                        setFrequency(getFrequency() + 1)
                        setLastUsedTime(System.currentTimeMillis())
                    }
                    val newFile = File(root, m.toFullName())
                    if (backupFile.renameTo(newFile)) {
                        getSharedPreferences("MS_PREFS", MODE_PRIVATE).edit()
                            .putString("LAST_${if (isTW) "TW" else "JP"}", m.getName()).apply()
                    }
                    success = true

                    val roomCode = getSharedPreferences("MS_PREFS", MODE_PRIVATE).getString("ROOM_CODE", "") ?: ""
                    val gameId = if (isTW) "monst_tw" else "monst_jp"
                    if (roomCode.isNotEmpty()) {
                        uploadFullAccountToCloud(roomCode, m, gameId, newFile) { }
                        updateMySelection(roomCode, gameId, m.getName())
                    }
                }
            } catch (e: Exception) { logMsg.append("\n异常: ${e.message}")
            } finally {
                if (cache10.exists()) cache10.delete()
                if (cache13.exists()) cache13.delete()
                isProcessing = false
                withContext(Dispatchers.Main) {
                    lastOperationLog = logMsg.toString()
                    if (!success) AlertDialog.Builder(this@MainActivity).setTitle("恢复失败，日志如下").setMessage(logMsg.toString()).setPositiveButton("确定", null).show()
                    onComplete(success)
                }
            }
        }
    }

    fun renameAccount(oldFullName: String, newName: String, isTW: Boolean, onComplete: () -> Unit) {
        val root = File(Constant.getBackupPath(isTW))
        val oldFile = File(root, oldFullName)
        val oldModel = AccountModel.fromFullName(oldFullName)
        val model = AccountModel.fromFullName(oldFullName).apply { setName(newName) }
        val newFile = File(root, model.toFullName())
        if (oldFile.exists() && oldFile.renameTo(newFile)) {
            val key = "LAST_${if(isTW) "TW" else "JP"}"
            val prefs = getSharedPreferences("MS_PREFS", MODE_PRIVATE)
            if (prefs.getString(key, "") == oldModel.getName()) prefs.edit().putString(key, newName).apply()
            onComplete()
        }
    }

    fun deleteAccount(fullName: String, isTW: Boolean, onComplete: () -> Unit) {
        File(Constant.getBackupPath(isTW), fullName).delete(); onComplete()
    }

    fun resetAllColors(isTW: Boolean, onComplete: () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            val root = File(Constant.getBackupPath(isTW))
            if (root.exists()) {
                root.listFiles()?.forEach { file ->
                    val model = AccountModel.fromFullName(file.name)
                    if (model.getLastUsedTime() != 0L) {
                        val newModel = AccountModel().apply {
                            setName(model.getName())
                            setFrequency(model.getFrequency())
                            setCreateDate(model.getCreateDate())
                            setLastUsedTime(0L)
                        }
                        val newFile = File(root, newModel.toFullName())
                        file.renameTo(newFile)

                        val roomCode = getSharedPreferences("MS_PREFS", MODE_PRIVATE).getString("ROOM_CODE", "") ?: ""
                        if (roomCode.isNotEmpty()) {
                            val gameId = if (isTW) "monst_tw" else "monst_jp"
                            uploadFullAccountToCloud(roomCode, newModel, gameId, newFile) { }
                        }
                    }
                }
            }
            getSharedPreferences("MS_PREFS", MODE_PRIVATE).edit()
                .remove("LAST_${if(isTW) "TW" else "JP"}").apply()
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    // ★★★ 新增：从云端移除本机设备记录 ★★★
    fun clearMyDeviceFromCloud(onComplete: (Boolean) -> Unit) {
        val roomCode = getSharedPreferences("MS_PREFS", MODE_PRIVATE).getString("ROOM_CODE", "") ?: ""
        if (roomCode.isEmpty()) {
            Toast.makeText(this, "请先设置房间号", Toast.LENGTH_SHORT).show()
            onComplete(false)
            return
        }
        db.collection("rooms").document(roomCode)
            .collection("devices").document(myDeviceId)
            .delete()
            .addOnSuccessListener {
                R2Logger.log("Settings", "已从云端移除本机设备记录: $myDeviceName")
                onComplete(true)
            }
            .addOnFailureListener { e ->
                R2Logger.error("Settings", "移除设备记录失败", e)
                onComplete(false)
            }
    }

    // ★★★ 新增：清理本地所有备份文件（tw + jp）★★★
    fun clearLocalBackups(onComplete: (Int) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            var deleteCount = 0
            val paths = listOf(
                Constant.getBackupPath(true),
                Constant.getBackupPath(false)
            )
            for (path in paths) {
                val dir = File(path)
                if (dir.exists()) {
                    dir.listFiles()?.forEach { file ->
                        if (file.isFile && file.delete()) {
                            deleteCount++
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                R2Logger.log("Settings", "清理本地备份: 删除了 $deleteCount 个文件")
                onComplete(deleteCount)
            }
        }
    }

    // ==================== R2 文件上传/下载 ====================

    private suspend fun uploadFileToStorage(roomCode: String, gameId: String, accountName: String, localFile: File): Boolean {
        val key = "rooms/$roomCode/files/${gameId}_${accountName}.bin"
        return R2Storage.uploadFile(localFile, key)
    }

    private suspend fun downloadFileFromStorage(roomCode: String, gameId: String, accountName: String, targetFile: File): Boolean {
        val key = "rooms/$roomCode/files/${gameId}_${accountName}.bin"
        return R2Storage.downloadFile(key, targetFile)
    }

    fun uploadFullAccountToCloud(roomCode: String, account: AccountModel, gameId: String, localFile: File, onResult: (Boolean) -> Unit) {
        if (roomCode.isEmpty()) { onResult(false); return }

        account.gameId = gameId
        account.isCloudSynced = true
        val docId = "${gameId}_${account.getName()}"
        account.cloudId = docId

        lifecycleScope.launch(Dispatchers.IO) {
            val metaOk = suspendCancellableCoroutine<Boolean> { cont ->
                db.collection("rooms").document(roomCode)
                    .collection("accounts").document(docId)
                    .set(account)
                    .addOnSuccessListener { cont.resume(true) }
                    .addOnFailureListener { cont.resume(false) }
            }

            val fileOk = uploadFileToStorage(roomCode, gameId, account.getName(), localFile)

            withContext(Dispatchers.Main) {
                lastOperationLog = if (metaOk && fileOk) "已上传到云端: ${account.getName()}"
                else "上传失败: 元数据=$metaOk, 文件=$fileOk"
                onResult(metaOk && fileOk)
            }
        }
    }

    fun uploadAllToCloud(isTW: Boolean, onComplete: (Int, Int) -> Unit) {
        val roomCode = getSharedPreferences("MS_PREFS", MODE_PRIVATE).getString("ROOM_CODE", "") ?: ""
        if (roomCode.isEmpty()) {
            Toast.makeText(this, "请先设置房间号", Toast.LENGTH_SHORT).show()
            onComplete(0, 0)
            return
        }

        val root = File(Constant.getBackupPath(isTW))
        if (!root.exists()) {
            Toast.makeText(this, "本地没有备份文件", Toast.LENGTH_SHORT).show()
            onComplete(0, 0)
            return
        }

        val gameId = if (isTW) "monst_tw" else "monst_jp"
        val files = root.listFiles()?.filter { it.isFile } ?: emptyList()
        if (files.isEmpty()) {
            Toast.makeText(this, "本地没有备份文件", Toast.LENGTH_SHORT).show()
            onComplete(0, 0)
            return
        }

        R2Logger.log("Batch", "开始批量上传 ${files.size} 个文件到 R2")

        lifecycleScope.launch(Dispatchers.IO) {
            var successCount = 0
            var failCount = 0

            for ((index, file) in files.withIndex()) {
                try {
                    val model = AccountModel.fromFullName(file.name)
                    val docId = "${gameId}_${model.getName()}"
                    model.gameId = gameId
                    model.isCloudSynced = true
                    model.cloudId = docId

                    withContext(Dispatchers.Main) {
                        transferProgress = "正在上传 ${index + 1}/${files.size}: ${model.getName()}"
                    }

                    R2Logger.log("Batch", "--- 第 ${index + 1}/${files.size} 个: ${model.getName()} ---")

                    val metaOk = suspendCancellableCoroutine<Boolean> { cont ->
                        db.collection("rooms").document(roomCode)
                            .collection("accounts").document(docId)
                            .set(model)
                            .addOnSuccessListener { cont.resume(true) }
                            .addOnFailureListener { cont.resume(false) }
                    }

                    val fileOk = uploadFileToStorage(roomCode, gameId, model.getName(), file)

                    if (metaOk && fileOk) successCount++ else failCount++
                } catch (e: Exception) {
                    R2Logger.error("Batch", "批量上传失败: ${file.name}", e)
                    failCount++
                }
            }

            withContext(Dispatchers.Main) {
                transferProgress = ""
                lastOperationLog = "批量上传完成: 成功 $successCount 个，失败 $failCount 个"
                R2Logger.log("Batch", "批量上传结束: 成功=$successCount, 失败=$failCount")
                onComplete(successCount, failCount)
            }
        }
    }

    fun restoreAllFromCloud(isTW: Boolean, onProgress: (Int, Int, String) -> Unit, onComplete: (Int, Int, Int) -> Unit) {
        val roomCode = getSharedPreferences("MS_PREFS", MODE_PRIVATE).getString("ROOM_CODE", "") ?: ""
        if (roomCode.isEmpty()) {
            Toast.makeText(this, "请先设置房间号", Toast.LENGTH_SHORT).show()
            onComplete(0, 0, 0)
            return
        }

        val gameId = if (isTW) "monst_tw" else "monst_jp"
        val root = File(Constant.getBackupPath(isTW))
        if (!root.exists()) root.mkdirs()

        lifecycleScope.launch(Dispatchers.IO) {
            var successCount = 0
            var failCount = 0
            var skipCount = 0

            try {
                withContext(Dispatchers.Main) { transferProgress = "正在获取云端账号列表..." }

                val snapshot = suspendCancellableCoroutine<com.google.firebase.firestore.QuerySnapshot> { cont ->
                    db.collection("rooms").document(roomCode)
                        .collection("accounts")
                        .whereEqualTo("gameId", gameId)
                        .get()
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { e -> cont.cancel(e) }
                }

                val cloudAccounts = snapshot.toObjects(AccountModel::class.java)
                R2Logger.log("Restore", "云端共有 ${cloudAccounts.size} 个账号")

                if (cloudAccounts.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        transferProgress = ""
                        onComplete(0, 0, 0)
                    }
                    return@launch
                }

                for ((index, account) in cloudAccounts.withIndex()) {
                    try {
                        withContext(Dispatchers.Main) {
                            onProgress(index + 1, cloudAccounts.size, account.getName())
                        }

                        val localFile = File(root, account.toFullName())
                        if (localFile.exists()) {
                            skipCount++
                            successCount++
                            continue
                        }

                        val downloaded = downloadFileFromStorage(roomCode, gameId, account.getName(), localFile)
                        if (downloaded) successCount++ else failCount++
                    } catch (e: Exception) {
                        R2Logger.error("Restore", "处理账号失败: ${account.getName()}", e)
                        failCount++
                    }
                }

                withContext(Dispatchers.Main) {
                    transferProgress = ""
                    lastOperationLog = "从云端恢复完成:\n成功: $successCount (跳过 $skipCount)\n失败: $failCount"
                    onComplete(successCount, failCount, skipCount)
                }
            } catch (e: Exception) {
                R2Logger.error("Restore", "从云端恢复失败", e)
                withContext(Dispatchers.Main) {
                    transferProgress = ""
                    lastOperationLog = "从云端恢复失败: ${e.message}"
                    onComplete(0, 0, 0)
                }
            }
        }
    }

    // ==================== 云端同步 ====================

    fun listenToCloud(roomCode: String, gameId: String, onCloudData: (List<AccountModel>) -> Unit) {
        if (roomCode.isEmpty()) return
        cloudListener?.remove()

        cloudListener = db.collection("rooms").document(roomCode)
            .collection("accounts")
            .whereEqualTo("gameId", gameId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) { Log.e("Firebase", "监听账号失败", e); return@addSnapshotListener }
                val cloudAccounts = snapshot?.toObjects(AccountModel::class.java) ?: emptyList()
                cloudState = cloudAccounts.associateBy { it.getName() }
                onCloudData(cloudAccounts)
            }
    }

    fun listenToDevices(roomCode: String, gameId: String) {
        if (roomCode.isEmpty()) return
        devicesListener?.remove()

        devicesListener = db.collection("rooms").document(roomCode)
            .collection("devices")
            .addSnapshotListener { snapshot, e ->
                if (e != null) { Log.e("Firebase", "监听设备失败", e); return@addSnapshotListener }
                val devices = snapshot?.toObjects(DeviceSelection::class.java) ?: emptyList()
                allDeviceSelections = devices.filter { it.gameId == gameId }
            }
    }

    fun updateMySelection(roomCode: String, gameId: String, accountName: String) {
        if (roomCode.isEmpty()) return
        val data = DeviceSelection(
            deviceId = myDeviceId,
            deviceName = myDeviceName,
            selectedAccount = accountName,
            gameId = gameId,
            selectedTime = System.currentTimeMillis()
        )
        db.collection("rooms").document(roomCode)
            .collection("devices").document(myDeviceId)
            .set(data)
            .addOnSuccessListener { Log.d("Firebase", "本机选中已上传: $accountName") }
            .addOnFailureListener { e -> Log.e("Firebase", "本机选中上传失败", e) }
    }

    override fun onDestroy() {
        super.onDestroy()
        cloudListener?.remove()
        devicesListener?.remove()
    }
}

// ==================== Compose UI ====================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileManagerScreen() {
    val context = LocalContext.current as MainActivity
    val prefs = context.getSharedPreferences("MS_PREFS", Context.MODE_PRIVATE)
    var isTW by remember { mutableStateOf(prefs.getBoolean("SAVED_IS_TW", true)) }
    var isDeleteLocked by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var showR2LogDialog by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showDevicesDialog by remember { mutableStateOf(false) }
    var showCleanOwnDialog by remember { mutableStateOf(false) }

    var showRoomDialog by remember { mutableStateOf(false) }
    var currentRoomCode by remember { mutableStateOf(prefs.getString("ROOM_CODE", "") ?: "") }
    var roomInput by remember { mutableStateOf(currentRoomCode) }

    var accountNameInput by remember { mutableStateOf("") }
    var renameInput by remember { mutableStateOf("") }
    var accountToRename by remember { mutableStateOf("") }
    var accountToDelete by remember { mutableStateOf("") }

    val backupList = remember { mutableStateListOf<String>() }
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    var lastActiveName by remember(isTW) { mutableStateOf(prefs.getString("LAST_${if(isTW) "TW" else "JP"}", "") ?: "") }

    fun refreshList() {
        val root = File(Constant.getBackupPath(isTW))
        if (!root.exists()) root.mkdirs()
        backupList.clear()
        val list = root.list()?.toMutableList() ?: mutableListOf()
        list.sortBy { it.lowercase() }
        backupList.addAll(list)
        lastActiveName = prefs.getString("LAST_${if(isTW) "TW" else "JP"}", "") ?: ""
    }

    LaunchedEffect(isTW, currentRoomCode) {
        prefs.edit().putBoolean("SAVED_IS_TW", isTW).apply()
        refreshList()

        if (currentRoomCode.isNotEmpty()) {
            val currentGameId = if (isTW) "monst_tw" else "monst_jp"
            context.listenToCloud(currentRoomCode, currentGameId) { cloudAccounts ->
                Log.d("Firebase", "云端数据更新: ${cloudAccounts.size} 个账号")
            }
            context.listenToDevices(currentRoomCode, currentGameId)
        }
    }

    LaunchedEffect(lastActiveName, isTW, currentRoomCode) {
        if (currentRoomCode.isNotEmpty() && lastActiveName.isNotEmpty()) {
            val currentGameId = if (isTW) "monst_tw" else "monst_jp"
            context.updateMySelection(currentRoomCode, currentGameId, lastActiveName)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isTW) "MS管理 (台)" else "MS管理 (日)") },
                actions = {
                    IconButton(onClick = { showR2LogDialog = true }) {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = "R2日志",
                            tint = if (R2Logger.logs.isNotEmpty()) Color(0xFF2196F3) else Color.Unspecified
                        )
                    }
                    IconButton(onClick = { showDevicesDialog = true }) {
                        BadgedBox(badge = {
                            if (context.allDeviceSelections.size > 1) {
                                Badge { Text("${context.allDeviceSelections.size}") }
                            }
                        }) { Icon(Icons.Default.Devices, "设备监控") }
                    }
                    IconButton(onClick = { roomInput = currentRoomCode; showRoomDialog = true }) {
                        Icon(Icons.Default.MeetingRoom, "房间号",
                            tint = if (currentRoomCode.isEmpty()) Color.Red else Color.Unspecified)
                    }
                    IconButton(onClick = { showLogDialog = true }) { Icon(Icons.Default.BugReport, null) }
                    IconButton(onClick = { isTW = !isTW }) { Icon(Icons.Default.SyncAlt, null) }
                    IconButton(onClick = { isDeleteLocked = !isDeleteLocked }) { Icon(if (isDeleteLocked) Icons.Default.Lock else Icons.Default.LockOpen, null) }
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, null) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("全部上传到云端（含文件）") },
                            leadingIcon = { Icon(Icons.Default.CloudUpload, null) },
                            onClick = {
                                showMenu = false
                                AlertDialog.Builder(context)
                                    .setTitle("批量上传")
                                    .setMessage("将上传 ${backupList.size} 个账号的完整文件到 R2。\n\n⚠️ 每个文件约 28MB，请确保网络稳定。")
                                    .setPositiveButton("开始") { _, _ ->
                                        context.uploadAllToCloud(isTW) { success, fail ->
                                            AlertDialog.Builder(context)
                                                .setTitle("批量上传完成")
                                                .setMessage("成功: $success 个\n失败: $fail 个\n\n详情请点顶部 ☁️ 图标查看日志。")
                                                .setPositiveButton("确定", null)
                                                .show()
                                        }
                                    }
                                    .setNegativeButton("取消", null)
                                    .show()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("从云端恢复所有（新设备）") },
                            leadingIcon = { Icon(Icons.Default.CloudDownload, null) },
                            onClick = {
                                showMenu = false
                                AlertDialog.Builder(context)
                                    .setTitle("从云端恢复")
                                    .setMessage("将从 R2 下载所有账号文件到本地。\n\n⚠️ 每个文件约 28MB，请确保 WIFI 环境。\n\n已存在的账号会自动跳过。")
                                    .setPositiveButton("开始") { _, _ ->
                                        context.restoreAllFromCloud(isTW,
                                            onProgress = { current, total, name ->
                                                context.transferProgress = "正在下载 $current/$total: $name"
                                            },
                                            onComplete = { success, fail, skip ->
                                                AlertDialog.Builder(context)
                                                    .setTitle("从云端恢复完成")
                                                    .setMessage("成功: $success 个（其中跳过 $skip 个）\n失败: $fail 个")
                                                    .setPositiveButton("确定") { _, _ -> refreshList() }
                                                    .show()
                                            }
                                        )
                                    }
                                    .setNegativeButton("取消", null)
                                    .show()
                            }
                        )
                        Divider()
                        DropdownMenuItem(
                            text = { Text("清理本機記錄") },
                            leadingIcon = { Icon(Icons.Default.DeleteSweep, null) },
                            onClick = { showCleanOwnDialog = true; showMenu = false }
                        )
                        Divider()
                        DropdownMenuItem(
                            text = { Text("重置所有顏色") },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            onClick = { showResetConfirm = true; showMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("關於本程式") },
                            leadingIcon = { Icon(Icons.Default.Info, null) },
                            onClick = { showAboutDialog = true; showMenu = false }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (lastActiveName.isNotEmpty()) {
                    SmallFloatingActionButton(
                        onClick = { if (!isDeleteLocked) {
                            val f = backupList.find { AccountModel.fromFullName(it).getName() == lastActiveName }
                            f?.let { context.saveMergedAccount(null, it, isTW) { refreshList() } }
                        } else { Toast.makeText(context, "請先解鎖", Toast.LENGTH_SHORT).show() } },
                        containerColor = if (isDeleteLocked) Color.LightGray else MaterialTheme.colorScheme.secondaryContainer
                    ) { Icon(Icons.Default.Refresh, null, tint = if(isDeleteLocked) Color.Gray else Color.Unspecified) }
                }
                Spacer(Modifier.height(8.dp))
                FloatingActionButton(onClick = { accountNameInput = ""; showAddDialog = true }) { Icon(Icons.Default.Add, null) }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (context.transferProgress.isNotEmpty()) {
                Surface(color = Color(0xFFFFF3E0), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(context.transferProgress, fontSize = 13.sp, color = Color(0xFFE65100))
                    }
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(backupList, key = { it }) { fullName ->
                    val localModel = AccountModel.fromFullName(fullName)
                    val cloudModel = context.cloudState[localModel.getName()]
                    val model = if (cloudModel != null && cloudModel.getLastUsedTime() > localModel.getLastUsedTime()) cloudModel else localModel

                    val isLast = model.getName() == lastActiveName
                    val otherDevicesUsingThis = context.allDeviceSelections.filter {
                        it.deviceId != context.myDeviceId && it.selectedAccount == model.getName()
                    }
                    val existsInCloud = cloudModel != null
                    val isUsed = model.isUsedToday()

                    val cardColor = when {
                        isLast -> Color(0xFF2196F3)
                        isUsed -> Color(0xFFFFEE58)
                        else -> Color(0xFFA5D6A7)
                    }

                    val dismissState = rememberSwipeToDismissBoxState(confirmValueChange = {
                        if (it == SwipeToDismissBoxValue.EndToStart) {
                            if (isDeleteLocked) { Toast.makeText(context, "鎖定中", Toast.LENGTH_SHORT).show(); false }
                            else { accountToDelete = fullName; showDeleteConfirm = true; false }
                        } else false
                    })

                    SwipeToDismissBox(state = dismissState, enableDismissFromStartToEnd = false, backgroundContent = {
                        Box(Modifier.fillMaxSize().padding(8.dp).background(
                            if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) Color.Red else Color.Transparent,
                            MaterialTheme.shapes.medium), contentAlignment = Alignment.CenterEnd) {
                            Icon(Icons.Default.Delete, null, tint = Color.White, modifier = Modifier.padding(end = 16.dp))
                        }
                    }) {
                        Card(colors = CardDefaults.cardColors(containerColor = cardColor),
                            modifier = Modifier.fillMaxWidth().padding(8.dp).combinedClickable(
                                onClick = {
                                    lastActiveName = model.getName()
                                    val localKey = "LAST_${if(isTW) "TW" else "JP"}"
                                    prefs.edit()
                                        .putString(localKey, model.getName())
                                        .putLong("${localKey}_TIME", System.currentTimeMillis())
                                        .apply()
                                    context.switchMergedAccount(fullName, isTW) { success -> if (success) refreshList() }
                                },
                                onLongClick = { accountToRename = fullName; renameInput = model.getName(); showRenameDialog = true }
                            )) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (isLast) Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.White, modifier = Modifier.padding(end = 8.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(model.getName(), fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                        color = if (isLast) Color.White else Color.Black)
                                    Text("次數: ${model.getFrequency()} | ${sdf.format(Date(model.getLastUsedTime()))}",
                                        fontSize = 12.sp, color = if (isLast) Color.LightGray else Color.DarkGray)

                                    if (otherDevicesUsingThis.isNotEmpty()) {
                                        val names = otherDevicesUsingThis.joinToString(", ") { it.deviceName }
                                        Text("📱 $names", fontSize = 11.sp,
                                            color = if (isLast) Color.White else Color(0xFFE65100),
                                            fontWeight = FontWeight.Bold)
                                    }
                                    if (existsInCloud) {
                                        Text("☁️ 云端已有", fontSize = 10.sp,
                                            color = if (isLast) Color.White else Color(0xFF1976D2))
                                    }
                                }

                                if (otherDevicesUsingThis.isNotEmpty()) {
                                    Box(modifier = Modifier.size(10.dp).background(Color(0xFFFF9800), shape = MaterialTheme.shapes.small))
                                    Spacer(Modifier.width(8.dp))
                                }

                                IconButton(onClick = {
                                    if (currentRoomCode.isEmpty()) {
                                        Toast.makeText(context, "请先设置房间号", Toast.LENGTH_SHORT).show()
                                        showRoomDialog = true
                                    } else {
                                        val localFile = File(Constant.getBackupPath(isTW), fullName)
                                        if (!localFile.exists()) {
                                            Toast.makeText(context, "本地文件不存在", Toast.LENGTH_SHORT).show()
                                            return@IconButton
                                        }
                                        context.transferProgress = "正在上传 ${model.getName()}..."
                                        context.uploadFullAccountToCloud(currentRoomCode, model, if (isTW) "monst_tw" else "monst_jp", localFile) { success ->
                                            context.transferProgress = ""
                                            Toast.makeText(context, if (success) "已上传到云端" else "上传失败，查看☁️日志", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }) {
                                    Icon(
                                        imageVector = if (existsInCloud) Icons.Default.CloudDone else Icons.Default.CloudUpload,
                                        contentDescription = "云同步",
                                        tint = if (existsInCloud) Color(0xFF4CAF50) else if (isLast) Color.White else Color.Gray
                                    )
                                }

                                if (isLast) Icon(Icons.Default.PlayArrow, null, tint = Color.White)
                                else if (isUsed) Icon(Icons.Default.Done, null, tint = Color(0xFFF57F17))
                            }
                        }
                    }
                }
            }
        }

        // --- 清理本機記錄弹窗 ---
        if (showCleanOwnDialog) {
            AlertDialog(
                onDismissRequest = { showCleanOwnDialog = false },
                title = { Text("清理本機記錄") },
                text = {
                    Column {
                        Text("选择要清理的内容：", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))

                        Button(
                            onClick = {
                                context.clearMyDeviceFromCloud { success ->
                                    if (success) {
                                        Toast.makeText(context, "✅ 已从云端移除本机记录", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "❌ 移除失败", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                showCleanOwnDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                        ) {
                            Icon(Icons.Default.CloudOff, null)
                            Spacer(Modifier.width(8.dp))
                            Text("從雲端移除本機（保留雲端檔案）")
                        }

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = {
                                AlertDialog.Builder(context)
                                    .setTitle("確認清理本地")
                                    .setMessage("將刪除本機 msaccount2/tw 和 jp 下的所有 .bin 檔案。\n\n⚠️ 此操作不可恢復！雲端檔案不受影響。")
                                    .setPositiveButton("確定刪除") { _, _ ->
                                        context.clearLocalBackups { count ->
                                            Toast.makeText(context, "✅ 已刪除 $count 個本地檔案", Toast.LENGTH_LONG).show()
                                            refreshList()
                                        }
                                        showCleanOwnDialog = false
                                    }
                                    .setNegativeButton("取消", null)
                                    .show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                        ) {
                            Icon(Icons.Default.DeleteSweep, null)
                            Spacer(Modifier.width(8.dp))
                            Text("清理本地備份（雲端不受影響）")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showCleanOwnDialog = false }) { Text("關閉") }
                }
            )
        }

        // --- R2 日志弹窗 ---
        if (showR2LogDialog) {
            AlertDialog(
                onDismissRequest = { showR2LogDialog = false },
                title = { Text("R2 上传日志 (共 ${R2Logger.logs.size} 条)") },
                text = {
                    if (R2Logger.logs.isEmpty()) {
                        Text("暂无日志。\n\n请先尝试上传一次文件，再回来看。", color = Color.Gray)
                    } else {
                        Box(modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                            Text(
                                text = R2Logger.logs.joinToString("\n\n"),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color.Black
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showR2LogDialog = false }) { Text("关闭") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { R2Logger.clear(); showR2LogDialog = false }) { Text("清空") }
                        TextButton(onClick = {
                            val path = R2Logger.saveToFile()
                            if (path != null) {
                                Toast.makeText(context, "✅ 日志已保存到:\n$path", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "❌ 保存失败，请检查存储权限", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("导出", color = Color(0xFF2196F3)) }
                    }
                }
            )
        }

        // --- 设备监控弹窗 ---
        if (showDevicesDialog) {
            AlertDialog(
                onDismissRequest = { showDevicesDialog = false },
                title = { Text("在线设备监控") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        if (context.allDeviceSelections.isEmpty()) {
                            Text("目前没有其他设备在线。", color = Color.Gray)
                        } else {
                            context.allDeviceSelections.forEach { device ->
                                val isMe = device.deviceId == context.myDeviceId
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.PhoneAndroid, null,
                                        tint = if (isMe) Color(0xFF2196F3) else Color(0xFF4CAF50))
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text("${device.deviceName}${if (isMe) " (本机)" else ""}",
                                            fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text("正在使用: ${device.selectedAccount}", fontSize = 12.sp, color = Color.Gray)
                                        Text(sdf.format(Date(device.selectedTime)), fontSize = 10.sp, color = Color.LightGray)
                                    }
                                }
                                Divider()
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showDevicesDialog = false }) { Text("关闭") } }
            )
        }

        // --- 房间号弹窗 ---
        if (showRoomDialog) {
            AlertDialog(
                onDismissRequest = { showRoomDialog = false },
                title = { Text("设置同步房间号") },
                text = {
                    Column {
                        Text("3台设备输入相同的房间号即可同步数据。", fontSize = 12.sp, color = Color.Gray)
                        Spacer(Modifier.height(8.dp))
                        TextField(value = roomInput, onValueChange = { roomInput = it }, singleLine = true, placeholder = { Text("例如 888888") })
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        currentRoomCode = roomInput.trim()
                        prefs.edit().putString("ROOM_CODE", currentRoomCode).apply()
                        showRoomDialog = false
                    }) { Text("保存并连接") }
                },
                dismissButton = { TextButton(onClick = { showRoomDialog = false }) { Text("取消") } }
            )
        }

        // --- 其他弹窗 ---
        if (showRenameDialog) {
            AlertDialog(onDismissRequest = { showRenameDialog = false }, title = { Text("重命名") },
                text = { TextField(value = renameInput, onValueChange = { renameInput = it.replace(Constant.SEPARATOR, "") }, singleLine = true) },
                confirmButton = { Button(onClick = {
                    if (renameInput.trim().isNotEmpty() && !backupList.any { AccountModel.fromFullName(it).getName().equals(renameInput.trim(), true) }) {
                        context.renameAccount(accountToRename, renameInput.trim(), isTW) { refreshList(); showRenameDialog = false }
                    } else { Toast.makeText(context, "名稱重複或無效", Toast.LENGTH_SHORT).show() }
                }) { Text("確定") } })
        }

        if (showAboutDialog) {
            AlertDialog(onDismissRequest = { showAboutDialog = false }, title = { Text("關於") },
                text = { Text("Google AI Design with KK\n\n支援 Android 8.0+\n合併封裝：data10 + data13\n雲端存儲：Cloudflare R2") },
                confirmButton = { TextButton(onClick = { showAboutDialog = false }) { Text("OK") } })
        }

        if (showAddDialog) {
            AlertDialog(onDismissRequest = { showAddDialog = false }, title = { Text("新增") },
                text = { TextField(value = accountNameInput, onValueChange = { accountNameInput = it.replace(Constant.SEPARATOR, "") }, singleLine = true) },
                confirmButton = { Button(onClick = {
                    if (accountNameInput.trim().isNotEmpty() && !backupList.any { AccountModel.fromFullName(it).getName().equals(accountNameInput.trim(), true) }) {
                        context.saveMergedAccount(accountNameInput.trim(), null, isTW) { refreshList(); showAddDialog = false }
                    } else { Toast.makeText(context, "重複", Toast.LENGTH_SHORT).show() }
                }) { Text("確認") } })
        }

        if (showDeleteConfirm) {
            AlertDialog(onDismissRequest = { showDeleteConfirm = false }, title = { Text("刪除") }, text = { Text("確定刪除？") },
                confirmButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                    onClick = { context.deleteAccount(accountToDelete, isTW) { refreshList(); showDeleteConfirm = false } }) { Text("刪除", color = Color.White) } },
                dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } })
        }

        if (showResetConfirm) {
            AlertDialog(
                onDismissRequest = { showResetConfirm = false },
                title = { Text("重置顏色") },
                text = { Text("確定要將所有帳號的「今日已用」狀態重置嗎？\n(所有卡片將變回綠色)") },
                confirmButton = { Button(onClick = { context.resetAllColors(isTW) { refreshList() }; showResetConfirm = false }) { Text("確定") } },
                dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("取消") } }
            )
        }

        if (showLogDialog) {
            AlertDialog(
                onDismissRequest = { showLogDialog = false },
                title = { Text("最近一次执行日志") },
                text = {
                    Box(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                        Text(text = context.lastOperationLog)
                    }
                },
                confirmButton = { TextButton(onClick = { showLogDialog = false }) { Text("关闭") } },
                dismissButton = { TextButton(onClick = { context.lastOperationLog = "暂无日志"; showLogDialog = false }) { Text("清空") } }
            )
        }
    }
}