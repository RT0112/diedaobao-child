package com.familyguardian.app.cloud

import com.familyguardian.app.util.AppLogger
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
    // K70本地服务器（局域网直连）
    // K70 WiFi IP: 192.168.4.19（同一局域网可直连）
    // 后续需配置内网穿透给外网用
    private const val BASE_URL = "https://scheduling-researchers-discuss-compatible.trycloudflare.com"
    
    private const val PREFS_NAME = "cloudbase"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_ELDER_ID = "elder_id"
    private const val KEY_FAMILY_ID = "family_id"
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
    fun getUserId(): String? = userId
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
        
        val rawDeviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: return false
        val deviceId = "family_$rawDeviceId"  // 区分同设备双App
        
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
                    AppLogger.e(TAG, "autoRegister failed: ${response.code}")
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
                    AppLogger.e(TAG, "autoRegister: no userId in response")
                    false
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "autoRegister error", e)
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
                        // 同步保存老人姓名
                        val name = json.get("elderName")?.asString
                        if (name != null) saveElderInfo(name, null)
                        Log.i(TAG, "Elder bound: $newElderId, name=$name")
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
                AppLogger.e(TAG, "bindElder error", e)
                BindResult(false, "网络异常：${e.message}")
            }
        }
    }
    
    fun unbindElder() {
        elderId = null
        prefs.edit().remove(KEY_ELDER_ID).apply()
    }

    /**
     * 强制重置注册状态，清除本地userId，下次启动会重新注册（带正确deviceId前缀）
     * 用途：解决SharedPreferences残留旧userId导致无法重新注册的问题
     */
    fun resetRegistration() {
        userId = null
        elderId = null
        prefs.edit()
            .remove(KEY_USER_ID)
            .remove(KEY_ELDER_ID)
            .remove(KEY_FAMILY_ID)
            .apply()
        Log.i(TAG, "Registration reset, will re-register on next launch")
    }

    /**
     * 从云端同步绑定关系，更新本地elderId
     * 解决：老人重新注册后userId变化，子女端本地elderId过期的问题
     */
    suspend fun syncBindingFromCloud(): Boolean {
        if (userId == null) return false

        return withContext(Dispatchers.IO) {
            try {
                val body = JsonObject().apply {
                    addProperty("action", "getBindings")
                    add("data", JsonObject().apply { addProperty("userId", userId); addProperty("role", "family") })
                }

                val request = Request.Builder()
                    .url("$BASE_URL/bind-family")
                    .post(gson.toJson(body).toRequestBody(jsonMediaType))
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "syncBinding: HTTP ${response.code}")
                    return@withContext false
                }

                val responseBody = response.body?.string()
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                val code = json.get("code")?.asInt ?: 0

                if (code == 200) {
                    val dataArray = json.getAsJsonArray("data")
                    if (dataArray != null && dataArray.size() > 0) {
                        // 遍历所有绑定记录，找到最新的且status=active的
                        var bestBinding: com.google.gson.JsonObject? = null
                        var bestTime = 0L
                        
                        for (i in 0 until dataArray.size()) {
                            val binding = dataArray[i].asJsonObject
                            val status = binding.get("status")?.asString ?: ""
                            if (status != "active") continue
                            
                            val createdAt = binding.get("createdAt")?.asString ?: ""
                            val time = try {
                                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                                    .parse(createdAt)?.time ?: 0L
                            } catch (e: Exception) { 0L }
                            
                            // 优先选择与本地elderId匹配的记录，否则选最新的
                            val bindingElderId = binding.get("elderId")?.asString
                            if (bindingElderId == elderId) {
                                bestBinding = binding
                                break  // 找到匹配的，直接用这个
                            }
                            if (time > bestTime) {
                                bestTime = time
                                bestBinding = binding
                            }
                        }
                        
                        val cloudElderId = bestBinding?.get("elderId")?.asString
                        // 修复：只在本地没有elderId时才从云端同步
                        // 避免云端旧绑定数据覆盖本地正确的elderId
                        if (cloudElderId != null && elderId == null) {
                            elderId = cloudElderId
                            prefs.edit().putString(KEY_ELDER_ID, cloudElderId).apply()
                            Log.i(TAG, "syncBinding: elderId set from cloud: $cloudElderId")
                        } else if (cloudElderId != null && elderId != null && cloudElderId != elderId) {
                            // 云端elderId与本地不一致，记录日志但不覆盖
                            Log.w(TAG, "syncBinding: cloud elderId($cloudElderId) != local($elderId), keeping local")
                        }
                        return@withContext true
                    }
                }
                false
            } catch (e: Exception) {
                Log.e(TAG, "syncBinding error: ${e.message}")
                false
            }
        }
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
                    AppLogger.e(TAG, "requestElderLocation failed: ${response.code}")
                    return@withContext null
                }
                
                val responseBody = response.body?.string()
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                val success = json.get("success")?.asBoolean ?: false
                if (!success) {
                    AppLogger.w(TAG, "requestElderLocation: ${json.get("message")?.asString}")
                    return@withContext null
                }
                
                json.get("requestTime")?.asLong
            } catch (e: Exception) {
                AppLogger.e(TAG, "requestElderLocation error", e)
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
                    AppLogger.w(TAG, "getElderStatus error code=$code: ${json.get("message")?.asString}")
                    return@withContext null
                }
                
                gson.fromJson(json, ElderStatus::class.java)
            } catch (e: Exception) {
                AppLogger.e(TAG, "getElderStatus error", e)
                null
            }
        }
    }
    
    // ========== 跌倒历史 ==========
    
    suspend fun getFallHistory(limit: Int = 20): List<FallEvent> {
        return withContext(Dispatchers.IO) {
            try {
                val eid = elderId ?: return@withContext emptyList()
                Log.i(TAG, "getFallHistory: elderId=$eid, limit=$limit")
                val url = "$BASE_URL/fall-history?elderId=$eid&limit=$limit"
                
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    AppLogger.e(TAG, "getFallHistory HTTP error: ${response.code}")
                    return@withContext emptyList()
                }
                
                val responseBody = response.body?.string()
                Log.i(TAG, "getFallHistory response: $responseBody")
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                
                // 检查云函数返回码
                val code = json.get("code")?.asInt ?: 0
                if (code != 200) {
                    AppLogger.e(TAG, "getFallHistory server error: code=$code, msg=${json.get("message")?.asString}")
                    return@withContext emptyList()
                }
                
                val eventsArray = json.getAsJsonArray("events")
                if (eventsArray == null) {
                    AppLogger.w(TAG, "getFallHistory: no events array in response")
                    return@withContext emptyList()
                }
                
                val events = eventsArray.map { gson.fromJson(it, FallEvent::class.java) }
                Log.i(TAG, "getFallHistory: got ${events.size} events for elderId=$eid")
                events
            } catch (e: Exception) {
                AppLogger.e(TAG, "getFallHistory error", e)
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
                    AppLogger.e(TAG, "getGeofences failed: ${response.code}")
                    return@withContext getCachedGeofences()
                }
                
                val responseBody = response.body?.string()
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                if (json.get("success")?.asBoolean != true) {
                    AppLogger.e(TAG, "getGeofences server error: ${json.get("message")?.asString}")
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
                        AppLogger.e(TAG, "Failed to parse fence: ${e.message}")
                        null
                    }
                }
                
                cacheGeofences(fences)
                fences
            } catch (e: Exception) {
                AppLogger.e(TAG, "getGeofences error", e)
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
                    AppLogger.e(TAG, "addGeofence: elderId is null, not bound")
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
                AppLogger.e(TAG, "addGeofence error", e)
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
                AppLogger.e(TAG, "updateGeofence error", e)
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
                    addProperty("creatorId", userId ?: "") // 云函数权限检查需要
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
                AppLogger.e(TAG, "deleteGeofence error", e)
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
            AppLogger.e(TAG, "getCachedGeofences error, clearing cache", e)
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
        val pullRequestTime: Long? = null,        // 上次拉取请求时间戳
        val lastFallEvent: LastFallEvent? = null   // 最近跌倒事件（精简版，供通知检测）
    )
    
    data class LastFallEvent(
        val eventId: String = "",
        val timestamp: Long = 0,
        val impactG: Double = 0.0,
        val mlScore: Double = 0.0,
        val latitude: Double? = null,
        val longitude: Double? = null
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
