package com.familyguardian.app.ui

import android.os.Bundle
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.familyguardian.app.cloud.CloudBaseClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 地图Activity v2.0 — 三种模式
 * - view: 只读查看老人位置+所有围栏
 * - add: 添加围栏（拖拽画圆+保存）
 * - view_fence: 查看单个围栏详情
 * 
 * 使用 Leaflet + 高德瓦片地图（免费无需API Key）
 */
class MapActivity : AppCompatActivity() {
    private val TAG = "MapActivity"
    private lateinit var webView: WebView
    private lateinit var rootLayout: LinearLayout
    private var currentMode = "view"
    
    // 添加模式的当前状态
    private var addFenceLat = 0.0
    private var addFenceLng = 0.0
    private var addFenceRadius = 200
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        currentMode = intent.getStringExtra("mode") ?: "view"
        
        webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            setGeolocationEnabled(true)
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                when (currentMode) {
                    "add" -> setupAddMode()
                    "view_fence" -> setupViewFenceMode()
                    else -> loadElderData()
                }
            }
        }
        
        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }
        }
        
        setContentView(webView)
        
        // 为添加模式创建原生保存面板（替代 WebView 内的 JS 按钮）
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        
        // WebView 占满剩余空间
        webView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        
        // 原生保存面板（添加模式时显示）
        val savePanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(16, 12, 16, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
            tag = "save_panel"
            
            // 围栏名称输入
            addView(EditText(this@MapActivity).apply {
                hint = "围栏名称"
                textSize = 16f
                tag = "fence_name"
                setSingleLine()
                setPadding(12, 10, 12, 10)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            
            // 保存按钮
            addView(android.widget.Button(this@MapActivity).apply {
                text = "💾 保存"
                textSize = 16f
                tag = "save_btn"
                setBackgroundColor(0xFF1976D2.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(24, 10, 24, 10)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = 12 }
                setOnClickListener { onNativeSaveClick() }
            })
        }
        
        rootLayout.addView(webView)
        rootLayout.addView(savePanel)
        
        setContentView(rootLayout)
        
        when (currentMode) {
            "add" -> supportActionBar?.title = "➕ 添加围栏"
            "view_fence" -> supportActionBar?.title = "📍 ${intent.getStringExtra("fenceName") ?: "围栏"}"
            else -> supportActionBar?.title = "📍 老人位置"
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        loadMapHtml()
    }
    
    private fun loadMapHtml() {
        val html = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>地图</title>
<style>
  * { margin: 0; padding: 0; }
  html, body, #container { width: 100%; height: 100%; font-size: 16px; }
  
  /* 信息面板 */
  #info-panel {
    position: absolute;
    bottom: 0;
    left: 0;
    right: 0;
    background: white;
    border-radius: 16px 16px 0 0;
    padding: 16px 20px;
    box-shadow: 0 -2px 12px rgba(0,0,0,0.1);
    z-index: 999;
  }
  #info-panel h3 { font-size: 18px; color: #333; margin-bottom: 8px; }
  #info-panel p { font-size: 14px; color: #666; margin: 3px 0; }
  
  /* 添加模式面板 */
  #add-panel {
    position: absolute;
    bottom: 0;
    left: 0;
    right: 0;
    background: white;
    border-radius: 16px 16px 0 0;
    padding: 16px 20px;
    box-shadow: 0 -2px 12px rgba(0,0,0,0.1);
    z-index: 999;
    display: none;
  }
  #add-panel label { font-size: 14px; color: #555; display: block; margin-top: 10px; }
  #add-panel input {
    width: 100%;
    padding: 10px 12px;
    font-size: 16px;
    border: 1px solid #ddd;
    border-radius: 8px;
    margin-top: 4px;
    box-sizing: border-box;
  }
  #add-panel .hint { font-size: 12px; color: #999; margin-top: 2px; }
  #add-panel .save-btn {
    width: 100%;
    padding: 14px;
    font-size: 18px;
    color: white;
    background: #1976D2;
    border: none;
    border-radius: 10px;
    margin-top: 14px;
    cursor: pointer;
  }
  #add-panel .save-btn:active { background: #1565C0; }
  
  .fence-info { margin-top: 8px; padding-top: 8px; border-top: 1px solid #eee; font-size: 13px; }
  .fence-item { margin: 4px 0; display: flex; align-items: center; }
  .fence-dot { width: 10px; height: 10px; border-radius: 50%; margin-right: 8px; }
  .fence-safe { background: #4CAF50; }
  .fence-alert { background: #F44336; }
  
  #loading {
    position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%);
    font-size: 16px; color: #999; z-index: 1000;
  }
  
  /* 圆心标记样式 */
  .center-marker {
    width: 24px; height: 24px;
    background: #1976D2;
    border: 3px solid white;
    border-radius: 50%;
    box-shadow: 0 2px 6px rgba(0,0,0,0.3);
    cursor: grab;
  }
  .center-marker:active { cursor: grabbing; }
  
  /* 边缘调整标记 */
  .edge-marker {
    width: 16px; height: 16px;
    background: #FF9800;
    border: 2px solid white;
    border-radius: 50%;
    box-shadow: 0 2px 4px rgba(0,0,0,0.3);
    cursor: ew-resize;
  }
</style>
</head>
<body>
<div id="container"></div>
<div id="loading">加载地图中...</div>

<!-- 查看模式信息面板 -->
<div id="info-panel" style="display:none;">
  <h3 id="elder-title">老人位置</h3>
  <p id="location-time">定位时间：加载中...</p>
  <p id="location-coord">坐标：--</p>
  <div class="fence-info" id="fence-info"></div>
</div>

<!-- 添加模式面板 -->
<div id="add-panel">
  <h3>📍 添加电子围栏</h3>
  <p style="color:#666;font-size:13px;margin-top:4px;">拖拽蓝色圆心移动位置，拖拽橙色圆点调整大小</p>
  <label>围栏名称</label>
  <input type="text" id="fence-name" placeholder="如：家、公园、社区" maxlength="20" />
  <div class="hint">留空则自动命名</div>
  <label>半径（米）</label>
  <input type="number" id="fence-radius" value="200" min="50" max="2000" />
  <div class="hint">范围：50 - 2000 米</div>
  <button class="save-btn" onclick="saveFence()">💾 保存围栏</button>
</div>

<!-- Leaflet.js -->
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>

<script>
var map = null;
var elderMarker = null;
var fenceCircles = [];
var fenceData = [];

// === 添加模式变量 ===
var editCircle = null;
var centerMarker = null;
var edgeMarker = null;
var currentCenter = null;
var currentRadius = 200;

function initMap(lat, lng) {
  if (map) return;
  
  map = L.map('container').setView([lat, lng], 15);
  
  // 高德瓦片（国内更快）
  L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}', {
    subdomains: ['1', '2', '3', '4'],
    maxZoom: 18,
    attribution: '&copy; 高德地图'
  }).addTo(map);
  
  document.getElementById('loading').style.display = 'none';
}

// === 查看模式：设置老人位置 ===
function setElderLocation(lat, lng, name, time) {
  if (!map) initMap(lat, lng);
  
  if (elderMarker) map.removeLayer(elderMarker);
  
  var icon = L.divIcon({
    className: 'elder-marker',
    html: '<div style="width:20px;height:20px;background:#F44336;border:3px solid white;border-radius:50%;box-shadow:0 2px 6px rgba(0,0,0,0.3);"></div>',
    iconSize: [20, 20],
    iconAnchor: [10, 10]
  });
  
  elderMarker = L.marker([lat, lng], {icon: icon}).addTo(map);
  elderMarker.bindPopup('<b>' + (name || '老人') + '</b><br>位置: ' + lat.toFixed(6) + ', ' + lng.toFixed(6) + '<br>时间: ' + (time || '未知'));
  
  map.setView([lat, lng], 16);
  
  document.getElementById('info-panel').style.display = 'block';
  document.getElementById('elder-title').textContent = (name || '老人') + ' 的位置';
  document.getElementById('location-time').textContent = '定位时间：' + (time || '未知');
  document.getElementById('location-coord').textContent = '坐标：' + lat.toFixed(6) + ', ' + lng.toFixed(6);
}

// === 查看模式：添加围栏圆形 ===
function addFence(fenceId, name, lat, lng, radius, isActive) {
  if (!map) initMap(lat, lng);
  
  var color = isActive ? '#F44336' : '#4CAF50';
  var circle = L.circle([lat, lng], {
    radius: radius,
    color: color,
    fillColor: color,
    fillOpacity: 0.1,
    weight: 2,
    dashArray: isActive ? '5, 5' : null
  }).addTo(map);
  
  circle.bindPopup('<b>' + name + '</b><br>半径: ' + radius + '米' + (isActive ? '<br>⚠️ 已触发' : ''));
  fenceCircles.push(circle);
  
  fenceData.push({id: fenceId, name: name, radius: radius, isActive: isActive});
  updateFenceInfo();
}

function updateFenceInfo() {
  var html = '';
  if (fenceData.length > 0) {
    html = '<b>电子围栏 (' + fenceData.length + ')</b>';
    fenceData.forEach(function(f) {
      html += '<div class="fence-item">';
      html += '<span class="fence-dot ' + (f.isActive ? 'fence-alert' : 'fence-safe') + '"></span>';
      html += f.name + ' (' + f.radius + '米)';
      if (f.isActive) html += ' ⚠️ 已触发';
      html += '</div>';
    });
  } else {
    html = '<span style="color:#999">暂未设置电子围栏</span>';
  }
  document.getElementById('fence-info').innerHTML = html;
}

function clearFences() {
  fenceCircles.forEach(function(c) { map.removeLayer(c); });
  fenceCircles = [];
  fenceData = [];
}

// === 添加模式：初始化可编辑围栏 ===
function setupEditableFence(lat, lng, radius) {
  if (!map) initMap(lat, lng);
  
  currentCenter = L.latLng(lat, lng);
  currentRadius = radius || 200;
  
  // 清理旧的
  if (editCircle) map.removeLayer(editCircle);
  if (centerMarker) map.removeLayer(centerMarker);
  if (edgeMarker) map.removeLayer(edgeMarker);
  
  // 画可编辑圆形
  editCircle = L.circle(currentCenter, {
    radius: currentRadius,
    color: '#1976D2',
    fillColor: '#1976D2',
    fillOpacity: 0.08,
    weight: 2
  }).addTo(map);
  
  // 圆心标记（可拖拽）
  var centerIcon = L.divIcon({
    className: 'center-marker-wrap',
    html: '<div class="center-marker"></div>',
    iconSize: [24, 24],
    iconAnchor: [12, 12]
  });
  
  centerMarker = L.marker(currentCenter, {
    icon: centerIcon,
    draggable: true,
    zIndexOffset: 1000
  }).addTo(map);
  
  // 圆心拖拽 → 重画圆
  centerMarker.on('drag', function(e) {
    currentCenter = e.target.getLatLng();
    editCircle.setLatLng(currentCenter);
    updateEdgeMarker();
  });
  
  centerMarker.on('dragend', function(e) {
    currentCenter = e.target.getLatLng();
    editCircle.setLatLng(currentCenter);
    updateEdgeMarker();
  });
  
  // 边缘调整标记（可拖拽调整半径）
  var edgePos = getEdgePosition();
  var edgeIcon = L.divIcon({
    className: 'edge-marker-wrap',
    html: '<div class="edge-marker"></div>',
    iconSize: [16, 16],
    iconAnchor: [8, 8]
  });
  
  edgeMarker = L.marker(edgePos, {
    icon: edgeIcon,
    draggable: true,
    zIndexOffset: 999
  }).addTo(map);
  
  // 边缘拖拽 → 调整半径
  edgeMarker.on('drag', function(e) {
    var dist = currentCenter.distanceTo(e.target.getLatLng());
    currentRadius = Math.round(Math.max(50, Math.min(2000, dist)));
    editCircle.setRadius(currentRadius);
    document.getElementById('fence-radius').value = currentRadius;
  });
  
  edgeMarker.on('dragend', function(e) {
    // 修正边缘标记位置到圆形边缘
    updateEdgeMarker();
  });
  
  // 地图居中到圆心
  map.setView(currentCenter, 15);
  
  // 显示添加面板
  document.getElementById('add-panel').style.display = 'block';
  document.getElementById('fence-radius').value = currentRadius;
  
  // 监听半径输入框
  document.getElementById('fence-radius').addEventListener('input', function(e) {
    var val = parseInt(e.target.value) || 200;
    val = Math.max(50, Math.min(2000, val));
    currentRadius = val;
    editCircle.setRadius(currentRadius);
    updateEdgeMarker();
  });
}

function getEdgePosition() {
  if (!currentCenter) return [0, 0];
  // 边缘标记放在圆心正东方向 radius 米处
  var point = map.latLngToContainerPoint(currentCenter);
  var edgePoint = L.point(point.x + currentRadius * getMetersPerPixel(), point.y);
  return map.containerPointToLatLng(edgePoint);
}

function getMetersPerPixel() {
  // 近似计算：每像素多少米
  var center = map.getCenter();
  var zoom = map.getZoom();
  // 高德瓦片的分辨率公式
  return 156543.03392 * Math.cos(center.lat * Math.PI / 180) / Math.pow(2, zoom);
}

function updateEdgeMarker() {
  if (!edgeMarker || !currentCenter) return;
  var edgePos = getEdgePosition();
  edgeMarker.setLatLng(edgePos);
}

// === 保存围栏（由 Android 调用） ===
function saveFence() {
  if (!currentCenter) return;
  
  var name = document.getElementById('fence-name').value.trim();
  var radius = parseInt(document.getElementById('fence-radius').value) || 200;
  radius = Math.max(50, Math.min(2000, radius));
  
  // 回调 Android
  if (window.Android) {
    window.Android.onSaveFence(
      name || '',
      currentCenter.lat,
      currentCenter.lng,
      radius
    );
  }
}

// === 查看单个围栏 ===
function showSingleFence(name, lat, lng, radius, isActive) {
  if (!map) initMap(lat, lng);
  
  var color = isActive ? '#F44336' : '#4CAF50';
  var circle = L.circle([lat, lng], {
    radius: radius,
    color: color,
    fillColor: color,
    fillOpacity: 0.12,
    weight: 2,
    dashArray: isActive ? '5, 5' : null
  }).addTo(map);
  
  circle.bindPopup('<b>' + name + '</b><br>半径: ' + radius + '米');
  
  // 围栏中心标记
  var icon = L.divIcon({
    className: 'fence-center',
    html: '<div style="width:14px;height:14px;background:' + color + ';border:2px solid white;border-radius:50%;box-shadow:0 2px 4px rgba(0,0,0,0.3);"></div>',
    iconSize: [14, 14],
    iconAnchor: [7, 7]
  });
  L.marker([lat, lng], {icon: icon}).addTo(map);
  
  map.setView([lat, lng], 15);
  
  // 信息面板
  document.getElementById('info-panel').style.display = 'block';
  document.getElementById('elder-title').textContent = name;
  document.getElementById('location-coord').textContent = '坐标：' + lat.toFixed(6) + ', ' + lng.toFixed(6) + ' | 半径：' + radius + '米';
  document.getElementById('location-time').textContent = isActive ? '⚠️ 老人已越界' : '✅ 老人在围栏内';
  
  // 尝试加载老人位置
  loadElderMarker();
}

function loadElderMarker() {
  // 由 Android 在查看模式下调用
}

// Android 调用接口
window.AndroidBridge = {
  setElderLocation: setElderLocation,
  addFence: addFence,
  clearFences: clearFences,
  setupEditableFence: setupEditableFence,
  showSingleFence: showSingleFence
};
</script>
</body>
</html>
        """.trimIndent()
        
        webView.loadDataWithBaseURL("https://localhost", html, "text/html", "UTF-8", null)
    }
    
    // === 查看模式：加载老人数据 ===
    private fun loadElderData() {
        if (!CloudBaseClient.hasBoundElder()) {
            Toast.makeText(this, "请先绑定老人设备", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            try {
                val status = CloudBaseClient.getElderStatus()
                if (status != null && status.lastLocation != null) {
                    val loc = status.lastLocation
                    val name = status.name.ifEmpty { "老人" }
                    val time = formatTime(loc.timestamp)
                    
                    webView.evaluateJavascript(
                        "if(window.AndroidBridge) AndroidBridge.setElderLocation(${loc.latitude}, ${loc.longitude}, '${escapeJs(name)}', '${escapeJs(time)}');",
                        null
                    )
                } else {
                    // 无位置时：只提示，不在默认位置画标记（避免误导）
                    webView.evaluateJavascript(
                        "if(window.AndroidBridge) { " +
                        "initMap(39.9042, 116.4074); " +
                        "document.getElementById('loading').textContent = '📍 暂无位置数据，请确保老人端已开启守护并允许定位权限'; " +
                        "document.getElementById('loading').style.display = 'block'; " +
                        "}",
                        null
                    )
                }
                
                // 加载围栏
                val fences = CloudBaseClient.getGeofences()
                if (fences.isNotEmpty()) {
                    webView.evaluateJavascript("if(window.AndroidBridge) AndroidBridge.clearFences();", null)
                    for (fence in fences) {
                        webView.evaluateJavascript(
                            "if(window.AndroidBridge) AndroidBridge.addFence('${escapeJs(fence.id)}', '${escapeJs(fence.name)}', ${fence.latitude}, ${fence.longitude}, ${fence.radius}, ${fence.isBreached});",
                            null
                        )
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@MapActivity, "加载失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // === 添加模式 ===
    private fun setupAddMode() {
        if (!CloudBaseClient.hasBoundElder()) {
            Toast.makeText(this, "请先绑定老人设备", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 注册 JS 接口
        webView.addJavascriptInterface(FenceSaveCallback(), "Android")
        
        lifecycleScope.launch {
            try {
                // 尝试用老人当前位置作为默认圆心
                val status = CloudBaseClient.getElderStatus()
                val loc = status?.lastLocation
                val lat = loc?.latitude ?: 39.9042
                val lng = loc?.longitude ?: 116.4074
                
                if (loc == null) {
                    Toast.makeText(this@MapActivity, "暂无老人位置，请在地图上拖拽调整", Toast.LENGTH_LONG).show()
                }
                
                // 生成默认名称
                val fences = CloudBaseClient.getGeofences()
                val defaultName = "围栏${fences.size + 1}"
                
                webView.evaluateJavascript(
                    "if(window.AndroidBridge) AndroidBridge.setupEditableFence($lat, $lng, 200);",
                    null
                )
                
                // 设置默认名称
                webView.evaluateJavascript(
                    "document.getElementById('fence-name').value = '${escapeJs(defaultName)}';",
                    null
                )
                
                // ★ 显示原生保存面板
                showAddModePanel()
                
                // 定时同步 JS 中的坐标到 Kotlin 变量（每500ms）
                scheduleJsSync()
                
            } catch (e: Exception) {
                Toast.makeText(this@MapActivity, "加载失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 显示添加模式的原生保存面板
     */
    private fun showAddModePanel() {
        val panel = rootLayout.findViewWithTag<View>("save_panel")
        if (panel != null) panel.visibility = View.VISIBLE
    }
    
    /**
     * 定时从 JS 同步坐标到 Kotlin（用于原生保存按钮）
     */
    private fun scheduleJsSync() {
        lifecycleScope.launch {
            while (true) {
                delay(500)
                // 读取 JS 中的 currentCenter 和 currentRadius
                webView.evaluateJavascript(
                    """
                    if (window.AndroidBridge && typeof currentCenter !== 'undefined' && currentCenter) {
                        window.AndroidBridge.syncFenceValues(
                            currentCenter.lat,
                            currentCenter.lng,
                            currentRadius
                        );
                    }
                    """.trimIndent(),
                    null
                )
            }
        }
    }
    
    /**
     * 原生保存按钮点击
     */
    private fun onNativeSaveClick() {
        val nameInput = rootLayout.findViewWithTag<EditText>("fence_name")
        val fenceName = nameInput?.text?.toString()?.trim()?.ifEmpty { "围栏" } ?: "围栏"
        
        val lat = addFenceLat
        val lng = addFenceLng
        val radius = addFenceRadius
        
        if (lat == 0.0 && lng == 0.0) {
            Toast.makeText(this, "请先在地图上选择围栏位置", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            Toast.makeText(this, "坐标无效，请在地图上选择位置", Toast.LENGTH_SHORT).show()
            return
        }
        
        Toast.makeText(this, "正在保存...", Toast.LENGTH_SHORT).show()
        
        lifecycleScope.launch {
            val errorMsg = CloudBaseClient.addGeofence(fenceName, lat, lng, radius.coerceIn(50, 2000))
            if (errorMsg.isEmpty()) {
                Toast.makeText(this@MapActivity, "围栏「$fenceName」已添加 ✅", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                val hint = when {
                    errorMsg.contains("elderId", ignoreCase = true) || errorMsg.contains("绑定", ignoreCase = true) -> "请先在首页绑定老人设备"
                    errorMsg.contains("权限", ignoreCase = true) || errorMsg.contains("auth", ignoreCase = true) -> "无权限执行此操作"
                    errorMsg.contains("creatorId", ignoreCase = true) -> "请重新登录后重试"
                    else -> errorMsg
                }
                Toast.makeText(this@MapActivity, "添加失败：$hint", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    // === 查看单个围栏模式 ===
    private fun setupViewFenceMode() {
        val name = intent.getStringExtra("fenceName") ?: "围栏"
        val lat = intent.getDoubleExtra("fenceLat", 39.9042)
        val lng = intent.getDoubleExtra("fenceLng", 116.4074)
        val radius = intent.getIntExtra("fenceRadius", 200)
        
        // 注册 JS 接口（查看模式也需要）
        webView.addJavascriptInterface(FenceSaveCallback(), "Android")
        
        webView.evaluateJavascript(
            "if(window.AndroidBridge) AndroidBridge.showSingleFence('${escapeJs(name)}', $lat, $lng, $radius, false);",
            null
        )
        
        // 同时加载老人位置
        lifecycleScope.launch {
            try {
                val status = CloudBaseClient.getElderStatus()
                if (status != null && status.lastLocation != null) {
                    val loc = status.lastLocation
                    val elderName = status.name.ifEmpty { "老人" }
                    webView.evaluateJavascript(
                        "if(window.AndroidBridge && map) { " +
                        "var icon = L.divIcon({className:'elder-marker',html:'<div style=\"width:20px;height:20px;background:#F44336;border:3px solid white;border-radius:50%;box-shadow:0 2px 6px rgba(0,0,0,0.3);\"></div>',iconSize:[20,20],iconAnchor:[10,10]});" +
                        "L.marker([${loc.latitude},${loc.longitude}],{icon:icon}).addTo(map).bindPopup('${escapeJs(elderName)}');" +
                        "}",
                        null
                    )
                }
            } catch (_: Exception) { }
        }
    }
    
    // === JS 回调：保存围栏 ===
    inner class FenceSaveCallback {
        private var lastCallMs = 0L
        
        // JS 同步来的坐标值（由 scheduleJsSync 调用）
        @android.webkit.JavascriptInterface
        fun syncFenceValues(lat: Double, lng: Double, radius: Int) {
            addFenceLat = lat
            addFenceLng = lng
            addFenceRadius = radius
        }
        
        @android.webkit.JavascriptInterface
        fun onSaveFence(name: String, lat: Double, lng: Double, radius: Int) {
            runOnUiThread {
                // 防抖：1秒内只响应一次
                val now = System.currentTimeMillis()
                if (now - lastCallMs < 1000) {
                    Log.d(TAG, "onSaveFence: ignored (debounce)")
                    return@runOnUiThread
                }
                lastCallMs = now
                
                val fenceName = name.trim().ifEmpty { "围栏" }
                val fenceRadius = radius.coerceIn(50, 2000)
                
                // 坐标有效性检查
                if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                    Toast.makeText(this@MapActivity, "坐标无效，请在地图上选择位置", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                
                // 立即显示加载状态
                Toast.makeText(this@MapActivity, "正在保存...", Toast.LENGTH_SHORT).show()
                
                // 调用云函数保存
                lifecycleScope.launch {
                    val errorMsg = CloudBaseClient.addGeofence(fenceName, lat, lng, fenceRadius)
                    if (errorMsg.isEmpty()) {
                        Toast.makeText(this@MapActivity, "围栏「$fenceName」已添加 ✅", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        // 详细错误信息反馈给用户
                        val hint = when {
                            errorMsg.contains("elderId", ignoreCase = true) || errorMsg.contains("绑定", ignoreCase = true) -> "请先在首页绑定老人设备"
                            errorMsg.contains("权限", ignoreCase = true) || errorMsg.contains("auth", ignoreCase = true) -> "无权限执行此操作"
                            else -> errorMsg
                        }
                        Toast.makeText(this@MapActivity, "添加失败：$hint", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
    
    private fun escapeJs(s: String): String {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
    }
    
    private fun formatTime(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
    
    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
