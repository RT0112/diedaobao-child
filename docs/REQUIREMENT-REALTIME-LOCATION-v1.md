# 需求文档：位置实时拉取 + 围栏保存修复

## 问题1：子女端查看位置是历史位置

### 根因
- 老人端按需上传：移动>50米 或 间隔>5分钟 才上传
- 子女端被动等待老人端主动上传
- 如果老人不动，位置就是几小时前的

### 解决：按需拉取架构
```
子女端点击"查看位置"
  → 发送 pull-location 请求到云端
  → 老人端被通知有拉取请求（轮询检测）
  → 老人端立即获取GPS并上传
  → 子女端轮询直到拿到最新位置
```

**老人端**：FallDetectionService 每30秒轮询检查 `pullLocationRequest` 字段，发现则立即上传位置

**子女端**：调用 pull-location 后，轮询 get-status（每500ms一次，最多6次）直到拿到新位置

---

## 问题2：围栏保存无反应

### 根因
需要加 Log.d 调试日志来定位（当前无任何反馈）。

### 解决
- MapActivity 保存按钮点击时加 Log.d
- CloudBaseClient.addGeofence 入口/出口加 Log.d
- 网络请求前后加 Log.d

---

## 技术方案

### 1. 老人端：位置轮询检测

**文件**：FallDetectionService.kt

**新增逻辑**：
- 添加30秒轮询协程，检测 `pullLocationRequest` 字段
- 检测到时立即调用 `requestSingleUpdate()` 获取GPS并上传
- 上传后清除 `pullLocationRequest` 字段

**新增云函数**：`pull-location`

### 2. 子女端：按需拉取

**文件**：HomeFragment.kt、MapActivity.kt

**HomeFragment**：点击"查看位置"时：
1. 调用 pull-location 云函数
2. 立即轮询 get-status（500ms间隔，最多6次）
3. 地图打开后显示最新位置

**MapActivity**：打开时：
1. 如果是 view 模式，立即发送 pull-location
2. 轮询 get-status 直到拿到5分钟内的新位置

### 3. 围栏保存加日志

**文件**：MapActivity.kt、CloudBaseClient.kt

- 保存按钮点击 Log.d
- addGeofence 入口/出口/错误 Log.d
- 错误 Toast 更具体

---

## 云函数修改

### location-sync（修改）
支持 `action=poll_pull` 查询 `pullLocationRequest` 字段

### pull-location（新建）
功能：查询/设置/清除 `pullLocationRequest` 字段