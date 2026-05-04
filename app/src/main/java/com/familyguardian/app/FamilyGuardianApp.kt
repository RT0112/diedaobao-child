package com.familyguardian.app

import android.app.Application
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.familyguardian.app.cloud.CloudBaseClient
import com.familyguardian.app.util.AppLogger
import java.io.PrintWriter
import java.io.StringWriter

class FamilyGuardianApp : Application() {
    
    companion object {
        lateinit var instance: FamilyGuardianApp
            private set
        // 崩溃日志文件路径
        val crashLogFile get() = "${instance.getExternalFilesDir(null)?.absolutePath}/crash_log.txt"
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // 全局异常处理（捕获并保存日志到文件）
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stack = sw.toString()
            
            // 1. 写 LogCat + 云端上传
            AppLogger.e("CRASH", stack)
            
            // 2. 写崩溃日志文件（SD卡）
            try {
                val logPath = crashLogFile
                val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                val logEntry = "\n========== CRASH $time ==========\nThread: ${thread.name}\n$stack\n"
                java.io.FileWriter(logPath, true).use { fw -> fw.append(logEntry) }
                Log.i("CRASH", "Saved to: $logPath")
            } catch (e: Exception) {
                AppLogger.e("CRASH", "Failed to write crash log", e)
            }
            
            // Toast 显示简要错误（API 33+ 发通知）
            val msg = throwable::class.java.simpleName + ": " + (throwable.message?.take(80) ?: "")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val ti = android.app.NotificationChannel(
                        "crash", "崩溃日志", android.app.NotificationManager.IMPORTANCE_HIGH
                    )
                    val nm = getSystemService(android.app.NotificationManager::class.java)
                    nm.createNotificationChannel(ti)
                    nm.notify(
                        9999,
                        android.app.Notification.Builder(this, "crash")
                            .setContentTitle("子女端闪退")
                            .setContentText(msg)
                            .setStyle(android.app.Notification.BigTextStyle().bigText(stack.take(500)))
                            .build()
                    )
                } else {
                    Toast.makeText(this, "闪退: $msg", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                // 忽略通知权限等错误
            }
            
            defaultHandler?.uncaughtException(thread, throwable)
        }
        
        CloudBaseClient.init(this)
        AppLogger.init(this, CloudBaseClient.getUserId() ?: "guardian_${Build.SERIAL}")
        AppLogger.i("FamilyGuardianApp", "onCreate done, SDK=${Build.VERSION.SDK_INT}")
    }
}