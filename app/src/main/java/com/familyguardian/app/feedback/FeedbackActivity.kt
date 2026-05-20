package com.familyguardian.app.feedback

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.familyguardian.app.databinding.ActivityFeedbackBinding
import kotlinx.coroutines.launch
import android.content.pm.PackageManager

/**
 * 意见反馈页面
 * - 反馈类型：Bug反馈 / 改进建议 / 其他
 * - 联系方式（选填）
 * - 反馈内容
 */
class FeedbackActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFeedbackBinding
    private val TAG = "FeedbackActivity"

    private val feedbackTypes = arrayOf(
        "Bug反馈",
        "改进建议",
        "其他"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            Log.d(TAG, "onCreate: step1 - inflating binding")
            binding = ActivityFeedbackBinding.inflate(layoutInflater)
            Log.d(TAG, "onCreate: step2 - setting content view")
            setContentView(binding.root)
            Log.d(TAG, "onCreate: step3 - setupToolbar")
            setupToolbar()
            Log.d(TAG, "onCreate: step4 - setupTypeSpinner")
            setupTypeSpinner()
            Log.d(TAG, "onCreate: step5 - setupSubmitButton")
            setupSubmitButton()
            Log.d(TAG, "onCreate: step6 - done")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate CRASH: ${e.javaClass.simpleName}: ${e.message}", e)
            throw e
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupTypeSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, feedbackTypes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerFeedbackType.adapter = adapter
    }

    private fun setupSubmitButton() {
        binding.btnSubmit.setOnClickListener {
            submitFeedback()
        }
    }

    private fun submitFeedback() {
        val typePosition = binding.spinnerFeedbackType.selectedItemPosition
        val feedbackType = feedbackTypes[typePosition]

        val contact = binding.etContact.text.toString().trim()

        val content = binding.etFeedbackContent.text.toString().trim()
        if (content.isEmpty()) {
            binding.etFeedbackContent.error = "请填写反馈内容"
            return
        }

        val deviceModel = android.os.Build.MODEL
        val androidVersion = android.os.Build.VERSION.RELEASE
        val appVersion = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA).versionName ?: ""
        } catch (_: Exception) { "" }

        lifecycleScope.launch {
            binding.btnSubmit.isEnabled = false
            try {
                val result = FeedbackSender.submit(
                    this@FeedbackActivity,
                    feedbackType,
                    contact,
                    content,
                    deviceModel,
                    androidVersion,
                    appVersion
                )
                if (result.isSuccess) {
                    Log.d(TAG, "✅ 反馈提交成功")
                    Toast.makeText(this@FeedbackActivity, "感谢您的反馈！", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    val e = result.exceptionOrNull()
                    Log.e(TAG, "❌ 提交失败: ${e?.message}")
                    Toast.makeText(this@FeedbackActivity, "提交失败: ${e?.message}", Toast.LENGTH_LONG).show()
                    binding.btnSubmit.isEnabled = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ 提交失败: ${e.message}")
                Toast.makeText(this@FeedbackActivity, "提交失败: ${e.message}", Toast.LENGTH_LONG).show()
                binding.btnSubmit.isEnabled = true
            }
        }
    }
}
