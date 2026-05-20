package com.familyguardian.app.feedback

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.familyguardian.app.R
import com.familyguardian.app.databinding.ActivityFeedbackBinding
import com.familyguardian.app.cloud.CloudBaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 反馈页面（子女端版）
 * - 误报反馈：从后端API读取跌倒记录 + 选择场景 + "其他"自定义填写
 * - 建议反馈：文字描述
 * - 自动采集检测数据（从后端获取）
 */
class FeedbackActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFeedbackBinding
    private val TAG = "FeedbackActivity"
    
    // 场景分类（最后一个"其他"需要自定义输入）
    private val sceneCategories = arrayOf(
        "口袋放手机",
        "坐下",
        "躺下",
        "走路晃动",
        "弯腰捡东西",
        "运动锻炼",
        "上下楼梯",
        "推/碰手机",
        "其他（自定义）"
    )
    
    // 跌倒事件列表（从后端API获取）
    private var fallEvents: List<JSONObject> = emptyList()
    private var selectedEvent: JSONObject? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFeedbackBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        setupTabs()
        setupSceneSpinner()
        setupSubmitButton()
        loadFallEvents()
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }
    
    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("误报反馈"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("建议反馈"))
        
        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showMisreportForm()
                    1 -> showSuggestionForm()
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
        
        showMisreportForm()
    }
    
    private fun setupSceneSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sceneCategories)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSceneCategory.adapter = adapter
        
        // "其他"选中时显示自定义输入框
        binding.spinnerSceneCategory.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val isOther = position == sceneCategories.size - 1
                binding.etCustomScene.visibility = if (isOther) View.VISIBLE else View.GONE
                if (isOther) {
                    binding.etCustomScene.hint = "请描述具体场景"
                    binding.etCustomScene.requestFocus()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    /** 从后端API加载最近跌倒事件 */
    private fun loadFallEvents() {
        lifecycleScope.launch {
            try {
                fallEvents = withContext(Dispatchers.IO) {
                    CloudBaseClient.getFallEvents(20)  // 从后端API获取最近20条
                }
                updateEventSpinner()
            } catch (e: Exception) {
                Log.e(TAG, "❌ 加载跌倒记录失败: ${e.message}")
                fallEvents = emptyList()
                updateEventSpinner()
            }
        }
    }
    
    /** 更新跌倒记录选择列表 */
    private fun updateEventSpinner() {
        if (fallEvents.isEmpty()) {
            // 没有历史记录时，显示提示
            binding.spinnerFallEvent.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, arrayOf("暂无跌倒记录"))
            binding.tvDetectionData.text = "暂无跌倒记录\n触发跌倒检测后，数据会自动保存到这里"
            return
        }
        
        // 构建显示列表：时间 + FF时长
        val eventLabels = fallEvents.map { event ->
            val timestamp = event.optLong("timestamp", 0)
            val ffTimeMs = event.optLong("ff_time_ms", 0)
            val impactStrength = event.optDouble("impact_strength", 0.0)
            val timeStr = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
            "${timeStr} | FF=${ffTimeMs}ms | 冲击=${String.format("%.1f", impactStrength)}g"
        }
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, eventLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerFallEvent.adapter = adapter
        
        // 选择监听：切换记录时更新检测数据显示
        binding.spinnerFallEvent.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedEvent = fallEvents.getOrNull(position)
                updateDetectionDataDisplay()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // 默认选中第一条
        selectedEvent = fallEvents.firstOrNull()
        updateDetectionDataDisplay()
    }
    
    /** 更新检测数据显示 */
    private fun updateDetectionDataDisplay() {
        val event = selectedEvent
        if (event == null) {
            binding.tvDetectionData.text = "暂无数据"
            return
        }
        
        val sb = StringBuilder()
        sb.append("FF时间: ${event.optLong("ff_time_ms")}ms\n")
        sb.append("冲击强度: ${String.format("%.2f", event.optDouble("impact_strength"))}g\n")
        sb.append("ML概率: ${String.format("%.2f", event.optDouble("ml_probability"))}\n")
        sb.append("物理评分: ${String.format("%.2f", event.optDouble("physics_score"))}\n")
        sb.append("加权评分: ${String.format("%.2f", event.optDouble("weighted_score"))}\n")
        sb.append("决策路径: ${event.optString("decision_path", "未知")}\n")
        val sensorJson = event.optString("sensor_data_json", "[]")
        sb.append("传感器帧数: ${sensorJson.count { it == '{' }}帧\n")
        val timestamp = event.optLong("timestamp", 0)
        sb.append("时间: ${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(timestamp))}")
        binding.tvDetectionData.text = sb.toString()
    }
    
    private fun showMisreportForm() {
        binding.layoutMisreport.visibility = View.VISIBLE
        binding.layoutSuggestion.visibility = View.GONE
    }
    
    private fun showSuggestionForm() {
        binding.layoutMisreport.visibility = View.GONE
        binding.layoutSuggestion.visibility = View.VISIBLE
    }
    
    private fun setupSubmitButton() {
        binding.btnSubmit.setOnClickListener {
            val selectedTab = binding.tabLayout.selectedTabPosition
            if (selectedTab == 0) {
                submitMisreport()
            } else {
                submitSuggestion()
            }
        }
    }
    
    private fun submitMisreport() {
        // 场景分类
        val scenePosition = binding.spinnerSceneCategory.selectedItemPosition
        var sceneCategory: String
        if (scenePosition == sceneCategories.size - 1) {
            // "其他" → 自定义输入
            val custom = binding.etCustomScene.text.toString().trim()
            if (custom.isEmpty()) {
                binding.etCustomScene.error = "请填写具体场景"
                return
            }
            sceneCategory = custom
        } else {
            sceneCategory = sceneCategories[scenePosition]
        }
        
        // 详情描述必填
        val sceneDescription = binding.etSceneDescription.text.toString().trim()
        if (sceneDescription.isEmpty()) {
            binding.etSceneDescription.error = "请填写详细描述"
            return
        }
        
        // 使用选中的跌倒事件
        val event = selectedEvent
        if (event == null) {
            showError("请先选择一条跌倒记录")
            return
        }
        
        lifecycleScope.launch {
            binding.btnSubmit.isEnabled = false
            
            try {
                val fallEventId = event.optLong("id", 0)
                val ffTimeMs = event.optLong("ff_time_ms", 0)
                val impactStrength = event.optDouble("impact_strength", 0.0).toFloat()
                val mlProbability = event.optDouble("ml_probability", 0.0).toFloat()
                val physicsScore = event.optDouble("physics_score", 0.0).toFloat()
                val weightedScore = event.optDouble("weighted_score", 0.0).toFloat()
                val decisionPath = event.optString("decision_path", "")
                val sensorDataJson = event.optString("sensor_data_json", "[]")
                
                val deviceModel = android.os.Build.MODEL
                val androidVersion = android.os.Build.VERSION.RELEASE
                val appVersion = try {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: ""
                } catch (_: Exception) { "" }
                
                val result = FeedbackSender.submitMisreport(
                    this@FeedbackActivity,
                    fallEventId,
                    sceneCategory,
                    sceneDescription,
                    ffTimeMs,
                    impactStrength,
                    mlProbability,
                    physicsScore,
                    weightedScore,
                    decisionPath,
                    sensorDataJson,
                    deviceModel,
                    androidVersion,
                    appVersion
                )
                
                result.onSuccess {
                    Log.d(TAG, "✅ 误报反馈提交成功")
                    showSuccessAndFinish("误报反馈已提交")
                }.onFailure { e ->
                    Log.e(TAG, "❌ 提交失败: ${e.message}")
                    showError("提交失败: ${e.message}")
                    binding.btnSubmit.isEnabled = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ 读取数据失败: ${e.message}")
                showError("读取数据失败: ${e.message}")
                binding.btnSubmit.isEnabled = true
            }
        }
    }
    
    private fun submitSuggestion() {
        val content = binding.etSuggestionContent.text.toString().trim()
        
        if (content.isEmpty()) {
            binding.etSuggestionContent.error = "请填写建议内容"
            return
        }
        
        val deviceModel = android.os.Build.MODEL
        val androidVersion = android.os.Build.VERSION.RELEASE
        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }
        
        lifecycleScope.launch {
            binding.btnSubmit.isEnabled = false
            val result = FeedbackSender.submitSuggestion(
                this@FeedbackActivity,
                content,
                deviceModel,
                androidVersion,
                appVersion
            )
            
            result.onSuccess {
                Log.d(TAG, "✅ 建议反馈提交成功")
                showSuccessAndFinish("建议反馈已提交")
            }.onFailure { e ->
                Log.e(TAG, "❌ 提交失败: ${e.message}")
                showError("提交失败: ${e.message}")
                binding.btnSubmit.isEnabled = true
            }
        }
    }
    
    private fun showSuccessAndFinish(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        finish()
    }
    
    private fun showError(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }
}
