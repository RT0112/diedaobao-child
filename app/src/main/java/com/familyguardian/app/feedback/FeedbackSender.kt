package com.familyguardian.app.feedback

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 意见反馈提交器
 * 支持反馈类型 + 联系方式 + 内容提交到服务器
 */
object FeedbackSender {
    private const val TAG = "FeedbackSender"

    // 使用与 CloudBaseClient 相同的公网地址
    private const val BASE_URL = "https://clerk-anything-adopt-lately.trycloudflare.com"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * 提交反馈
     */
    suspend fun submit(
        context: Context,
        feedbackType: String,
        contact: String,
        content: String,
        deviceModel: String,
        androidVersion: String,
        appVersion: String
    ): Result<String> {
        val payload = JSONObject().apply {
            put("type", feedbackType)
            put("contact", contact)
            put("content", content)
            put("device_model", deviceModel)
            put("android_version", androidVersion)
            put("app_version", appVersion)
            put("platform", "child") // 标识是子女端
        }
        return postToServer(payload)
    }

    /**
     * 发送POST请求到服务器
     */
    private suspend fun postToServer(payload: JSONObject): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val body = payload.toString().toRequestBody(JSON)
            val request = Request.Builder()
                .url("$BASE_URL/feedback")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful) {
                Log.d(TAG, "✅ 反馈提交成功: $responseBody")
                Result.success(responseBody ?: "提交成功")
            } else {
                val error = "服务器返回 ${response.code}: $responseBody"
                Log.e(TAG, "❌ $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 提交失败: ${e.message}")
            Result.failure(e)
        }
    }
}
