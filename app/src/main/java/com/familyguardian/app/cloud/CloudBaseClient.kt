package com.familyguardian.app.cloud

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * CloudBase HTTP API 客户端（子女端）
 * v0.4.0: 新增 registerUser() 自动注册
 */
object CloudBaseClient {
    
    private const val TAG = "CloudBaseClient"
    private const val ENV_ID = "diedaobao-cdn-d4g496tvv296f0ac2"
    private const val BASE_URL = "https://$ENV_ID-1409685971.ap-shanghai.app.tcloudbase.com"
    
    private const val PREFS_NAME = "cloudbase_prefs"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_ELDER_ID = "elder_id"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_USER_PHONE = "user_phone"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    
    private lateinit var prefs: SharedPreferences
    private var userId: String? = null
    private var elderId: String? = null
    
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        userId = prefs.getString(KEY_USER_ID, null)
        elderId = prefs.getString(KEY_ELDER_ID, null)
        Log.i(TAG, "CloudBase initialized: userId=$userId, elderId=$elderId")
    }
    
    /**
     * 检查是否已注册
     */
    fun isRegistered(): Boolean = userId != null
    
    /**
     * 检查是否已绑定老人
     */
    fun hasBoundElder(): Boolean = elderId != null
    
    /**
     * 获取绑定的老人ID
     */
    fun getElderId(): String? = elderId
    
    /**
     * 获取用户名
     */
    fun getUserName(): String = prefs.getString(KEY_USER_NAME, "") ?: ""
    
    /**
     * v0.4.0: 自动注册（子女端）
     * 使用 deviceId 自动注册，无需用户输入
     */
    suspend fun autoRegister(context: Context): Boolean {
        // 已注册则跳过
        if (userId != null) return true
        
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: return false
        
        return withContext(Dispatchers.IO) {
            try {
                val body = JsonObject().apply {
                    addProperty("deviceId", deviceId)
                    addProperty("name", "家属")
                    addProperty("phone", "")
                    addProperty("role", "guardian")
                }
                
                val request = Request.Builder()
                    .url("$BASE_URL/user-register")
                    .post(gson.toJson(body).toRequestBody(jsonMediaType))
                    .build()
                
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "autoRegister failed: ${response.code}")
                    return@withContext false
                }
                
                val responseBody = response.body?.string()
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                val newUserId = json.get("userId")?.asString
                
                if (newUserId != null) {
                    userId = newUserId
                    prefs.edit().putString(KEY_USER_ID, newUserId).apply()
                    Log.i(TAG, "Guardian registered: $newUserId")
                    true
                } else {
                    Log.e(TAG, "autoRegister: no userId in response")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "autoRegister error", e)
                false
            }
        }
    }
    
    /**
     * 绑定老人（输入绑定码）
     */
    suspend fun bindElder(bindCode: String): BindResult {
        return withContext(Dispatchers.IO) {
            try {
                // 确保已注册
                if (userId == null) {
                    return@withContext BindResult(false, "请先完成注册")
                }
                
                val url = "$BASE_URL/bind-family"
                val body = JsonObject().apply {
                    addProperty("bindCode", bindCode)
                    addProperty("guardianId", userId)
                }
                
                val request = Request.Builder()
                    .url(url)
                    .post(gson.toJson(body).toRequestBody(jsonMediaType))
                    .build()
                
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()
                Log.i(TAG, "bindElder response: $responseBody")
                
                if (!response.isSuccessful) {
                    return@withContext BindResult(false, "网络错误(${response.code})")
                }
                
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                val code = json.get("code")?.asInt ?: 0
                
                if (code == 200) {
                    val newElderId = json.get("elderId")?.asString
                    if (newElderId != null) {
                        elderId = newElderId
                        prefs.edit().putString(KEY_ELDER_ID, newElderId).apply()
                        Log.i(TAG, "Elder bound: $newElderId")
                        BindResult(true, "绑定成功")
                    } else {
                        // 可能是 Already bound 的情况
                        val msg = json.get("message")?.asString ?: "绑定成功"
                        BindResult(true, msg)
                    }
                } else {
                    val msg = json.get("message")?.asString ?: "绑定失败"
                    BindResult(false, msg)
                }
            } catch (e: Exception) {
                Log.e(TAG, "bindElder error", e)
                BindResult(false, "网络异常：${e.message}")
            }
        }
    }
    
    /**
     * 解绑老人
     */
    fun unbindElder() {
        elderId = null
        prefs.edit().remove(KEY_ELDER_ID).apply()
    }
    
    /**
     * 获取老人状态
     */
    suspend fun getElderStatus(): ElderStatus? {
        return withContext(Dispatchers.IO) {
            try {
                val eid = elderId ?: return@withContext null
                val url = "$BASE_URL/get-status?elderId=$eid"
                
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()
                
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "getElderStatus failed: ${response.code}")
                    return@withContext null
                }
                
                val responseBody = response.body?.string()
                gson.fromJson(responseBody, ElderStatus::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "getElderStatus error", e)
                null
            }
        }
    }
    
    /**
     * 获取跌倒历史
     */
    suspend fun getFallHistory(limit: Int = 20): List<FallEvent> {
        return withContext(Dispatchers.IO) {
            try {
                val eid = elderId ?: return@withContext emptyList()
                val url = "$BASE_URL/fall-history?elderId=$eid&limit=$limit"
                
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()
                
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "getFallHistory failed: ${response.code}")
                    return@withContext emptyList()
                }
                
                val responseBody = response.body?.string()
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                val events = json.getAsJsonArray("events")
                events.map { gson.fromJson(it, FallEvent::class.java) }
            } catch (e: Exception) {
                Log.e(TAG, "getFallHistory error", e)
                emptyList()
            }
        }
    }
    
    // 数据类
    data class BindResult(val success: Boolean, val message: String)
    
    data class ElderStatus(
        val elderId: String,
        val name: String,
        val lastLocation: Location?,
        val lastUpdate: Long,
        val status: String
    )
    
    data class Location(
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long
    )
    
    data class FallEvent(
        val eventId: String,
        val timestamp: Long,
        val latitude: Double?,
        val longitude: Double?,
        val impactG: Double,
        val mlScore: Double
    )
}
