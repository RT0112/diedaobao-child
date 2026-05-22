package com.familyguardian.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 跌倒通知 — 本地存储，短信式收件箱
 * 只有真跌倒（老人未点"我没事"）才会产生通知
 */
@Entity(tableName = "fall_notifications")
data class FallNotification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String = "",           // 云端fall_report的ID（用于去重）
    val elderName: String = "",         // 老人姓名
    val timestamp: Long = System.currentTimeMillis(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val impactG: Double = 0.0,
    val mlScore: Double = 0.0,
    val isRead: Boolean = false,        // 是否已读
    val isHandled: Boolean = false,     // 是否已处理（打电话/查看位置等）
    // v0.47: 完整检测数据（供反馈页面显示）
    @ColumnInfo(defaultValue = "0") val ffDuration: Long = 0L,
    @ColumnInfo(defaultValue = "0.0") val physicalScore: Float = 0f,
    @ColumnInfo(defaultValue = "0.0") val weightedScore: Float = 0f,
    @ColumnInfo(defaultValue = "") val decisionPath: String = "",
    @ColumnInfo(defaultValue = "[]") val sensorDataJson: String = "[]",
    @ColumnInfo(defaultValue = "0.0") val feedRate: Float = 0f
)
