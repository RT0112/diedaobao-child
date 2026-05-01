# 亲情守护 — 电子围栏功能需求文档 v2.0

> 版本：v2.0 | 日期：2026-05-01 | 状态：待确认

---

## 1. 功能概述

电子围栏允许子女为老人设置安全区域。当老人离开围栏区域时，子女收到通知。

**核心交互原则：地图优先，所见即所得。**

---

## 2. 用户故事

| 角色 | 故事 | 验收标准 |
|------|------|----------|
| 子女 | 我想在地图上画一个圈作为围栏 | 地图上拖拽圆心、拖拽边缘调整半径，实时预览 |
| 子女 | 我想给围栏起个名字 | 输入框预填默认名（如"围栏1"），可修改 |
| 子女 | 我想看看已设围栏的效果 | 围栏列表点击→地图页显示对应围栏圆形区域 |
| 子女 | 我想删除某个围栏 | 列表项左滑/长按→确认删除 |
| 子女 | 我提交空数据时不能闪退 | 空名称用默认值，空半径给默认200m，无坐标时提示 |

---

## 3. 页面流程

### 3.1 围栏列表页（GeofenceFragment）

```
┌─────────────────────────────────┐
│  🛡️ 电子围栏                      │
│  老人离开围栏区域时自动通知您         │
├─────────────────────────────────┤
│                                 │
│  ┌──────────────────────────┐   │
│  │ 📍 家          200m  ✅  │   │  ← 安全(绿)
│  │   39.1234, 116.5678      │   │
│  ├──────────────────────────┤   │
│  │ 📍 公园        500m  ⚠️  │   │  ← 已越界(红)
│  │   39.2345, 116.6789      │   │
│  └──────────────────────────┘   │
│                                 │
│  ┌──────────────────────────┐   │
│  │   ➕ 添加电子围栏           │   │  ← 底部按钮
│  └──────────────────────────┘   │
└─────────────────────────────────┘
```

**交互**：
- 点击围栏项 → 打开地图页，定位到该围栏，显示圆形区域
- 长按围栏项 → 弹出菜单：查看地图 / 删除
- 下拉刷新

### 3.2 添加围栏页（MapActivity - 新模式）

```
┌─────────────────────────────────┐
│  ← 添加围栏            [保存]    │
├─────────────────────────────────┤
│                                 │
│         ┌─────────┐             │
│         │  圆形    │             │  ← 可拖拽的圆形围栏
│         │  围栏区  │             │
│         │  域      │             │
│         └────┬────┘             │
│              │ 📍 圆心标记       │  ← 可拖拽调整位置
│                                 │
│   ─ ─ ─ 虚线圆边 ─ ─ ─         │  ← 拖拽边缘调整半径
│                                 │
├─────────────────────────────────┤
│  围栏名称：[家          ]        │  ← 底部面板
│  半径：    [200] 米              │  ← 可手动输入或拖拽
│  ⚠️ 半径范围 50-2000 米          │
└─────────────────────────────────┘
```

**交互流程**：
1. 进入地图页 → 以老人当前位置为圆心，默认半径200m画圆
2. **拖拽圆心标记** → 圆跟随移动
3. **拖拽圆边缘** → 调整半径（最小50m，最大2000m）
4. **手动输入半径** → 圆实时更新
5. **输入围栏名称** → 默认"围栏N"（N=当前数量+1）
6. 点击保存 → 提交到云端

### 3.3 查看围栏地图（MapActivity - 查看模式）

从围栏列表点击某围栏进入：
- 地图定位到该围栏中心
- 显示围栏圆形区域（安全=绿色，越界=红色虚线）
- 显示老人当前位置标记
- 底部信息面板：围栏名称、半径、状态

---

## 4. 技术方案

### 4.1 MapActivity 改造

**现有**：WebView + Leaflet + 高德瓦片，只读查看
**改造**：新增两种模式

| 模式 | intent extra | 行为 |
|------|-------------|------|
| `view` | 默认 | 只读查看老人位置+所有围栏 |
| `add` | `mode=add` | 可编辑：拖拽圆心+调整半径+保存按钮 |
| `view_fence` | `mode=view_fence&fenceId=xxx` | 查看单个围栏详情 |

### 4.2 前端交互实现

**圆心拖拽**：
- Leaflet marker dragend 事件 → 重新画圆 → 更新坐标

**半径调整**：
- 方案A（推荐）：拖拽圆形边缘的 marker（Leaflet circle + edge marker）
- 方案B：底部滑块/输入框调整半径
- 最终方案：**A+B 双通道**，拖拽圆边或手动输入均可

**保存流程**：
```kotlin
1. 获取圆心坐标（lat, lng）
2. 获取半径（radius）
3. 获取名称（name，默认"围栏N"）
4. 参数校验：
   - radius 50-2000
   - name 非空（空则用默认）
   - lat/lng 有效（-90~90, -180~180）
5. 调用 CloudBaseClient.addGeofence()
6. 成功 → Toast + finish()
7. 失败 → Toast 错误信息，留在地图页
```

### 4.3 闪退修复

**根因**：GeofenceFragment.showAddFenceDialog() 中：
- `radiusEdit.text.toString().trim().toIntOrNull() ?: 200` — 空输入→默认200，OK
- 但 `addFence()` 内部：老人无位置时 toast 后 `return@launch`，此时对话框已关闭
- **实际闪退点**：AlertDialog.Builder 使用 `android.R.layout.activity_list_item` 作为 dialog view（这行代码其实没用上，后面又自定义了 LinearLayout），但更可能的是**空名称**被传入后端或 `name.isEmpty()` 导致的问题

**防御性编程清单**：
1. 名称空→默认"围栏N"
2. 半径空/非数字→默认200
3. 半径超出范围→提示但不闪退
4. lat/lng 为空→用老人当前位置；老人也没位置→提示+return（不提交）
5. 网络失败→Toast错误，不闪退
6. 所有 try-catch 兜底

### 4.4 GeofenceFragment 交互升级

**旧方案**（废弃）：4个EditText对话框手动输坐标
**新方案**：点击"添加电子围栏"→ 打开 MapActivity(mode=add)

---

## 5. 数据结构

### 5.1 GeofenceInfo（已有，不变）

```kotlin
data class GeofenceInfo(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Int,         // 50-2000米
    val isBreached: Boolean,
    val createdAt: Long
)
```

### 5.2 云函数接口（已有，不变）

- `action=create`: `{action, elderId, creatorId, name, latitude, longitude, radius}`
- `action=list`: `{action, elderId, creatorId}`
- `action=delete`: `{action, fenceId}`
- `action=check`: `{action, userId, latitude, longitude}`

---

## 6. 防御性编程规范

所有用户输入必须遵循：

```kotlin
// ✅ 正确模式
val name = nameEdit.text.toString().trim().ifEmpty { "围栏${fences.size + 1}" }
val radius = radiusEdit.text.toString().trim().toIntOrNull()
    ?.coerceIn(50, 2000) ?: 200
val lat = latEdit.text.toString().trim().toDoubleOrNull()
val lng = lngEdit.text.toString().trim().toDoubleOrNull()

// ❌ 错误模式（会闪退）
val radius = radiusEdit.text.toString().toInt()  // NumberFormatException!
val name = nameEdit.text.toString()  // 可能为空字符串
```

---

## 7. 开发计划

| 步骤 | 内容 | 优先级 |
|------|------|--------|
| 1 | 修复闪退：空数据提交防御 | P0 |
| 2 | GeofenceFragment：添加按钮→打开地图(添加模式) | P0 |
| 3 | MapActivity：添加模式（拖拽画圆+保存） | P0 |
| 4 | MapActivity：查看单个围栏模式 | P1 |
| 5 | 围栏列表项：点击→查看地图，长按→菜单 | P1 |
| 6 | 删除拨打电话代码清理 | P2 |
| 7 | 更新 SOUL.md + AGENTS.md | P0 |

---

## 8. 验收标准

- [ ] 空数据提交不闪退，给出合理默认值
- [ ] 添加围栏：在地图上拖拽画圆，保存成功
- [ ] 查看围栏：列表点击→地图定位到围栏
- [ ] 删除围栏：确认后删除
- [ ] 围栏列表下拉刷新
- [ ] 无老人位置时提示"无法获取老人位置"，不闪退
