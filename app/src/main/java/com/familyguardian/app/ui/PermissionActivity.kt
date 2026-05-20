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
        val act = this
        val overlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(act)
        } else true

        val batteryGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true

        val card = MaterialCardView(act)
        card.radius = 16f
        card.cardElevation = 4f
        card.setContentPadding(48, 48, 48, 48)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.bottomMargin = 32
        card.layoutParams = params

        val container = LinearLayout(act)
        container.orientation = LinearLayout.VERTICAL

        // 标题
        val title = TextView(act)
        title.text = "🔐 特殊权限（需手动开启）"
        title.textSize = 14f
        title.setTextColor(0xFF888888.toInt())
        title.setPadding(0, 8, 0, 24)
        container.addView(title)

        // 1. 锁屏显示（悬浮窗权限）
        val lsRow = LinearLayout(act)
        lsRow.orientation = LinearLayout.HORIZONTAL
        lsRow.setPadding(0, 24, 0, 0)
        val lsName = TextView(act)
        lsName.text = "🔲 锁屏显示\n允许锁屏时弹出全屏告警界面"
        lsName.textSize = 15f
        lsName.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val lsBtn = Button(act)
        lsBtn.text = if (overlayGranted) "✅ 已开启" else "去设置"
        lsBtn.isEnabled = !overlayGranted
        lsBtn.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${act.packageName}")))
            }
        }
        lsRow.addView(lsName)
        lsRow.addView(lsBtn)
        container.addView(lsRow)

        // 2. 后台弹出界面
        val bgRow = LinearLayout(act)
        bgRow.orientation = LinearLayout.VERTICAL
        bgRow.setPadding(0, 24, 0, 0)
        val bgName = TextView(act)
        bgName.text = "📱 后台弹出界面"
        bgName.textSize = 15f
        val bgDesc = TextView(act)
        bgDesc.text = "小米/华为/OPPO等厂商特有，后台也能弹出界面"
        bgDesc.textSize = 13f
        bgDesc.setTextColor(0xFF888888.toInt())
        val bgBtnRow = LinearLayout(act)
        bgBtnRow.orientation = LinearLayout.HORIZONTAL
        bgBtnRow.gravity = android.view.Gravity.END
        val bgTutorialBtn = Button(act)
        bgTutorialBtn.text = "查看教程"
        bgTutorialBtn.setOnClickListener {
            AlertDialog.Builder(act)
                .setTitle("后台弹出界面权限教程")
                .setMessage(
                    "这是小米/华为/OPPO等厂商的特殊权限。\n\n" +
                    "开启方法：\n" +
                    "1. 打开手机「设置」\n" +
                    "2. 找到「应用管理」或「应用与权限」\n" +
                    "3. 找到「亲情守护」\n" +
                    "4. 找到「权限管理」\n" +
                    "5. 找到「后台弹出界面」设为「允许」\n" +
                    "6. 找到「锁屏显示」设为「允许」\n\n" +
                    "不同品牌路径略有差异"
                )
                .setPositiveButton("知道了", null)
                .show()
        }
        val bgOpenBtn = Button(act)
        bgOpenBtn.text = "去设置"
        bgOpenBtn.setOnClickListener {
            act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${act.packageName}")
            })
        }
        bgBtnRow.addView(bgTutorialBtn)
        bgBtnRow.addView(bgOpenBtn)
        bgRow.addView(bgName)
        bgRow.addView(bgDesc)
        bgRow.addView(bgBtnRow)
        container.addView(bgRow)

        // 3. 电池优化白名单
        val batRow = LinearLayout(act)
        batRow.orientation = LinearLayout.HORIZONTAL
        batRow.setPadding(0, 24, 0, 0)
        val batName = TextView(act)
        batName.text = "🔋 电池优化\n允许后台接收报警推送"
        batName.textSize = 15f
        batName.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val batBtn = Button(act)
        batBtn.text = if (batteryGranted) "✅ 已开启" else "去设置"
        batBtn.isEnabled = !batteryGranted
        batBtn.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                act.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${act.packageName}")
                })
            }
        }
        batRow.addView(batName)
        batRow.addView(batBtn)
        container.addView(batRow)

        // 4. 开机自启
        val asRow = LinearLayout(act)
        asRow.orientation = LinearLayout.VERTICAL
        asRow.setPadding(0, 24, 0, 0)
        val asTitleRow = LinearLayout(act)
        asTitleRow.orientation = LinearLayout.HORIZONTAL
        val asName = TextView(act)
        asName.text = "🚀 开机自启"
        asName.textSize = 15f
        val asBtn = Button(act)
        asBtn.text = "去设置"
        asBtn.setOnClickListener { act.openAutoStartSettings() }
        asTitleRow.addView(asName)
        asTitleRow.addView(asBtn)
        val asDesc = TextView(act)
        asDesc.text = "⚠️ 建议开启！手机重启后可自动接收报警"
        asDesc.textSize = 13f
        asDesc.setTextColor(0xFFFF9800.toInt())
        asRow.addView(asTitleRow)
        asRow.addView(asDesc)
        container.addView(asRow)

        // 5. 通知设置
        val notifRow = LinearLayout(act)
        notifRow.orientation = LinearLayout.VERTICAL
        notifRow.setPadding(0, 24, 0, 0)
        val notifTitleRow = LinearLayout(act)
        notifTitleRow.orientation = LinearLayout.HORIZONTAL
        val notifName = TextView(act)
        notifName.text = "🔔 通知设置"
        notifName.textSize = 15f
        val notifBtn = Button(act)
        notifBtn.text = "去设置"
        notifBtn.setOnClickListener { act.openNotificationSettings() }
        notifTitleRow.addView(notifName)
        notifTitleRow.addView(notifBtn)
        val notifDesc = TextView(act)
        notifDesc.text = "⚠️ 必须开启「锁屏通知」「横幅通知」！否则跌倒报警弹不出来"
        notifDesc.textSize = 13f
        notifDesc.setTextColor(0xFFD32F2F.toInt())
        val notifTutorialBtn = Button(act)
        notifTutorialBtn.text = "查看教程"
        notifTutorialBtn.setOnClickListener { act.showNotificationTutorial() }
        notifRow.addView(notifTitleRow)
        notifRow.addView(notifDesc)
        notifRow.addView(notifTutorialBtn)
        container.addView(notifRow)

        card.addView(container)
        return card
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


    private fun openAutoStartSettings() {
        val intents = listOfNotNull(
            Intent().apply { component = android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity") },
            Intent().apply { component = android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity") },
            Intent().apply { component = android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity") },
            Intent().apply { component = android.content.ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity") },
            Intent().apply { component = android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity") },
            Intent().apply { component = android.content.ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity") },
            Intent().apply { component = android.content.ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity") },
            Intent().apply { component = android.content.ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartPermissionsActivity") },
            Intent().apply { component = android.content.ComponentName("com.lenovo.security", "com.lenovo.security.firewall.StartupActivity") },
            Intent().apply { component = android.content.ComponentName("com.zte.heartyservice", "com.zte.heartyservice.autoboot.BootAppListActivity") }
        )
        for (intent in intents) {
            try { startActivity(intent); return } catch (_: Exception) { }
        }
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:${packageName}") }) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun openNotificationSettings() {
        val notifIntents = listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply { putExtra(Settings.EXTRA_APP_PACKAGE, packageName); putExtra(Settings.EXTRA_CHANNEL_ID, "emergency") } else null,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) putExtra(Settings.EXTRA_APP_PACKAGE, packageName) else { putExtra("app_package", packageName); putExtra("app_uid", applicationInfo.uid) } }
        )
        for (intent in notifIntents) { try { startActivity(intent); return } catch (_: Exception) { } }
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:${packageName}") }) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun showNotificationTutorial() {
        val brand = Build.MANUFACTURER.lowercase()
        val guide = when {
            brand.contains("xiaomi") || brand.contains("redmi") -> "【小米/红米 MIUI】\n1. 设置 → 通知管理 → 亲情守护\n2. 开启「允许通知」「锁屏通知→全部显示」「横幅通知→允许」「优先级→紧急」"
            brand.contains("huawei") || brand.contains("honor") -> "【华为/荣耀 EMUI/鸿蒙】\n1. 设置 → 通知 → 亲情守护\n2. 开启「允许通知」「锁屏通知→显示全部」「横幅通知」「通知方式→紧急」"
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") -> "【OPPO/Realme/一加 ColorOS】\n1. 设置 → 通知与状态栏 → 亲情守护\n2. 开启所有通知类型，重要性设为「紧急」"
            brand.contains("vivo") || brand.contains("iqoo") -> "【Vivo/iQOO FunTouch】\n1. 设置 → 通知与状态栏 → 亲情守护\n2. 开启所有通知，优先级设为「紧急」"
            brand.contains("samsung") -> "【三星 OneUI】\n1. 设置 → 通知 → 亲情守护\n2. 开启「允许通知」「锁屏通知」「通知类别→紧急」"
            brand.contains("meizu") -> "【魅族 Flyme】\n1. 设置 → 通知管理 → 亲情守护\n2. 开启「允许通知」「锁屏显示」「横幅通知」「优先级→紧急」"
            else -> "【通用设置】\n1. 设置 → 应用管理 → 亲情守护 → 通知\n2. 开启所有通知，优先级设为「紧急」"
        }
        AlertDialog.Builder(this)
            .setTitle("🔔 通知设置教程")
            .setMessage("跌倒报警需要通知权限才能弹出！\n\n$guide\n\n💡 所有通知类型都要开！")
            .setPositiveButton("知道了", null)
            .show()
    }
}
