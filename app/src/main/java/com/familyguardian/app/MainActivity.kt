package com.familyguardian.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.familyguardian.app.databinding.ActivityMainBinding
import com.familyguardian.app.ui.PermissionActivity
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // 全局异常捕获（持久化崩溃日志到 files/crash_log.txt）
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val sw = java.io.StringWriter()
            throwable.printStackTrace(java.io.PrintWriter(sw))
            val crashMsg = buildString {
                append("=== Crash @ $timestamp ===\n")
                append("Thread: ${thread.name}\n")
                append("Message: ${throwable.message}\n")
                append("\n--- StackTrace ---\n")
                append(sw.toString())
            }
            Log.e("CrashHandler", crashMsg)
            try {
                val crashFile = File(filesDir, "crash_log.txt")
                crashFile.writeText(crashMsg)
                Log.i("CrashHandler", "Crash log written to ${crashFile.absolutePath}")
            } catch (e: Exception) {
                Log.e("CrashHandler", "Failed to write crash log", e)
            }
            // 重启App回到首页
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        
        binding.bottomNav.setupWithNavController(navController)
        
        // 首次启动自动跳转权限设置页
        checkAndRequestPermissions()
    }
    
    private fun checkAndRequestPermissions() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val hasRequestedPermissions = prefs.getBoolean("has_requested_permissions", false)
        
        if (!hasRequestedPermissions) {
            startActivity(Intent(this, PermissionActivity::class.java))
            prefs.edit().putBoolean("has_requested_permissions", true).apply()
        }
    }
}
