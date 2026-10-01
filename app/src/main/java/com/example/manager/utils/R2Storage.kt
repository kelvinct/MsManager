package com.example.manager.utils

import android.content.Context
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.DeleteObjectRequest
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awssigning.AwsSigningAttributes // 💡 引入簽章屬性
import aws.smithy.kotlin.runtime.auth.awssigning.HashSpecification   // 💡 引入雜湊規格
import aws.smithy.kotlin.runtime.client.ProtocolRequestInterceptorContext
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.fromFile
import aws.smithy.kotlin.runtime.content.writeToFile
import aws.smithy.kotlin.runtime.http.interceptors.HttpInterceptor // 💡 引入攔截器
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import aws.smithy.kotlin.runtime.net.url.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 💡 自訂攔截器：停用 AWS Chunked 簽章以相容 Cloudflare R2
 */
class DisableChunkedSigning : HttpInterceptor {
    override suspend fun modifyBeforeSigning(
        context: ProtocolRequestInterceptorContext<Any, HttpRequest>
    ): HttpRequest {
        // 強制將 Payload 簽章雜湊規格改為 UnsignedPayload
        context.executionContext[AwsSigningAttributes.HashSpecification] = HashSpecification.UnsignedPayload

        // 💡 必須呼叫父類別方法將上下文與請求安全回傳，否則會發生型別或欄位不匹配的編譯錯誤
        return super.modifyBeforeSigning(context)
    }
}
object R2Storage {
    private const val TAG = "R2Storage"

    private const val ACCESS_KEY = "ca7b97bc63b26eee4abe8386d66ec4b3"
    private const val SECRET_KEY = "b3e8631f37f7cef469f96f603423db1fe360ea5250d97b2c29f7eeacb54eebd9"
    private const val ENDPOINT = "https://c65a9c181d5932485a2768e19cd0aeb7.r2.cloudflarestorage.com"
    private const val BUCKET = "ms-manager-backup"

    private var s3Client: S3Client? = null

    fun init(context: Context) {
        R2Logger.log(TAG, "初始化 R2Storage (Kotlin SDK)")

        try {
            s3Client = S3Client {
                region = "auto"
                endpointUrl = Url.parse(ENDPOINT)
                credentialsProvider = StaticCredentialsProvider(
                    Credentials(
                        accessKeyId = ACCESS_KEY,
                        secretAccessKey = SECRET_KEY
                    )
                )
                // 💡 在這裡將攔截器注入進去
                interceptors += DisableChunkedSigning()
            }
            R2Logger.log(TAG, "✅ 初始化完成")
        } catch (e: Exception) {
            R2Logger.error(TAG, "初始化失败", e)
        }
    }

    /**
     * 上傳檔案（優化版：支援大檔案，串流傳輸，相容 R2）
     */
    suspend fun uploadFile(localFile: File, key: String): Boolean {
        val client = s3Client ?: run {
            R2Logger.error(TAG, "S3Client 未初始化")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                if (!localFile.exists()) {
                    R2Logger.error(TAG, "本地文件不存在: ${localFile.absolutePath}")
                    return@withContext false
                }

                R2Logger.log(TAG, "开始上传: $key (${localFile.length() / 1024} KB)")

                val request = PutObjectRequest {
                    bucket = BUCKET
                    this.key = key
                    // 配合攔截器後，這裡可以安全使用 fromFile 串流，不再引發 501 錯誤
                    body = ByteStream.fromFile(localFile)
                }

                client.putObject(request)
                R2Logger.log(TAG, "✅ 上传成功: $key")
                true
            } catch (e: Exception) {
                R2Logger.error(TAG, "❌ 上传异常: $key", e)
                false
            }
        }
    }

    /**
     * 下載檔案（優化版：直接串流寫入硬碟，防止 OOM）
     */
    suspend fun downloadFile(key: String, targetFile: File): Boolean {
        val client = s3Client ?: run {
            R2Logger.error(TAG, "S3Client 未初始化")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                R2Logger.log(TAG, "开始下载: $key")
                targetFile.parentFile?.mkdirs()

                val request = GetObjectRequest {
                    bucket = BUCKET
                    this.key = key
                }

                client.getObject(request) { response ->
                    val body = response.body
                    if (body != null) {
                        body.writeToFile(targetFile)
                    } else {
                        throw IllegalStateException("Response body is null")
                    }
                }

                R2Logger.log(TAG, "✅ 下载成功: $key (${targetFile.length() / 1024} KB)")
                true
            } catch (e: Exception) {
                R2Logger.error(TAG, "❌ 下载异常: $key", e)
                if (targetFile.exists()) targetFile.delete()
                false
            }
        }
    }

    /**
     * 刪除檔案
     */
    suspend fun deleteFile(key: String): Boolean {
        val client = s3Client ?: run {
            R2Logger.error(TAG, "S3Client 未初始化")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val request = DeleteObjectRequest {
                    bucket = BUCKET
                    this.key = key
                }
                client.deleteObject(request)
                R2Logger.log(TAG, "删除成功: $key")
                true
            } catch (e: Exception) {
                R2Logger.error(TAG, "删除失败: $key", e)
                false
            }
        }
    }
}
