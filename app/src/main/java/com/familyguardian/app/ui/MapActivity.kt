package com.familyguardian.app.ui

import android.os.Bundle
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.familyguardian.app.cloud.CloudBaseClient
import kotlinx.coroutines.launch

/**
 * 地图Activity - 显示老人位置和电子围栏
 * 使用高德地图JS API（免费Web端）
 */
class MapActivity : AppCompatActivity() {
    
    private lateinit var webView: WebView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            // 允许WebView获取位置
            setGeolocationEnabled(true)
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 页面加载完后，加载老人位置和围栏数据
                loadElderData()
            }
        }
        
        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                // 允许WebView使用位置
                callback?.invoke(origin, true, false)
            }
        }
        
        setContentView(webView)
        supportActionBar?.title = "📍 老人位置"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        // 加载本地HTML地图页面
        loadMapHtml()
    }
    
    private fun loadMapHtml() {
        // 使用高德地图Web API（免费，无需key的基础功能）
        val html = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>老人位置</title>
<style>
  * { margin: 0; padding: 0; }
  html, body, #container { width: 100%; height: 100%; font-size: 16px; }
  
  /* 信息面板 */
  #info-panel {
    position: absolute;
    bottom: 20px;
    left: 10px;
    right: 10px;
    background: white;
    border-radius: 12px;
    padding: 14px;
    box-shadow: 0 2px 12px rgba(0,0,0,0.15);
    z-index: 999;
  }
  #info-panel h3 { font-size: 18px; color: #333; margin-bottom: 6px; }
  #info-panel p { font-size: 14px; color: #666; margin: 3px 0; }
  #info-panel .fence-info { 
    margin-top: 8px; padding-top: 8px; 
    border-top: 1px solid #eee; 
    font-size: 13px; 
  }
  .fence-item { margin: 4px 0; display: flex; align-items: center; }
  .fence-dot { width: 10px; height: 10px; border-radius: 50%; margin-right: 8px; }
  .fence-safe { background: #4CAF50; }
  .fence-alert { background: #F44336; }
  
  /* 围栏列表 */
  #fence-list { display: none; }
  
  /* 加载中 */
  #loading {
    position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%);
    font-size: 16px; color: #999; z-index: 1000;
  }
</style>
</head>
<body>
<div id="container"></div>
<div id="loading">加载地图中...</div>

<div id="info-panel" style="display:none;">
  <h3 id="elder-title">老人位置</h3>
  <p id="location-time">定位时间：加载中...</p>
  <p id="location-coord">坐标：--</p>
  <div class="fence-info" id="fence-info"></div>
</div>

<script type="text/javascript">
  // 高德地图 JS API 2.0（免费Web端，2.0需要key）
  // 使用 OpenStreetMap + Leaflet 作为免费替代方案
</script>

<!-- Leaflet.js + OpenStreetMap（完全免费，无需API Key） -->
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>

<script>
var map = null;
var elderMarker = null;
var fenceCircles = [];
var fenceData = [];

function initMap(lat, lng) {
  if (map) return;
  
  map = L.map('container').setView([lat, lng], 15);
  
  // 使用高德瓦片（国内更快）
  L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}', {
    subdomains: ['1', '2', '3', '4'],
    maxZoom: 18,
    attribution: '&copy; 高德地图'
  }).addTo(map);
  
  document.getElementById('loading').style.display = 'none';
}

// 设置老人位置标记
function setElderLocation(lat, lng, name, time) {
  if (!map) initMap(lat, lng);
  
  // 移除旧标记
  if (elderMarker) map.removeLayer(elderMarker);
  
  // 添加老人位置标记（红色大圆点）
  var icon = L.divIcon({
    className: 'elder-marker',
    html: '<div style="width:20px;height:20px;background:#F44336;border:3px solid white;border-radius:50%;box-shadow:0 2px 6px rgba(0,0,0,0.3);"></div>',
    iconSize: [20, 20],
    iconAnchor: [10, 10]
  });
  
  elderMarker = L.marker([lat, lng], {icon: icon}).addTo(map);
  elderMarker.bindPopup('<b>' + (name || '老人') + '</b><br>位置: ' + lat.toFixed(6) + ', ' + lng.toFixed(6) + '<br>时间: ' + (time || '未知'));
  
  map.setView([lat, lng], 16);
  
  // 更新信息面板
  document.getElementById('info-panel').style.display = 'block';
  document.getElementById('elder-title').textContent = (name || '老人') + ' 的位置';
  document.getElementById('location-time').textContent = '定位时间：' + (time || '未知');
  document.getElementById('location-coord').textContent = '坐标：' + lat.toFixed(6) + ', ' + lng.toFixed(6);
}

// 添加围栏圆形区域
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

// 更新围栏信息面板
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

// 清除所有围栏
function clearFences() {
  fenceCircles.forEach(function(c) { map.removeLayer(c); });
  fenceCircles = [];
  fenceData = [];
}

// Android调用入口
window.AndroidBridge = {
  setElderLocation: setElderLocation,
  addFence: addFence,
  clearFences: clearFences
};
</script>
</body>
</html>
        """.trimIndent()
        
        webView.loadDataWithBaseURL("https://localhost", html, "text/html", "UTF-8", null)
    }
    
    private fun loadElderData() {
        if (!CloudBaseClient.hasBoundElder()) {
            Toast.makeText(this, "请先绑定老人设备", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            // 加载老人位置
            val status = CloudBaseClient.getElderStatus()
            if (status != null && status.lastLocation != null) {
                val loc = status.lastLocation
                val name = status.name.ifEmpty { "老人" }
                val time = formatTime(loc.timestamp)
                
                webView.evaluateJavascript(
                    "if(window.AndroidBridge) AndroidBridge.setElderLocation(${loc.latitude}, ${loc.longitude}, '${name}', '${time}');",
                    null
                )
            } else {
                // 无位置数据，显示默认位置
                webView.evaluateJavascript(
                    "if(window.AndroidBridge) { initMap(39.9042, 116.4074); document.getElementById('loading').textContent='暂无位置数据，请确保老人端已开启守护'; }",
                    null
                )
            }
            
            // 加载围栏数据
            val fences = CloudBaseClient.getGeofences()
            if (fences.isNotEmpty()) {
                webView.evaluateJavascript("if(window.AndroidBridge) AndroidBridge.clearFences();", null)
                for (fence in fences) {
                    val isActive = fence.isBreached  // 是否越界
                    webView.evaluateJavascript(
                        "if(window.AndroidBridge) AndroidBridge.addFence('${fence.id}', '${fence.name}', ${fence.latitude}, ${fence.longitude}, ${fence.radius}, $isActive);",
                        null
                    )
                }
            }
        }
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
