package com.familyguardian.app

import com.familyguardian.app.util.AppLogger
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.familyguardian.app.databinding.ActivityMainBinding
import com.familyguardian.app.ui.*
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    
    // Tab Fragment 缓存 — 用 hide/show 替代 Navigation 默认的 destroy/recreate
    // 这样切 tab 时 Fragment 不被销毁，远程协助画面不会丢失
    private val fragments = mutableMapOf<Int, Fragment>()
    private var currentTabId = R.id.nav_home
    
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
            AppLogger.e("CrashHandler", crashMsg)
            try {
                val crashFile = File(filesDir, "crash_log.txt")
                crashFile.writeText(crashMsg)
                Log.i("CrashHandler", "Crash log written to ${crashFile.absolutePath}")
            } catch (e: Exception) {
                AppLogger.e("CrashHandler", "Failed to write crash log", e)
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
        
        // 初始化第一个 tab
        if (savedInstanceState == null) {
            val home = HomeFragment()
            fragments[R.id.nav_home] = home
            supportFragmentManager.beginTransaction()
                .add(R.id.nav_host_fragment, home, "tab_home")
                .commit()
        } else {
            // 恢复已有 Fragment 引用
            restoreFragments()
        }
        
        // 底部导航 — 手动切换，不用 NavigationUI
        binding.bottomNav.setOnItemSelectedListener { item ->
            switchTab(item.itemId)
            true
        }
        
        // 首次启动自动跳转权限设置页
        checkAndRequestPermissions()
    }
    
    private fun restoreFragments() {
        // 从 FragmentManager 找回已存在的 Fragment
        listOf(
            R.id.nav_home to "tab_home",
            R.id.nav_geofence to "tab_geofence",
            R.id.nav_history to "tab_history",
            R.id.nav_remote_assist to "tab_assist",
            R.id.nav_settings to "tab_settings"
        ).forEach { (id, tag) ->
            supportFragmentManager.findFragmentByTag(tag)?.let {
                fragments[id] = it
            }
        }
    }
    
    private fun switchTab(tabId: Int) {
        if (tabId == currentTabId) return
        
        val currentFrag = fragments[currentTabId]
        val targetFrag = getOrCreateFragment(tabId)
        
        supportFragmentManager.beginTransaction().apply {
            // 隐藏当前
            currentFrag?.let { hide(it) }
            // 显示目标
            if (targetFrag.isAdded) {
                show(targetFrag)
            } else {
                add(R.id.nav_host_fragment, targetFrag, getTagForTab(tabId))
            }
        }.commit()
        
        currentTabId = tabId
    }
    
    private fun getOrCreateFragment(tabId: Int): Fragment {
        return fragments.getOrPut(tabId) {
            when (tabId) {
                R.id.nav_home -> HomeFragment()
                R.id.nav_geofence -> GeofenceFragment()
                R.id.nav_history -> HistoryFragment()
                R.id.nav_remote_assist -> RemoteAssistFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> HomeFragment()
            }
        }
    }
    
    private fun getTagForTab(tabId: Int): String {
        return when (tabId) {
            R.id.nav_home -> "tab_home"
            R.id.nav_geofence -> "tab_geofence"
            R.id.nav_history -> "tab_history"
            R.id.nav_remote_assist -> "tab_assist"
            R.id.nav_settings -> "tab_settings"
            else -> "tab_home"
        }
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
