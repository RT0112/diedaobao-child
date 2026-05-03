# 远程协助功能需求文档 v1.0

> 版本：v1.0 | 日期：2026-05-03 | 状态：待确认
> 所属项目：跌倒宝 + 亲情守护（双端联动）
> 优先级：P0（Phase 1，与电子围栏、亲情守护同级）

---

## 1. 功能概述

子女通过亲情守护 App，远程查看并操作老人的手机屏幕，帮助老人解决操作问题（调整设置、下载应用、清理内存等）。老人无需自行理解复杂步骤，子女代劳。

**核心设计理念**：老人只需点一次"允许"，后面的事全交给子女。

---

## 2. 用户故事

| 角色 | 故事 | 验收标准 |
|------|------|----------|
| 子女 | 我想远程帮爸爸设置跌倒宝灵敏度 | 发起协助请求 → 爸爸点"同意" → 看到爸爸手机屏幕 → 远程点击操作 |
| 子女 | 我想远程帮爸爸安装一个新应用 | 看到爸爸手机屏幕 → 打开应用商店 → 搜索 → 安装 |
| 爸爸 | 儿子说要帮我设置，我只需点个"同意" | 手机弹出"儿子请求协助"，点"允许"后什么都不用做 |
| 爸爸 | 我不想被控制时可以随时停止 | 屏幕顶部有红色"结束协助"按钮，点一下立即断开 |

---

## 3. 交互流程

### 3.1 整体时序图

```
子女端                        云端                        老人端
   |                            |                            |
   |-- 发起协助请求 ----------->|                            |
   |                            |-- Push通知 --------------->|
   |                            |                            |-- 弹出"子女请求协助"
   |                            |                            |-- 爸爸点击 [允许]
   |                            |<-- 授权确认 --------------|
   |                            |-- WebRTC信令交换 -----------|
   |<========= WebRTC P2P 连接 =============================>|
   |                            |                            |
   |  ● 接收屏幕画面流           |                            |  ● 发送屏幕画面
   |  ● 发送触控指令             |                            |  ● 执行触控指令
   |  ● 实时语音                |                            |  ● 实时语音
   |                            |                            |
   |-- 结束协助 --------------->|---------------------------->|-- 断开连接
```

### 3.2 子女端操作流程

```
首页 → 点击 [远程协助]
  ↓
├─ 场景A：爸爸在线 → 显示"正在请求协助..."
│     ↓
│     爸爸同意 → 进入协助画面（实时屏幕+语音）
│     爸爸拒绝 → Toast "爸爸拒绝了协助请求"
│     30秒未响应 → Toast "爸爸未响应，请稍后重试"
│
└─ 场景B：爸爸离线 → Toast "爸爸当前不在线，无法发起协助"
```

### 3.3 老人端授权流程

```
收到Push通知或在App内收到请求
  ↓
全屏弹出授权对话框 ────────────────┐
│                                 │
│  🔗 远程协助请求                 │
│                                 │
│  儿子 请求协助操作您的手机        │
│                                 │
│  ┌──────────────────────┐      │
│  │     [✓ 允许]          │      │  ← 点此 → 开始屏幕共享+接收远程操作
│  └──────────────────────┘      │
│  ┌──────────────────────┐      │
│  │     [✗ 拒绝]          │      │  ← 点此 → 子女收到拒绝通知
│  └──────────────────────┘      │
│                                 │
│  ⏱️ 30秒后自动拒绝              │
└─────────────────────────────────┘
```

### 3.4 协助中画面

#### 老人端（协助中）

```
┌─────────────────────────────────┐
│  🔴 远程协助中    [结束协助]      │  ← 顶部常驻红色条，点"结束"立即断开
├─────────────────────────────────┤
│                                 │
│     【正常显示手机屏幕】          │
│                                 │
│     子女正在远程操作...          │
│                                 │
│  🔊 "爸爸，您看这个按钮..."      │  ← 子女语音从扬声器播放
│                                 │
├─────────────────────────────────┤
│  ⚠️ 敏感操作需您确认             │
│  儿子正在卸载"XX应用"            │
│  [确认] [拒绝]                   │
└─────────────────────────────────┘
```

#### 子女端（协助中）

```
┌─────────────────────────────────┐
│  ← 结束协助    🔴 协助中  ⏱️ 3:42 │
├─────────────────────────────────┤
│                                 │
│   ┌─────────────────────────┐   │
│   │  📱 爸爸的手机屏幕        │   │
│   │  （实时同步，延迟<500ms） │   │
│   │                         │   │
│   │  [跌倒宝设置页面]         │   │
│   │  ○ 灵敏度：中             │   │
│   │  ● 灵敏度：高  ← 👆       │   │
│   │  ○ 灵敏度：最高           │   │
│   └─────────────────────────┘   │
│                                 │
├─────────────────────────────────┤
│  🎤 语音通话中                   │
│  [静音] [画笔标注] [截图指导]     │
│                                 │
│  💡 提示：您的点击和画笔标注      │
│     会实时显示在爸爸屏幕上        │
└─────────────────────────────────┘
```

---

## 4. 技术方案

### 4.1 整体架构

```
┌──────────────────┐       ┌────────────┐       ┌──────────────────┐
│   子女端 App      │       │  CloudBase │       │   老人端 App      │
│  (family-guardian)│       │  (信令服务) │       │  (fall-detection)│
├──────────────────┤       ├────────────┤       ├──────────────────┤
│                  │       │            │       │                  │
│ WebRTC Client ───┼──P2P──┼──信令────────┼──P2P──┼── WebRTC Client │
│ (屏幕接收端)      │       │            │       │ (屏幕发送端)      │
│                  │       │            │       │                  │
│ 触控指令发送 ─────┼──P2P──┼────────────┼──P2P──┼── 触控指令接收    │
│                  │       │            │       │ AccessibilitySvc │
│                  │       │            │       │ (执行远程操作)    │
│                  │       │            │       │                  │
│ 语音通话 ─────────┼──P2P──┼────────────┼──P2P──┼── 语音通话        │
└──────────────────┘       └────────────┘       └──────────────────┘
```

### 4.2 核心技术选型

| 模块 | 技术方案 | 理由 |
|------|---------|------|
| 屏幕共享 | **MediaProjection + WebRTC** | Android 5.0+ 系统API，零第三方SDK费用 |
| 信令服务 | **CloudBase 云函数** | 已有基础设施，复用零成本 |
| 远程控制 | **AccessibilityService** | Android 系统级辅助功能，可模拟点击/滑动/输入 |
| 语音通话 | **WebRTC Audio Track** | 复用同一WebRTC连接，延迟最低 |
| P2P穿透 | **STUN (Google免费)** | 绝大多数网络环境可用 |

### 4.3 为什么选 MediaProjection + WebRTC（不选第三方SDK）

| 方案 | 优点 | 缺点 | 决策 |
|------|------|------|------|
| TeamViewer/AnyDesk SDK | 开箱即用 | 商业授权费、体积大、隐私风险 | ❌ |
| ADB screenrecord 推流 | 简单 | 需要USB调试、不可靠 | ❌ |
| **MediaProjection + WebRTC** | 免费、可控、隐私安全 | 开发量稍大 | ✅ |

> 参考开源项目：[scrcpy](https://github.com/Genymobile/scrcpy)（已证明 MediaProjection 方案成熟可行）

### 4.4 屏幕共享实现要点

```kotlin
// 1. 申请屏幕录制权限（MediaProjection）
val intent = mediaProjectionManager.createScreenCaptureIntent()
startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)

// 2. 创建 VirtualDisplay 捕获屏幕
val mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
val virtualDisplay = mediaProjection.createVirtualDisplay(
    "RemoteAssist",
    width, height, dpi,
    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
    surface, null, null
)

// 3. 将 Surface 的图像帧通过 WebRTC VideoTrack 发送
// → Surface → ImageReader → YUV/NV21 → WebRTC VideoEncoder → PeerConnection
```

### 4.5 远程控制实现要点

```kotlin
// AccessibilityService 接收触控指令并执行
class RemoteAssistService : AccessibilityService() {
    
    // 接收子女端传来的触控事件
    fun executeTouch(x: Float, y: Float, action: Int) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }
    
    // 执行滑动
    fun executeSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(gesture, null, null)
    }
    
    // 模拟点击（含长按）
    fun executeClick(x: Float, y: Float, duration: Long = 100L) { ... }
    
    // 模拟输入文本
    fun executeInput(text: String) { ... }
}
```

### 4.6 信令流程（CloudBase 云函数）

**新增云函数**：`remote-assist`（信令交换）

| Action | 发起方 | 说明 |
|--------|--------|------|
| `request` | 子女端 | 发起协助请求，设置 `remoteAssistRequest` 字段 |
| `poll_request` | 老人端 | 轮询是否有协助请求（每5秒） |
| `respond` | 老人端 | 同意/拒绝请求 |
| `offer` | 老人端(发起者) | WebRTC SDP Offer |
| `answer` | 子女端 | WebRTC SDP Answer |
| `ice_candidate` | 双方 | ICE Candidate 交换 |
| `end` | 任意方 | 结束协助 |

**数据结构**（存在 CloudBase users 集合中）：

```json
{
  "remoteAssist": {
    "active": false,
    "requestFrom": null,        // 请求者 userId
    "requestFromName": null,    // 请求者名称（用于显示）
    "requestTime": null,        // 请求时间戳
    "status": "idle",           // idle | requesting | active
    "sessionId": null,          // 当前会话ID
    "startedAt": null           // 协助开始时间
  }
}
```

### 4.7 同步与清除机制（子女端→老人端→远程协助服务）

```
子女端触发远程协助
  → CloudBase 写入 remoteAssist.request = {from, fromName, time, status:"requesting"}
  → 老人端定时 poll_assist 协程（5秒间隔）检测到请求
  → 老人端弹出授权对话框
  → 爸爸点[允许]
    → 清除请求
    → 启动 WebRTC + MediaProjection
    → 子女端接收画面
  → 爸爸点[拒绝] 或 30秒超时
    → 清除请求
    → 子女端收到"请求被拒绝"状态
```

**关键**：与围栏缓存刷新不同，远程协助需要**主动 push 通知老人端**。但由于没有 FCM，改用**轮询**方案：
- 子女端发起请求后，老人端轮询检测（5秒间隔）
- 最坏延迟 5 秒，可接受

---

## 5. 安全机制

### 5.1 授权层级

| 层级 | 条件 | 说明 |
|------|------|------|
| **L1: 绑定关系** | 子女已绑定该老人 | 基础前提，未绑定无法发起 |
| **L2: 老人授权** | 每次协助需老人手动点击"允许" | 防止滥用，核心安全机制 |
| **L3: 敏感操作确认** | 卸载应用、修改系统设置等 | 老人二次确认 |
| **L4: 随时断开** | 任一方可随时结束 | 老人体验可控 |

### 5.2 防滥用

- ❌ 禁止静默启动（不经授权直接控制）
- ❌ 禁止后台隐藏协助状态（老人端必有红色指示条）
- ❌ 禁止一次授权永久有效（每次需重新确认）
- ❌ 禁止密码输入框被远程控制（系统自动拦截）
- ✅ 协助全程录音录像提示（如有录屏则留存7天，可审计）

### 5.3 隐私保护

- 屏幕画面通过 WebRTC P2P 直连，不经过云端服务器
- 信令消息通过 HTTPS 加密
- 协助结束后，子女端缓存的屏幕截图自动清除
- 协助记录仅存开始/结束时间、操作统计，不存画面内容

---

## 6. 防御性编程清单

### 6.1 老人端

| 场景 | 防御策略 |
|------|---------|
| 老人未授权 AccessibilityService | 在首页/设置页引导开启，"未开启辅助功能，远程协助无法操作" |
| MediaProjection 权限被拒 | Toast提示后停止，不清除请求（允许子女重试） |
| WebRTC 连接失败（NAT穿透失败） | Toast "连接失败，请检查网络"，5秒后自动重试，最多3次 |
| 协助中网络断开 | 双方显示"连接已断开"，自动结束协助，清除请求 |
| 协助中锁屏 | 自动停止屏幕共享（隐私保护），解锁后需重新发起 |
| 协助中来电 | 暂停协助（不共享通话界面），挂断后恢复 |
| 协助中电量<5% | 强制结束协助，Toast "电量过低，已自动结束" |
| 多个子女同时发起请求 | 只处理第一个到达的请求，后续返回"其他家属正在协助" |

### 6.2 子女端

| 场景 | 防御策略 |
|------|---------|
| 老人端离线 | 返回"爸爸当前离线"，不发起请求 |
| 请求30秒未响应 | 自动超时，清除请求 |
| 收到拒绝 | Toast "爸爸拒绝了协助"，不重发请求 |
| 连接失败 | 显示具体失败原因，提供"重试"按钮 |
| 协助中自己网络断开 | 显示"连接已断开，正在重连..."，5秒后自动重试 |
| 远程操作执行失败 | 显示操作结果反馈（如"点击已发送，等待执行..."） |

### 6.3 AccessibilityService

| 场景 | 防御策略 |
|------|---------|
| 服务未启动 | 引导用户去设置→无障碍→开启 |
| 权限被系统回收 | 服务 onUnbind 时通知 App，重新引导用户开启 |
| 厂商ROM限制 | 各厂商跳转 Intent 适配（参考现有兼容兼容表） |

---

## 7. 云函数设计

### 7.1 `remote-assist` 云函数

**HTTP 路由**：`POST /remote-assist`

```json
// action=request（子女端发起）
{ "action": "request", "elderId": "xxx", "guardianId": "yyy", "guardianName": "儿子" }

// action=poll_request（老人端轮询）
{ "action": "poll_request", "userId": "xxx" }

// action=respond（老人端响应）
{ "action": "respond", "userId": "xxx", "accepted": true }

// action=end（任一方结束）
{ "action": "end", "userId": "xxx", "elderId": "yyy" }

// action=signal（信令消息转发 — 用于ICE candidate等）
{ "action": "signal", "from": "xxx", "to": "yyy", "data": {"type": "ice_candidate", ...} }
```

### 7.2 信令消息存储

ICE candidates 和 SDP 通过云函数中转，不额外建集合（信令消息有一次性特征，直接存储在 users 文档的 `remoteAssist.signals` 子字段中，每次 action=end 时清除）。

---

## 8. 厂商兼容

### 8.1 AccessibilityService 兼容

| 厂商 | 跳转 Intent | 注意事项 |
|------|------------|---------|
| 小米/红米 (MIUI) | `com.miui.securitycenter` | 需走"更多设置→无障碍" |
| 华为/荣耀 (EMUI/鸿蒙) | `com.huawei.systemmanager` | 鸿蒙可能弹"安全提醒" |
| OPPO/Realme (ColorOS) | `com.coloros.securitypermission` | 默认阻止所有辅助功能 |
| Vivo/iQOO (FunTouch) | `com.vivo.permissionmanager` | 需手动搜索"无障碍" |
| 三星 (OneUI) | 标准 Settings.ACTION_ACCESSIBILITY_SETTINGS | 较标准 |
| 魅族 (Flyme) | 标准 + "无障碍"菜单层次与原生不同 | 测试验证 |

### 8.2 MediaProjection 兼容

- Android 5.0+ 均支持，覆盖率>99%
- 部分厂商（小米/OPPO）需要 Intent.createScreenCaptureIntent() 加 FLAG_ACTIVITY_NEW_TASK
- 华为鸿蒙 3.0+ 需要在 onActivityResult 中立即创建 VirtualDisplay，延迟可能失败

### 8.3 WebRTC 兼容

- Android WebView/Chromium 内核均支持
- Google WebRTC library (io.getstream:stream-webrtc-android 或 org.webrtc:google-webrtc)
- 华为手机注意 H.264 硬编码器兼容（某些机型只支持 baseline profile）

---

## 9. 依赖与体积影响

| 依赖 | 版本 | 体积 |
|------|------|------|
| `org.webrtc:google-webrtc` | 1.0.32006 | ~8MB (仅arm64) |
| 无其他新增依赖 | - | - |

**预计 APK 增量**：跌倒宝 ~8MB，亲情守护 ~8MB（已包含 ONNX 9MB，总计增量可接受）

---

## 10. 开发计划

| 步骤 | 内容 | 预估工时 | 优先级 |
|------|------|---------|--------|
| 1 | CloudBase 云函数 `remote-assist` 开发+部署 | 0.5天 | P0 |
| 2 | 老人端 AccessibilityService 实现 | 1天 | P0 |
| 3 | 老人端 MediaProjection + WebRTC 屏幕共享 | 1.5天 | P0 |
| 4 | 子女端 WebRTC 接收 + 触控指令发送 | 1天 | P0 |
| 5 | 双端授权流程 + UI | 0.5天 | P0 |
| 6 | 各厂商辅助功能引导适配 | 0.5天 | P0 |
| 7 | 安全机制（敏感操作确认等） | 0.5天 | P1 |
| 8 | 真机联调 + 厂商兼容测试 | 1天 | P0 |
| **总计** | | **约6.5天** | |

---

## 11. 验收标准

- [ ] 子女端点击"远程协助"→ 老人端弹出授权对话框，显示请求者名称
- [ ] 老人点击"允许"→ 子女端看到老人手机实时画面（延迟不超过1秒）
- [ ] 子女在屏幕画面上点击 → 老人端对应位置执行点击操作
- [ ] 子女在屏幕上滑动 → 老人端执行滑动操作
- [ ] 双方可实时语音通话
- [ ] 任一方点击"结束"→ 协助立即终止
- [ ] 老人点击"拒绝"或30秒未响应 → 子女收到相应提示
- [ ] 老人未开启 AccessibilityService → 协助前引导开启
- [ ] 协助中网络断开 → 双方收到断开提示
- [ ] 敏感操作（卸载应用等）→ 老人端二次确认
- [ ] 10台主流厂商手机测试通过（小米、华为、OPPO、Vivo、三星、魅族、联想、中兴、荣耀、Realme）

---

## 12. 用户确认项

| 确认点 | 选项 | 默认建议 |
|--------|------|---------|
| 实现优先级 | A. 先做远程协助 / B. 先做日常提醒+健康档案 | A（远程协助技术难度高，尽早启动） |
| AccessibilityService 权限 | 需要老人在无障碍设置中手动开启 | 首次引导页 + 设置页入口 |
| 录屏留存 | 是否录制协助画面备查？ | 默认不录，可选开启（需老人同意） |
| 截图标注 | 子女端是否支持画笔标注屏幕位置？ | 是，方便指导老人 |
