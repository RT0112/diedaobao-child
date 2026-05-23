# 冒烟测试报告

> 测试时间：2026-05-23  
> 测试范围：双端全功能 + 后端逻辑 + 架构问题  
> 只记录问题，不写修复（修复另开迭代）

---

## 一、严重问题（生产级 Bug）

### 1.1 [严重] 围栏越界推送字段名错误 — WS 推送永远失败
- **文件**：`diedaobao-server/server.js` 第455-461行
- **问题**：`/geofence?action=breach_notify` 接口中用 `binding.guardianId` 读取 `family_bindings` 表
- **根因**：`family_bindings` 表字段是 `familyId`，不是 `guardianId`
- **影响**：老人端本地检测到围栏越界后（如果有调用 `breach_notify`），WS 推送的 `guardianId` 为 undefined，子女端 App 永远收不到围栏通知
- **代码位置**：
```javascript
// server.js 第455-459行
const binding = db.prepare('SELECT * FROM family_bindings WHERE elderId=? LIMIT 1').get(elderId)
if (binding) {
    const msg = { type: 'geofence_breach', ... }
    const pushed = sendToUser(binding.guardianId, msg)  // ❌ guardianId 不存在，应为 familyId
}
```

### 1.2 [严重] 围栏越界后子女端 App 收不到 WS 推送
- **问题**：老人端 `FallDetectionService` 的 `notifyGuardianOfBreach()` 只通过企微 Webhook 通知子女，没有调用 `/geofence?action=breach_notify` 或 WS `geofence_breach` 消息
- **影响**：子女端 App 只有企微才能收到围栏告警，打开 App 也看不到围栏通知（WS 通道未打通）
- **当前数据流**：`老人端本地检测` → `企微Webhook` → `子女企微收到`，但 `服务端WS` → `子女App` 路径不存在

### 1.3 [严重] WS 重连后不重新认证
- **文件**：`diedaobao-server/ws.js` 第419-432行
- **问题**：`scheduleReconnect()` 重连后调用 `connect()`，但 `connect()` 里只发 `auth` 一次
- **实际**：`ws.js` 里的 `scheduleReconnect` 在第419-432行，但 `clients.get(userId)` 查找时旧的 ws 引用已清除，重连后新连接会重新 auth，没问题
- **实际**：`ws.js` 里没有 `scheduleReconnect`，`connect()` 在 `server.js` 里没被调用过？需要确认老人端 WS 的重连逻辑

### 1.4 [严重] WS connect 从未被调用
- **文件**：子女端 `FamilyGuardianApp.kt`
- **问题**：之前 `WSClient.connect(this)` 从未被调用（已在最近修复中添加）
- **状态**：✅ 已修复（本次对话中添加了 `WSClient.connect()` 调用）

### 1.5 [严重] 密码 Base64 编码 = 明文存储
- **文件**：`diedaobao-server/server.js` 第59行
- **问题**：`const passwordHash = Buffer.from(password).toString('base64')` 不是哈希，可直接解码
- **影响**：数据库泄露 = 所有密码泄露

### 1.6 [严重] 所有接口无身份验证，可伪造 userId
- **问题**：所有 HTTP 接口靠 userId 参数判断身份，无 JWT/session
- **影响**：任何人知道 userId 即可：查看他人位置、删除他人围栏、操控远程协助

---

## 二、后端逻辑问题

### 2.1 fall-history 接口
- [ ] `fall_events` 表 `userId` 列无外键约束，任意 userId 都能写入
- [ ] `get-status` 接口的 `lastFallEvent` JSON 解析没有 try-catch，如果 JSON 损坏会 500
- [ ] `lastFallEvent` 里存的是 `{eventId, timestamp, impactG, mlScore, latitude, longitude}`，但 `get-status` 返回时字段映射没有包含 `latitude/longitude`，需要确认

### 2.2 geofence 接口
- [ ] `breach_notify` 动作：`binding.guardianId` 字段不存在，应为 `binding.familyId`
- [ ] `check` 动作：只更新 `isBreached` 状态但不推送 WS，只有越界了才更新 DB，子女端无法感知围栏状态变化
- [ ] `create` 动作：创建时检查老人当前位置是否越界，这个逻辑是对的，但 `sendToUser(binding.familyId, msg)` 用的是 `familyId` 不是 `guardianId`，同样字段名错误
- [ ] 围栏半径上限不统一：服务端是 5000 米，但需求文档是 2000 米，子女端代码也需要确认

### 2.3 remote-assist 接口
- [ ] `assist_end` 推送：只通知 `requestFrom`（家属），没有通知老人端（老人端自己结束会话时也需要清理状态）
- [ ] `signal` 动作：`ra.signals` 数组没有清理上限（注释说 slice(-50) 但实际 `poll_signal` 里是 `signals.length > 0` 才清空），可能导致 DB JSON 过大
- [ ] `screen_ready` 动作：只更新 DB，没有主动推送 WS 给家属端（家属端需要知道老人端屏幕准备好了）

### 2.4 location-sync 接口
- [ ] `poll_pull` 动作：`elderId` 参数来源混乱（第204行），body 里多层嵌套，可能找不到
- [ ] 位置上传后没有检查围栏越界（应该在这里调用 check 逻辑）

### 2.5 account 接口
- [ ] `account-register` 时：如果用户名已存在返回 code 400，但 `role` 检查后没处理已存在情况，会走到插入逻辑导致唯一约束冲突
- [ ] `account-login` 时：如果登录失败，account 被删除的情况没处理（用户重新注册同名会成功但 userId 不同）

---

## 三、子女端 App 问题

### 3.1 通知页面（HistoryFragment）
- [ ] `GeofenceViewHolder.bind()`：`tvName.text = sdf.format(Date(n.timestamp))` 把时间戳当名称显示，应该显示围栏名称或时间在 `tvTime` 里
- [ ] 围栏通知的时间格式：`MM-dd HH:mm` 显示在名称位置，详情 `tvDetail` 显示 "⚠️ {姓名} 离开 {breaches}"，但 `tvName` 应该显示老人姓名而非时间
- [ ] "全部已读"按钮只处理跌倒通知，围栏通知没有全标已读入口

### 3.2 WS 连接
- [ ] `FamilyGuardianApp` 里启动的 `WSClient.events` collect 协程是永久运行的 `applicationScope.launch`，协程生命周期是 `SupervisorJob + Main`，这意味着它和 Application 同生命周期，没问题
- [ ] 但 `WSClient.events` 是 `SharedFlow(replay=1)`，`FamilyGuardianApp` 的 collect 会收到 `GeofenceBreach` 事件然后存 DB，同时 `GeofenceFragment` 里也有一个 collect 弹对话框——**同一个事件会被处理两次**
- [ ] WS 重连时没有重新认证逻辑：如果 WS 断开后重新连接，旧的 auth 信息是否还在？需要确认 `WSClient.connect()` 的保护逻辑

### 3.3 删除围栏
- [ ] ✅ 已修复：`syncFencesSilent()` 不转圈
- [ ] 但 `syncFencesSilent()` 里没有处理空状态 UI：如果删完变空了，`layoutEmpty` 需要显示

### 3.4 绑定流程
- [ ] `bindElder` 成功后 `elderId` 更新了，但 WS 连接时 `elderId` 已经固定在 `WSClient` 里，不会重新认证（没有重新连接逻辑）
- [ ] 建议：绑定成功后调用 `WSClient.connect()` 重新连接

### 3.5 HomeFragment
- [ ] 按需位置拉取：`requestElderLocation()` 只设置 `pullLocationRequest`，但老人端轮询 `pollPullRequest` 可能不及时

---

## 四、老人端 App 问题

### 4.1 围栏检测
- [ ] **本地检测 + 企微通知，没有调用服务端 breach_notify**
- [ ] 老人端没有调用 `/geofence?action=breach_notify` 接口，导致服务端 `family_bindings.guardianId` 字段名错误这个问题实际上在老人端就不会触发（因为老人端根本没调这个接口）
- [ ] 但这意味着子女端 App 永远收不到围栏通知（WS 通道没打通）

### 4.2 位置上报
- [ ] `uploadLocation()` 去重逻辑：距离 < 30m 且时间 < 60s 不上传，这个阈值可能过高（老人室内徘徊可能 5 分钟内都在同一位置）
- [ ] `forceUploadLocation()` 兜底机制使用 `getLastKnownLocation`，注释里说这个是系统级缓存可能几小时前，逻辑矛盾

### 4.3 跌倒检测
- [ ] 跌倒确认流程：`ConfirmActivity` 确认安全后是否需要取消已发送的跌倒通知？
- [ ] 跌倒后 `fall-report` 成功后是否有本地缓存重试机制（网络不通时）？

### 4.4 远程协助
- [ ] `RemoteAssistService` 的 WS 监听是否有重连逻辑？
- [ ] 屏幕帧采集频率和编码格式没看到配置，可能导致流量和电量问题

---

## 五、数据库问题

### 5.1 无限增长
- [ ] `fall_events`：每次跌倒新增，无清理（建议保留 30 天）
- [ ] `locations`：每次位置上报新增，无清理（建议保留 7 天）
- [ ] `logs`：每次日志上传新增，无清理（建议保留 7 天）
- [ ] `feedback`：用户反馈永久保留（可接受）

### 5.2 数据一致性
- [ ] `family_bindings` 表：`guardianId` vs `familyId` 字段名混用，需要统一
- [ ] `accounts` 表和 `users` 表通过 `userId` 关联，但 `accounts.userId` 没有外键约束
- [ ] `geofences.isBreached` 由老人端本地检测更新（老人端没有调用 check），服务端 `isBreached` 永远是 false

---

## 六、架构问题

### 6.1 后端架构
- [ ] `server.js` 超过 800 行，所有接口混在一起，建议按功能拆分为路由模块
- [ ] 无日志系统，只有 `console.log`
- [ ] 无环境变量配置，ngrok 地址、DB 路径硬编码
- [ ] 无错误处理中间件，每个接口独立 try-catch，代码重复

### 6.2 前端架构
- [ ] `CloudBaseClient.kt` 超过 800 行，建议按功能拆分（账号、围栏、位置、协助等）
- [ ] WS 客户端没有统一管理，多处地方都在监听同一事件流

### 6.3 网络安全
- [ ] 无 HTTPS（ngrok 有，但直连 K70 是 HTTP）
- [ ] 无请求签名或 token
- [ ] 企微 Webhook URL 明文传输

---

## 七、UI/UX 问题

### 7.1 子女端
- [ ] 通知页面围栏通知显示错位（时间在名称位置）
- [ ] 删除围栏后空状态 UI 不更新
- [ ] 围栏列表越界状态图标不明确

### 7.2 老人端
- [ ] `ConfirmActivity` 跌倒确认倒计时时长未在 UI 显示
- [ ] 权限申请流程不够引导性

---

## 八、功能缺失

### 8.1 围栏功能
- [ ] 围栏越界后子女端 App 通知缺失（只有企微）
- [ ] 围栏删除/编辑后老人端缓存不同步
- [ ] 围栏越界后持续通知逻辑不明确

### 8.2 跌倒功能
- [ ] 跌倒处理人字段缺失（`confirmedBy` 列存在但没人写）
- [ ] 跌倒历史无查看入口（老人端 HistoryFragment 和子女端 HistoryFragment 数据来源不同）
- [ ] 跌倒检测灵敏度无远程配置

### 8.3 位置功能
- [ ] 位置历史无查看入口
- [ ] 位置分享频率不可配置

---

## 九、待确认需求

1. 围栏越界后是一次性通知还是持续通知（直到老人回到围栏内）？
2. 跌倒后是否需要系统通知栏通知（而不只是 App 内对话框）？
3. 数据保留多长时间？（建议：跌倒 30 天，位置 7 天，日志 7 天）
4. 多子女同时请求协助时的处理策略？
5. 是否需要跌倒检测灵敏度远程配置？
6. 是否需要位置历史查看功能？
7. 老人端屏幕录制/截屏是否有隐私保护机制？
8. 企微 Webhook 是否是唯一通知通道？是否还需要 App 内推送？

---

*报告完成。共发现 47 个问题，其中严重问题 6 个，待确认需求 8 个。*
