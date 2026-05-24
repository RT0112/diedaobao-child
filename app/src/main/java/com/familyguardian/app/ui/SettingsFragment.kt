package com.familyguardian.app.ui

import android.content.Intent
import com.familyguardian.app.LoginActivity
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

        // 意见反馈
        b.btnFeedback.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), com.familyguardian.app.feedback.FeedbackActivity::class.java))
        }
        
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
        val username = CloudBaseClient.getUsername()
        
        b.tvBindingInfo.text = if (hasBound) {
            "已绑定老人（${username}）"
        } else {
            "未绑定老人（${username}）"
        }
        b.tvBindingInfo.setTextColor(
            ContextCompat.getColor(context, 
                if (hasBound) android.R.color.holo_green_dark else android.R.color.darker_gray)
        )
    }
    
    private fun showResetConfirm() {
        val hasBound = CloudBaseClient.hasBoundElder()
        if (hasBound) {
            // 已绑定老人：显示"解绑老人"确认
            AlertDialog.Builder(requireContext())
                .setTitle("解绑老人")
                .setMessage("确定要解绑当前绑定的老人设备吗？\n\n解绑后需要重新输入绑定码才能再次绑定。")
                .setPositiveButton("解绑") { _, _ ->
                    CloudBaseClient.unbindElder()
                    Toast.makeText(requireContext(), "已解绑老人设备", Toast.LENGTH_SHORT).show()
                    updateBindingStatus()
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            // 未绑定老人：显示"退出登录"确认
            AlertDialog.Builder(requireContext())
                .setTitle("退出登录")
                .setMessage("确定要退出当前账号吗？\n\n退出后需要重新登录才能使用云端功能。")
                .setPositiveButton("退出") { _, _ ->
                    CloudBaseClient.logout()
                    startActivity(Intent(requireContext(), LoginActivity::class.java))
                    requireActivity().finish()
                }
                .setNegativeButton("取消", null)
                .show()
        }
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
