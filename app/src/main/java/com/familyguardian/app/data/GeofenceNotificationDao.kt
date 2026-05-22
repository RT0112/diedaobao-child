package com.familyguardian.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GeofenceNotificationDao {
    @Query("SELECT * FROM geofence_notifications ORDER BY timestamp DESC")
    fun getAll(): Flow<List<GeofenceNotification>>

    @Query("SELECT * FROM geofence_notifications ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<GeofenceNotification>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notification: GeofenceNotification): Long

    @Update
    suspend fun update(notification: GeofenceNotification)

    @Query("UPDATE geofence_notifications SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: Long)

    @Query("UPDATE geofence_notifications SET isRead = 1")
    suspend fun markAllRead()

    @Query("SELECT COUNT(*) FROM geofence_notifications WHERE isRead = 0")
    suspend fun getUnreadCount(): Int

    @Query("SELECT COUNT(*) FROM geofence_notifications WHERE isRead = 0")
    fun getUnreadCountFlow(): Flow<Int>

    @Delete
    suspend fun delete(notification: GeofenceNotification)

    @Query("DELETE FROM geofence_notifications")
    suspend fun deleteAll()
}
