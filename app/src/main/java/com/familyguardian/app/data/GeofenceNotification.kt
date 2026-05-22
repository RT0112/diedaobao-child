package com.familyguardian.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 围栏越界通知 — 本地存储，短信式收件箱
 */
@Entity(tableName = "geofence_notifications")
data class GeofenceNotification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val elderId: String = "",
    val elderName: String = "",
    val breaches: String = "",     // 逗号分隔的围栏名称列表
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = false
)
