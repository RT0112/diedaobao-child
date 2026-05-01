package com.familyguardian.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.familyguardian.app.databinding.ActivityMainBinding
import com.familyguardian.app.ui.PermissionActivity

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // 全局异常捕获
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("CrashHandler", "Uncaught exception in ${thread.name}", throwable)
            // 重启App
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
