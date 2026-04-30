package com.familyguardian.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.familyguardian.app.R
import com.google.android.material.card.MaterialCardView

class PermissionActivity : AppCompatActivity() {

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private data class PermissionItem(
        val name: String,
        val desc: String,
        val permission: String,
        val required: Boolean = true
    )

    private val permissionItems = mutableListOf<PermissionItem>().apply {
        add(PermissionItem("📍 精确定位", "查看老人位置", Manifest.permission.ACCESS_FINE_LOCATION))
        add(PermissionItem("📍 大致定位", "辅助定位", Manifest.permission.ACCESS_COARSE_LOCATION, false))
        add(PermissionItem("📞 电话", "一键拨打老人电话", Manifest.permission.CALL_PHONE))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(PermissionItem("🔔 通知", "接收跌倒报警通知", Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { _ ->
            refreshUI()
        }

        setupUI()
    }

    private fun setupUI() {
        val scrollView = android.widget.ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
        }

        val title = TextView(this).apply {
            text = "🔐 权限设置"
            textSize = 28f
            setTextColor(0xFF212121.toInt())
            setPadding(0, 0, 0, 16)
        }
        container.addView(title)

        val subtitle = TextView(this).apply {
            text = "亲情守护需要以下权限才能正常工作"
            textSize = 14f
            setTextColor(0xFF757575.toInt())
            setPadding(0, 0, 0, 32)
        }
        container.addView(subtitle)

        permissionItems.forEach { item ->
            val card = createPermissionCard(item)
            container.addView(card)
        }

        // 特殊权限卡片
        container.addView(createSpecialPermissionCard())

        val btnGrantAll = Button(this).apply {
            text = "一键申请所有权限"
            setBackgroundColor(0xFF4CAF50.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(32, 24, 32, 24)
            setOnClickListener { requestAllPermissions() }
        }
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 32 }
        container.addView(btnGrantAll, btnParams)

        val btnDone = Button(this).apply {
            text = "完成"
            setOnClickListener { finish() }
        }
        container.addView(btnDone, btnParams)

        scrollView.addView(container)
        setContentView(scrollView)
    }

    private fun createPermissionCard(item: PermissionItem): MaterialCardView {
        val granted = ContextCompat.checkSelfPermission(this, item.permission) == PackageManager.PERMISSION_GRANTED

        return MaterialCardView(this).apply {
            radius = 16f
            cardElevation = 4f
            setContentPadding(24, 24, 24, 24)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
            layoutParams = params

            val layout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

            val textLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameView = TextView(context).apply {
                text = item.name + if (item.required) " *" else ""
                textSize = 18f
            }

            val descView = TextView(context).apply {
                text = item.desc + "\n状态: ${if (granted) "✅ 已授权" else "❌ 未授权"}"
                textSize = 12f
                setTextColor(0xFF757575.toInt())
            }

            textLayout.addView(nameView)
            textLayout.addView(descView)

            val btn = Button(context).apply {
                text = if (granted) "已开启" else "去设置"
                isEnabled = !granted
                setOnClickListener {
                    if (shouldShowRequestPermissionRationale(item.permission)) {
                        permissionLauncher.launch(arrayOf(item.permission))
                    } else {
                        openAppSettings()
                    }
                }
            }

            layout.addView(textLayout)
            layout.addView(btn)
            addView(layout)
        }
    }

    private fun createSpecialPermissionCard(): MaterialCardView {
        val overlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        val batteryGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true

        return MaterialCardView(this).apply {
            radius = 16f
            cardElevation = 4f
            setContentPadding(24, 24, 24, 24)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
            layoutParams = params

            val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

            val title = TextView(context).apply {
                text = "🔐 特殊权限（需手动开启）"
                textSize = 14f
                setTextColor(0xFF888888.toInt())
                setPadding(0, 8, 0, 16)
            }
            container.addView(title)

            // 电池优化白名单
            val batteryRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 0)
                val name = TextView(context).apply {
                    text = "🔋 电池优化\n允许后台接收报警推送"
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val btn = Button(context).apply {
                    text = if (batteryGranted) "✅ 已开启" else "去设置"
                    isEnabled = !batteryGranted
                    setOnClickListener {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            })
                        }
                    }
                }
                addView(name)
                addView(btn)
            }
            container.addView(batteryRow)

            // 开机自启
            val autoStartRow = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 16, 0, 0)
                val titleRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val name = TextView(context).apply {
                        text = "🚀 开机自启"
                        textSize = 15f
                    }
                    val openBtn = Button(context).apply {
                        text = "去设置"
                        setOnClickListener { openAutoStartSettings() }
                    }
                    addView(name)
                    addView(openBtn)
                }
                val desc = TextView(context).apply {
                    text = "⚠️ 建议开启！手机重启后可自动接收报警"
                    textSize = 13f
                    setTextColor(0xFFFF9800.toInt())
                }
                addView(titleRow)
                addView(desc)
            }
            container.addView(autoStartRow)

            // 通知设置
            val notifRow = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 16, 0, 0)
                val titleRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val name = TextView(context).apply {
                        text = "🔔 通知设置"
                        textSize = 15f
                    }
                    val openBtn = Button(context).apply {
                        text = "去设置"
                        setOnClickListener { openNotificationSettings() }
                    }
                    addView(name)
                    addView(openBtn)
                }
                val desc = TextView(context).apply {
                    text = "⚠️ 必须开启「锁屏通知」「横幅通知」！否则跌倒报警弹不出来"
                    textSize = 13f
                    setTextColor(0xFFD32F2F.toInt())
                }
                val tutorialBtn = Button(context).apply {
                    text = "查看教程"
                    setOnClickListener { showNotificationTutorial() }
                }
                addView(titleRow)
                addView(desc)
                addView(tutorialBtn)
            }
            container.addView(notifRow)

            addView(container)
        }
    }

    private fun openAutoStartSettings() {
        val intents = listOfNotNull(
            // 小米/红米 MIUI
            Intent().apply {
                component = android.content.ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            },
            // OPPO/Realme ColorOS
            Intent().apply {
                component = android.content.ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                )
            },
            Intent().apply {
                component = android.content.ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            },
            // Vivo/Funtouch OS
            Intent().apply {
                component = android.content.ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
            },
            // 华为/鸿蒙 EMUI
            Intent().apply {
                component = android.content.ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            },
            // 三星
            Intent().apply {
                component = android.content.ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"
                )
            },
            // OnePlus
            Intent().apply {
                component = android.content.ComponentName(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                )
            },
            // 魅族 Flyme
            Intent().apply {
                component = android.content.ComponentName(
                    "com.meizu.safe",
                    "com.meizu.safe.permission.SmartPermissionsActivity"
                )
            },
            // 联想 ZUI
            Intent().apply {
                component = android.content.ComponentName(
                    "com.lenovo.security",
                    "com.lenovo.security.firewall.StartupActivity"
                )
            },
            // 中兴 MyOS
            Intent().apply {
                component = android.content.ComponentName(
                    "com.zte.heartyservice",
                    "com.zte.heartyservice.autoboot.BootAppListActivity"
                )
            }
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) { }
        }
        // 全部失败：打开应用详情页面
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${packageName}")
            })
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun openNotificationSettings() {
        val notifIntents = listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, "emergency")
                }
            } else null,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                } else {
                    putExtra("app_package", packageName)
                    putExtra("app_uid", applicationInfo.uid)
                }
            }
        )
        for (intent in notifIntents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) { }
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${packageName}")
            })
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun showNotificationTutorial() {
        val brand = Build.MANUFACTURER.lowercase()
        val brandGuide = when {
            brand.contains("xiaomi") || brand.contains("redmi") ->
                "【小米/红米 MIUI】\n1. 设置 → 通知管理 → 亲情守护\n2. 开启「允许通知」\n3. 开启「锁屏通知」→ 全部显示\n4. 开启「横幅通知」→ 允许\n5. 优先级设为「紧急」"
            brand.contains("huawei") || brand.contains("honor") ->
                "【华为/荣耀 EMUI/鸿蒙】\n1. 设置 → 通知 → 亲情守护\n2. 开启「允许通知」\n3. 开启「锁屏通知」→ 显示全部\n4. 开启「横幅通知」\n5. 通知方式设为「紧急」"
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") ->
                "【OPPO/Realme/一加 ColorOS】\n1. 设置 → 通知与状态栏 → 亲情守护\n2. 开启「允许通知」\n3. 开启「锁屏通知」\n4. 开启「横幅通知」\n5. 重要性设为「紧急」"
            brand.contains("vivo") || brand.contains("iqoo") ->
                "【Vivo/iQOO FunTouch】\n1. 设置 → 通知与状态栏 → 亲情守护\n2. 开启「允许通知」\n3. 开启「锁屏通知」→ 显示通知\n4. 开启「横幅通知」\n5. 优先级设为「紧急」"
            brand.contains("samsung") ->
                "【三星 OneUI】\n1. 设置 → 通知 → 亲情守护\n2. 开启「允许通知」\n3. 开启「锁屏通知」\n4. 通知类别设为「紧急」"
            brand.contains("meizu") ->
                "【魅族 Flyme】\n1. 设置 → 通知管理 → 亲情守护\n2. 开启「允许通知」\n3. 开启「锁屏显示」\n4. 开启「横幅通知」\n5. 优先级设为「紧急」"
            else ->
                "【通用设置方法】\n1. 设置 → 应用管理 → 亲情守护\n2. 点击「通知」\n3. 开启「允许通知」\n4. 开启「锁屏通知」\n5. 开启「横幅通知」\n6. 优先级设为「紧急」"
        }
        AlertDialog.Builder(this)
            .setTitle("🔔 通知设置教程")
            .setMessage(
                "跌倒报警需要通知权限才能弹出！\n\n" +
                brandGuide +
                "\n\n💡 所有通知类型都要开，否则老人跌倒时可能看不到报警！"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun requestAllPermissions() {
        val missing = permissionItems
            .filter { ContextCompat.checkSelfPermission(this, it.permission) != PackageManager.PERMISSION_GRANTED }
            .map { it.permission }
            .toTypedArray()

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing)
        } else {
            Toast.makeText(this, "运行时权限已授权", Toast.LENGTH_SHORT).show()
        }

        // 检查电池优化
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle("🔋 电池优化权限")
                    .setMessage("需要将亲情守护设为「无限制」才能稳定接收报警推送。")
                    .setPositiveButton("去设置") { _, _ ->
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        })
                    }
                    .setNegativeButton("稍后", null)
                    .show()
                return
            }
        }

        Toast.makeText(this, "所有权限已就绪！", Toast.LENGTH_LONG).show()
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        })
    }

    private fun refreshUI() {
        setupUI()
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }
}
