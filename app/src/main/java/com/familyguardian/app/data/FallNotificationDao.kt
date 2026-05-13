package com.familyguardian.app.data

import androidx.room.*

@Dao
interface FallNotificationDao {
    @Query("SELECT * FROM fall_notifications ORDER BY timestamp DESC")
    fun getAll(): kotlinx.coroutines.flow.Flow<List<FallNotification>>

    @Query("SELECT * FROM fall_notifications ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<FallNotification>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(notification: FallNotification): Long

    @Update
    suspend fun update(notification: FallNotification)

    @Query("UPDATE fall_notifications SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: Long)

    @Query("UPDATE fall_notifications SET isRead = 1")
    suspend fun markAllRead()

    @Query("SELECT COUNT(*) FROM fall_notifications WHERE isRead = 0")
    suspend fun getUnreadCount(): Int

    @Query("SELECT COUNT(*) FROM fall_notifications WHERE isRead = 0")
    fun getUnreadCountFlow(): kotlinx.coroutines.flow.Flow<Int>

    @Query("SELECT * FROM fall_notifications WHERE eventId = :eventId LIMIT 1")
    suspend fun getByEventId(eventId: String): FallNotification?

    @Delete
    suspend fun delete(notification: FallNotification)

    @Query("DELETE FROM fall_notifications")
    suspend fun deleteAll()
}
