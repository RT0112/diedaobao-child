package com.familyguardian.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.familyguardian.app.cloud.CloudBaseClient
import com.familyguardian.app.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {
    
    private var _binding: FragmentSettingsBinding? = null
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return _binding!!.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val b = _binding ?: return
        
        // 权限设置
        b.btnPermissionSettings.setOnClickListener { openPermissionSettings() }
        
        // 强制弹窗通知开关
        val prefs = requireContext().getSharedPreferences("family_guardian_settings", 0)
        b.switchForcePopup.isChecked = prefs.getBoolean("force_popup_notification", true)
        b.switchForcePopup.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("force_popup_notification", isChecked).apply()
            android.widget.Toast.makeText(
                requireContext(),
                if (isChecked) "已开启强制弹窗通知" else "已关闭强制弹窗通知，使用普通通知",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        
        // 解绑按钮
        b.btnUnbind.setOnClickListener { showResetConfirm() }
        
        // 关于按钮
        b.btnAbout.setOnClickListener { showAbout() }
        
        // 更新绑定状态显示
        updateBindingStatus()
    }
    
    private fun openPermissionSettings() {
        startActivity(Intent(requireContext(), PermissionActivity::class.java))
    }
    
    private fun updateBindingStatus() {
        val b = _binding ?: return
        val hasBound = CloudBaseClient.hasBoundElder()
        val context = context ?: return
        
        if (hasBound) {
            b.tvBindingInfo.text = "已绑定老人（ID: ${CloudBaseClient.getElderId()?.take(8)}...）"
            b.tvBindingInfo.setTextColor(ContextCompat.getColor(context, android.R.color.holo_green_dark))
            b.btnUnbind.visibility = View.VISIBLE
        } else {
            b.tvBindingInfo.text = "未绑定老人，请在首页绑定"
            b.tvBindingInfo.setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            b.btnUnbind.visibility = View.GONE
        }
    }
    
    private fun showResetConfirm() {
        AlertDialog.Builder(requireContext())
            .setTitle("重置账号")
            .setMessage("将清除本地账号信息，重新注册。\n\n⚠️ 重新注册后会生成新的 userId（带正确前缀），需要重新绑定老人。")
            .setPositiveButton("重置") { _, _ ->
                CloudBaseClient.resetRegistration()
                Toast.makeText(requireContext(), "已重置，请重启App", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showAbout() {
        if (!isAdded) return
        val context = context ?: return
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) { "未知" }
        
        AlertDialog.Builder(requireContext())
            .setTitle("关于亲情守护")
            .setMessage("""
                亲情守护 v$version
                
                守护老人安全，及时接收跌倒报警通知。
                
                后端服务：腾讯云 CloudBase
                环境ID：diedaobao-cdn-d4g496tvv296f0ac2
            """.trimIndent())
            .setPositiveButton("确定", null)
            .show()
    }
    
    override fun onResume() {
        super.onResume()
        updateBindingStatus()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
