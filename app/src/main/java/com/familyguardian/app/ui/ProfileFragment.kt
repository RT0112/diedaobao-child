package com.familyguardian.app.ui

import android.content.Context
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
import com.familyguardian.app.databinding.FragmentProfileBinding
import com.familyguardian.app.LoginActivity

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return _binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val b = _binding ?: return

        // 读取用户信息
        updateUserInfo()

        // 绑定状态
        updateBindingStatus()

        // 修改个人信息
        b.btnEditProfile.setOnClickListener {
            showEditProfileDialog()
        }

        // 权限设置
        b.btnPermissionSettings.setOnClickListener {
            startActivity(Intent(requireContext(), PermissionActivity::class.java))
        }

        // 强制弹窗通知开关
        val settingsPrefs = requireContext().getSharedPreferences("family_guardian_settings", 0)
        b.switchForcePopup.isChecked = settingsPrefs.getBoolean("force_popup_notification", true)
        b.switchForcePopup.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("force_popup_notification", isChecked).apply()
            Toast.makeText(
                requireContext(),
                if (isChecked) "已开启强制弹窗通知" else "已关闭强制弹窗通知，使用普通通知",
                Toast.LENGTH_SHORT
            ).show()
        }

        // 意见反馈
        b.btnFeedback.setOnClickListener {
            startActivity(Intent(requireContext(), com.familyguardian.app.feedback.FeedbackActivity::class.java))
        }

        // 解绑老人
        b.btnUnbind.setOnClickListener { showUnbindConfirm() }

        // 退出登录
        b.btnLogout.setOnClickListener { showLogoutConfirm() }

        // 关于
        b.btnAbout.setOnClickListener { showAbout() }
    }

    private fun updateUserInfo() {
        val b = _binding ?: return
        val prefs = requireContext().getSharedPreferences("cloudbase", Context.MODE_PRIVATE)
        val username = CloudBaseClient.getUsername()
        val accountId = CloudBaseClient.getAccountId()

        b.tvUsername.text = if (username.isNotEmpty()) username else "未知用户"
        b.tvAccountId.text = if (accountId != null && accountId.isNotEmpty()) "账号ID: $accountId" else "账号ID: 未分配"
    }

    private fun updateBindingStatus() {
        val b = _binding ?: return
        val hasBound = CloudBaseClient.hasBoundElder()
        val context = context ?: return

        b.tvBindingInfo.text = if (hasBound) "已绑定老人" else "未绑定老人"
        b.tvBindingInfo.setTextColor(
            ContextCompat.getColor(
                context,
                if (hasBound) android.R.color.holo_green_dark else android.R.color.darker_gray
            )
        )
    }

    private fun showEditProfileDialog() {
        val prefs = requireContext().getSharedPreferences("cloudbase", Context.MODE_PRIVATE)
        val currentName = CloudBaseClient.getUserName()

        val nameEdit = android.widget.EditText(requireContext()).apply {
            hint = "您的称呼"
            setText(currentName)
            setSingleLine()
        }
        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(nameEdit)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("修改个人信息")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val newName = nameEdit.text.toString().trim()
                if (newName.isNotEmpty()) {
                    prefs.edit().putString("user_name", newName).apply()
                    Toast.makeText(requireContext(), "信息已更新", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "称呼不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showUnbindConfirm() {
        if (!isAdded) return
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
    }

    private fun showLogoutConfirm() {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle("退出登录")
            .setMessage("确定要退出当前账号吗？\n\n退出后需要重新登录才能使用云端功能。")
            .setPositiveButton("退出") { _, _ ->
                CloudBaseClient.logout()
                Toast.makeText(requireContext(), "已退出登录", Toast.LENGTH_SHORT).show()
                startActivity(Intent(requireContext(), LoginActivity::class.java))
                requireActivity().finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAbout() {
        if (!isAdded) return
        val context = context ?: return
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "未知"
        }

        AlertDialog.Builder(requireContext())
            .setTitle("关于亲情守护")
            .setMessage(
                "亲情守护 v$version\n\n" +
                "守护老人安全，及时接收跌倒报警通知。\n\n" +
                "👨‍👩‍👧 绑定老人设备\n" +
                "🛡️ 实时跌倒报警\n" +
                "📍 位置追踪\n" +
                "🔲 电子围栏\n" +
                "🖥️ 远程协助\n\n" +
                "© 2026 跌倒宝团队"
            )
            .setPositiveButton("确定", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        updateUserInfo()
        updateBindingStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
