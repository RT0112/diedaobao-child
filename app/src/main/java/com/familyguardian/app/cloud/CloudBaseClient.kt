package com.familyguardian.app.cloud

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * CloudBase HTTP API 客户端（子女端）
 * v0.5.0: 新增电子围栏API
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
    private const val KEY_ELDER_NAME = "elder_name"
    private const val KEY_ELDER_PHONE = "elder_phone"
    private const val KEY_GEOFENCES = "geofences_cache"
    
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
    
    fun isRegistered(): Boolean = userId != null
    fun hasBoundElder(): Boolean = elderId != null
    fun getElderId(): String? = elderId
    fun getUserName(): String = prefs.getString(KEY_USER_NAME, "") ?: ""
    fun getElderName(): String = prefs.getString(KEY_ELDER_NAME, "老人") ?: "老人"
    fun getElderPhone(): String = prefs.getString(KEY_ELDER_PHONE, "") ?: ""
    
    fun saveElderInfo(name: String?, phone: String?) {
        prefs.edit().apply {
            if (name != null) putString(KEY_ELDER_NAME, name)
            if (phone != null) putString(KEY_ELDER_PHONE, phone)
            apply()
        }
    }
    
    // ========== 自动注册 ==========
    
    suspend fun autoRegister(context: Context): Boolean {
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
    
    // ========== 绑定 ==========
    
    suspend fun bindElder(bindCode: String): BindResult {
        return withContext(Dispatchers.IO) {
            try {
                if (userId == null) return@withContext BindResult(false, "请先完成注册")
                
                val body = JsonObject().apply {
                    addProperty("bindCode", bindCode)
                    addProperty("guardianId", userId)
                }
                
                val request = Request.Builder()
                    .url("$BASE_URL/bind-family")
                    .post(gson.toJson(body).toRequestBody(jsonMediaType))
                    .build()
                
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()
                Log.i(TAG, "bindElder response: $responseBody")
                
                if (!response.isSuccessful) return@withContext BindResult(false, "网络错误(${response.code})")
                
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
    
    fun unbindElder() {
        elderId = null
        prefs.edit().remove(KEY_ELDER_ID).apply()
    }
    
    // ========== 按需位置拉取 ==========
    
    /**
     * 请求老人实时位置
     * 调用云函数在老人用户文档上设置 pull flag
     * 老人端每10秒轮询 poll_pull，发现请求后立即上传最新GPS
     */
    suspend fun requestElderLocation(): Long? {
        return withContext(Dispatchers.IO) {
            try {
                val eid = elderId ?: return@withContext null
                val body = JsonObject().apply {
                    addProperty("elderId", eid)
                }
                
                val request = Request.Builder()
                    .url("$BASE_URL/request-elder-location")
                    .post(gson.toJson(body).toRequestBody(jsonMediaType))
                    .build()
                
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "requestElderLocation failed: ${response.code}")
                    return@withContext null
                }
                
                val responseBody = response.body?.string()
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                val success = json.get("success")?.asBoolean ?: false
                if (!success) {
                    Log.w(TAG, "requestElderLocation: ${json.get("message")?.asString}")
                    return@withContext null
                }
                
                json.get("requestTime")?.asLong
            } catch (e: Exception) {
                Log.e(TAG, "requestElderLocation error", e)
                null
            }
        }
    }
    
    // ========== 老人状态 ==========
    
    suspend fun getElderStatus(): ElderStatus? {
        return withContext(Dispatchers.IO) {
            try {
                val eid = elderId ?: return@withContext null
                val url = "$BASE_URL/get-status?elderId=$eid"
                
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null
                
                val responseBody = response.body?.string()
                Log.i(TAG, "getElderStatus response: $responseBody")
                
                // 先检查code字段
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                val code = json.get("code")?.asInt ?: 0
                if (code != 200) {
                    Log.w(TAG, "getElderStatus error code=$code: ${json.get("message")?.asString}")
                    return@withContext null
                }
                
                gson.fromJson(json, ElderStatus::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "getElderStatus error", e)
                null
            }
        }
    }
    
    // ========== 跌倒历史 ==========
    
    suspend fun getFallHistory(limit: Int = 20): List<FallEvent> {
        return withContext(Dispatchers.IO) {
            try {
                val eid = elderId ?: return@withContext emptyList()
                val url = "$BASE_URL/fall-history?elderId=$eid&limit=$limit"
                
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext emptyList()
                
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
    
    // ========== 电子围栏 ==========
    
    /**
     * 获取围栏列表
     * 先从缓存取，然后异步更新
     */
    suspend fun getGeofences(): List<GeofenceInfo> {
        return withContext(Dispatchers.IO) {
            try {
                val eid = elderId ?: return@withContext emptyList()
                val body = JsonObject().apply {
                    addProperty("action", "list")
                    addProperty("elderId", eid)
                    addProperty("creatorId", userId ?: "")
                }
                
                val request = Request.Builder()
                    .url("$BASE_URL/geofence")
                    .post(gson.toJson(body).toRequestBody(jsonMediaType))
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "getGeofences failed: ${response.code}")
                    return@withContext getCachedGeofences()
                }
                
                val responseBody = response.body?.string()
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                if (json.get("success")?.asBoolean != true) {
                    Log.e(TAG, "getGeofences server error: ${json.get("message")?.asString}")
                    return@withContext getCachedGeofences()
                }
                val fencesArray = json.getAsJsonArray("fences")
                
                val fences = fencesArray.mapNotNull { element ->
                    try {
                        val obj = element.asJsonObject
                        val id = obj.get("id")?.asString ?: return@mapNotNull null
                        val name = obj.get("name")?.asString ?: "未命名围栏"
                        val latitude = obj.get("latitude")?.asDouble ?: return@mapNotNull null
                        val longitude = obj.get("longitude")?.asDouble ?: return@mapNotNull null
                        val radius = obj.get("radius")?.asInt ?: 200
                        GeofenceInfo(
                            id = id,
                            name = name,
                            latitude = latitude,
                            longitude = longitude,
                            radius = radius,
                            isBreached = obj.get("isBreached")?.asBoolean ?: false,
                            createdAt = obj.get("createdAt")?.asLong ?: System.currentTimeMillis()
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse fence: ${e.message}")
                        null
                    }
                }
                
                cacheGeofences(fences)
                fences
            } catch (e: Exception) {
                Log.e(TAG, "getGeofences error", e)
                getCachedGeofences()
            }
        }
    }
    
    /**
     * 添加围栏
     */
    suspend fun addGeofence(name: String, latitude: Double, longitude: Double, radius: Int): String {
        return withContext(Dispatchers.IO) {
            try {
                val eid = elderId
                if (eid == null) {
                    Log.e(TAG, "addGeofence: elderId is null, not bound")
                    return@withContext "请先绑定老人设备"
                }
                
                val body = JsonObject().apply {
                    addProperty("action", "create")
                    addProperty("elderId", eid)
                    addProperty("creatorId", userId ?: "")
                    addProperty("name", name)
                    addProperty("latitude", latitude)
                    addProperty("longitude", longitude)
                    addProperty("radius", radius)
                }
                
                val request = Request.Builder()
                    .url("$BASE_URL/geofence")
                    .post(gson.toJson(body).toRequestBody(jsonMediaType))
                    .build()
                
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                Log.i(TAG, "addGeofence response: $responseBody")
                
                if (!response.isSuccessful) {
                    return@withContext "网络错误(${response.code})"
                }
                
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                val success = json.get("success")?.asBoolean ?: false
                if (!success) {
                    val msg = json.get("message")?.asString ?: "保存失败"
                    return@withContext msg
                }
                
                ""
            } catch (e: Exception) {
                Log.e(TAG, "addGeofence error", e)
                "网络异常：${e.message}"
            }
        }
    }
    
    /**
     * 更新围栏
     */
    suspend fun updateGeofence(fenceId: String, name: String, latitude: Double, longitude: Double, radius: Int): String {
        return withContext(Dispatchers.IO) {
            try {
                val body = JsonObject().apply {
                    addProperty("action", "update")
                    addProperty("fenceId", fenceId)
                    addProperty("creatorId", userId ?: "")
                    addProperty("name", name)
                    addProperty("latitude", latitude)
                    addProperty("longitude", longitude)
                    addProperty("radius", radius)
                }
                
                val request = Request.Builder()
                    .url("$BASE_URL/geofence")
                    .post(gson.toJson(body).toRequestBody(jsonMediaType))
                    .build()
                
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                Log.i(TAG, "updateGeofence response: $responseBody")
                
                if (!response.isSuccessful) {
                    return@withContext "网络错误(${response.code})"
                }
                
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                val success = json.get("success")?.asBoolean ?: false
                if (!success) {
                    val msg = json.get("message")?.asString ?: "更新失败"
                    return@withContext msg
                }
                
                ""
            } catch (e: Exception) {
                Log.e(TAG, "updateGeofence error", e)
                "网络异常：${e.message}"
            }
        }
    }
    
    /**
     * 删除围栏
     */
    suspend fun deleteGeofence(fenceId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val body = JsonObject().apply {
                    addProperty("action", "delete")
                    addProperty("fenceId", fenceId)
                }
                
                val request = Request.Builder()
                    .url("$BASE_URL/geofence")
                    .post(gson.toJson(body).toRequestBody(jsonMediaType))
                    .build()
                
                val response = client.newCall(request).execute()
                val success = response.isSuccessful
                Log.i(TAG, "deleteGeofence: $success")
                success
            } catch (e: Exception) {
                Log.e(TAG, "deleteGeofence error", e)
                false
            }
        }
    }
    
    private fun cacheGeofences(fences: List<GeofenceInfo>) {
        prefs.edit().putString(KEY_GEOFENCES, gson.toJson(fences)).apply()
    }
    
    private fun getCachedGeofences(): List<GeofenceInfo> {
        val json = prefs.getString(KEY_GEOFENCES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<GeofenceInfo>>() {}.type
            val fences = gson.fromJson<List<GeofenceInfo>>(json, type)
            // 过滤掉无效数据
            fences.filter { it.id.isNotBlank() && it.name.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "getCachedGeofences error, clearing cache", e)
            clearGeofenceCache()
            emptyList()
        }
    }
    
    fun clearGeofenceCache() {
        prefs.edit().remove(KEY_GEOFENCES).apply()
    }
    
    // ========== 数据类 ==========
    
    data class BindResult(val success: Boolean, val message: String)
    
    data class ElderStatus(
        val elderId: String,
        val name: String,
        val lastLocation: Location?,
        val lastUpdate: Long,
        val status: String,
        val pullLocationStatus: String? = null,  // "pending" | "done" | "idle"
        val pullRequestTime: Long? = null         // 上次拉取请求时间戳
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
    
    data class GeofenceInfo(
        val id: String,
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val radius: Int,
        val isBreached: Boolean = false,
        val createdAt: Long = System.currentTimeMillis()
    )
}
