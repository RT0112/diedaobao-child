package com.familyguardian.app.cloud

import android.content.Context
import android.content.SharedPreferences
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
 */
object CloudBaseClient {
    
    private const val TAG = "CloudBaseClient"
    private const val ENV_ID = "diedaobao-cdn-d4g496tvv296f0ac2"
    private const val BASE_URL = "https://$ENV_ID-1409685971.ap-shanghai.app.tcloudbase.com"
    
    private const val PREFS_NAME = "cloudbase_prefs"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_ELDER_ID = "elder_id" // 绑定的老人ID
    
    private val client = OkHttpClient()
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
     * 检查是否已绑定老人
     */
    fun hasBoundElder(): Boolean = elderId != null
    
    /**
     * 获取绑定的老人ID
     */
    fun getElderId(): String? = elderId
    
    /**
     * 绑定老人
     */
    suspend fun bindElder(context: Context, bindCode: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/bind-family"
                val body = JsonObject().apply {
                    addProperty("bindCode", bindCode)
                    addProperty("guardianId", userId ?: return@withContext false)
                }
                
                val request = Request.Builder()
                    .url(url)
                    .post(gson.toJson(body).toRequestBody(jsonMediaType))
                    .build()
                
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "bindElder failed: ${response.code}")
                    return@withContext false
                }
                
                val responseBody = response.body?.string()
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                val newElderId = json.get("elderId")?.asString
                
                if (newElderId != null) {
                    elderId = newElderId
                    prefs.edit().putString(KEY_ELDER_ID, newElderId).apply()
                    Log.i(TAG, "Elder bound: $newElderId")
                    true
                } else {
                    Log.e(TAG, "bindElder: no elderId in response")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "bindElder error", e)
                false
            }
        }
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
    data class ElderStatus(
        val elderId: String,
        val name: String,
        val lastLocation: Location?,
        val lastUpdate: Long,
        val status: String // "normal" | "fallen"
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
