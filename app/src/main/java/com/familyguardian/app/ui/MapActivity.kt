package com.familyguardian.app.ui

import com.familyguardian.app.util.AppLogger
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.familyguardian.app.cloud.CloudBaseClient
import kotlinx.coroutines.launch

/**
 * 地图Activity — 三种模式
 * - view: 查看老人位置+所有围栏
 * - add: 拖拽画圆添加围栏
 * - view_fence: 查看单个围栏详情
 */
class MapActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MapActivity"
    }

    private lateinit var webView: WebView
    private var currentMode = "view"

    /**
     * JS → Kotlin 桥接。
     * 在 loadMapHtml() 之前注册，确保页面加载完即可调用。
     */
    inner class JsBridge {
        @android.webkit.JavascriptInterface
        fun onSaveFence(name: String, lat: Double, lng: Double, radius: Int) {
            runOnUiThread {
                val fenceName = name.trim().ifEmpty { "围栏" }
                val r = radius.coerceIn(50, 2000)

                if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                    Toast.makeText(this@MapActivity, "坐标无效", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                Toast.makeText(this@MapActivity, "正在保存...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    val err = CloudBaseClient.addGeofence(fenceName, lat, lng, r)
                    if (err.isEmpty()) {
                        Toast.makeText(this@MapActivity, "围栏「$fenceName」已添加 ✅", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        Toast.makeText(this@MapActivity, "添加失败：$err", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun onUpdateFence(fenceId: String, name: String, lat: Double, lng: Double, radius: Int) {
            runOnUiThread {
                val fenceName = name.trim().ifEmpty { "围栏" }
                val r = radius.coerceIn(50, 2000)

                if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                    Toast.makeText(this@MapActivity, "坐标无效", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                Toast.makeText(this@MapActivity, "正在更新...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    val err = CloudBaseClient.updateGeofence(fenceId, fenceName, lat, lng, r)
                    if (err.isEmpty()) {
                        Toast.makeText(this@MapActivity, "围栏「$fenceName」已更新 ✅", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        Toast.makeText(this@MapActivity, "更新失败：$err", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentMode = intent.getStringExtra("mode") ?: "view"

        // ====== 1. 创建 WebView + 必须在 loadMapHtml 之前注册 JS 桥接 ======
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(JsBridge(), "Android")

            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView?, code: Int, desc: String?, url: String?) {
                    AppLogger.e(TAG, "WebView错误: code=$code, desc=$desc")
                    webView.loadDataWithBaseURL("about:blank", "<h2 style='padding:40px;text-align:center;color:#666'>地图加载失败<br><small>请检查网络连接</small></h2>", "text/html", "UTF-8", null)
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    // 页面加载完成后，根据模式加载数据
                    when (currentMode) {
                        "add" -> setupAddMode()
                        "edit" -> setupEditMode()
                        "view_fence" -> setupViewFenceMode()
                        else -> loadElderData()
                    }
                }
            }
        }

        // ====== 2. 简单 setContentView ======
        setContentView(webView)

        // ====== 3. 标题 ======
        supportActionBar?.run {
            title = when (currentMode) {
                "add" -> "➕ 添加围栏"
                "edit" -> "✏️ 编辑围栏"
                "view_fence" -> intent.getStringExtra("fenceName") ?: "围栏详情"
                else -> "📍 老人位置"
            }
            setDisplayHomeAsUpEnabled(true)
        }

        // ====== 4. 加载地图 HTML ======
        loadMapHtml()
    }

    // ========================================
    // 地图 HTML（Leaflet + 高德瓦片，GCJ-02坐标系，JS层WGS84↔GCJ02转换）
    // ========================================
    private fun loadMapHtml() {
        val html = """
<!DOCTYPE html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
*{margin:0;padding:0}
html,body,#container{width:100%;height:100%;font-size:16px}
#loading{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);font-size:16px;color:#999;z-index:1000}
#info-panel{position:absolute;bottom:0;left:0;right:0;background:white;border-radius:16px 16px 0 0;padding:16px 20px;box-shadow:0 -2px 12px rgba(0,0,0,.1);z-index:999;display:none}
#info-panel h3{font-size:18px;color:#333;margin-bottom:8px}
#info-panel p{font-size:14px;color:#666;margin:3px 0}
#fence-info{margin-top:8px;padding-top:8px;border-top:1px solid #eee;font-size:13px}
#add-panel{position:absolute;bottom:0;left:0;right:0;background:white;border-radius:16px 16px 0 0;padding:16px 20px;box-shadow:0 -2px 12px rgba(0,0,0,.1);z-index:999;display:none}
#add-panel h3{font-size:18px;color:#333;margin-bottom:4px}
#add-panel .hint{font-size:13px;color:#666;margin-bottom:10px}
#add-panel label{font-size:14px;color:#555;display:block;margin-top:8px}
#add-panel input{width:100%;padding:10px 12px;font-size:16px;border:1px solid #ddd;border-radius:8px;margin-top:4px;box-sizing:border-box}
#add-panel button{width:100%;padding:14px;font-size:18px;color:white;background:#1976D2;border:none;border-radius:10px;margin-top:12px}
.fence-item{margin:4px 0}
.fence-dot{display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:6px}
</style></head>
<body>
<div id="container"></div>
<div id="loading">加载地图中...</div>
<div id="info-panel">
  <h3 id="title">老人位置</h3>
  <p id="time">--</p>
  <p id="coord">--</p>
  <div id="fence-info"></div>
</div>
<div id="add-panel">
  <h3 id="add-panel-title">📍 添加电子围栏</h3>
  <p class="hint">拖拽蓝色圆心移动 • 拖拽橙色圆点调整大小</p>
  <label>围栏名称</label>
  <input type="text" id="fence-name" placeholder="如：家、公园" maxlength="20">
  <label>半径（米）</label>
  <input type="number" id="fence-radius" value="200" min="50" max="2000" oninput="handleRadiusInput()">
  <p style="font-size:12px;color:#999;margin-top:2px">范围：50-2000米</p>
  <button onclick="saveFence()">💾 保存围栏</button>
</div>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
var map=null,elderMarker=null,fCircles=[],fData=[];
var editCircle=null,ctrMarker=null,edgeMarker=null;
var ctr=null,cRad=200;

// ======== WGS-84 <-> GCJ-02 ========
var PI=Math.PI,a=6378245.0,ee=0.00669342162296594323;
function _tLat(x,y){var r=-100+2*x+3*y+.2*y*y+.1*x*y+.2*Math.sqrt(Math.abs(x));r+=(20*Math.sin(6*x*PI)+20*Math.sin(2*x*PI))*2/3;r+=(20*Math.sin(y*PI)+40*Math.sin(y/3*PI))*2/3;r+=(160*Math.sin(y/12*PI)+320*Math.sin(y*PI/30))*2/3;return r}
function _tLng(x,y){var r=300+x+2*y+.1*x*x+.1*x*y+.1*Math.sqrt(Math.abs(x));r+=(20*Math.sin(6*x*PI)+20*Math.sin(2*x*PI))*2/3;r+=(20*Math.sin(x*PI)+40*Math.sin(x/3*PI))*2/3;r+=(150*Math.sin(x/12*PI)+300*Math.sin(x/30*PI))*2/3;return r}
function outOfChina(lat,lng){return lng<72.004||lng>137.8347||lat<.8293||lat>55.8271}
function wgs2gcj(lat,lng){if(outOfChina(lat,lng))return[lat,lng];var dLat=_tLat(lng-105,lat-35),dLng=_tLng(lng-105,lat-35),rad=lat/180*PI,m=Math.sin(rad);m=1-ee*m*m;var s=Math.sqrt(m);dLat=dLat*180/((a*(1-ee))/(m*s)*PI);dLng=dLng*180/(a/s*Math.cos(rad)*PI);return[lat+dLat,lng+dLng]}
function gcj2wgs(lat,lng){if(outOfChina(lat,lng))return[lat,lng];var wLat=lat,wLng=lng;for(var i=0;i<5;i++){var g=wgs2gcj(wLat,wLng);wLat+=lat-g[0];wLng+=lng-g[1]}return[wLat,wLng]}

function initMap(lat,lng){
  if(map)return;
  map=L.map('container').setView([lat,lng],15);
  L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',{subdomains:['1','2','3','4'],maxZoom:18}).addTo(map);
  document.getElementById('loading').style.display='none';
}

function je(s){return s.replace(/\\/g,'\\\\').replace(/'/g,"\\'").replace(/\n/g,'\\n')}
var editingFenceId=null;
function showLoading(text){
  var el=document.getElementById('loading');
  if(el){el.textContent=text||'加载中...';el.style.display='block'}
}
function hideLoading(){
  var el=document.getElementById('loading');
  if(el)el.style.display='none'
}
function handleRadiusInput(){
  if(!ctr)return;
  var r=parseInt(document.getElementById('fence-radius').value)||200;
  cRad=Math.max(50,Math.min(2000,r));
  editCircle.setRadius(cRad);
  updEdge()
}
function editFence(id,name,lat,lng,radius){
  if(!map)return;
  clearFences();
  if(elderMarker)map.removeLayer(elderMarker);
  document.getElementById('info-panel').style.display='none';
  setupEditableFence(lat,lng,radius);
  document.getElementById('fence-name').value=name;
  document.getElementById('fence-radius').value=radius;
  document.getElementById('add-panel-title').textContent='✏️ 编辑围栏';
  editingFenceId=id
}

function setElderLocation(lat,lng,name,time){
  if(!map)initMap(lat,lng);
  if(elderMarker)map.removeLayer(elderMarker);
  var gcj=wgs2gcj(lat,lng);
  elderMarker=L.circleMarker([gcj[0],gcj[1]],{radius:8,color:'#F44336',fillColor:'#F44336',fillOpacity:1}).addTo(map);
  map.setView([gcj[0],gcj[1]],16);
  document.getElementById('info-panel').style.display='block';
  document.getElementById('title').textContent=(name||'老人')+' 的位置';
  document.getElementById('time').textContent='定位时间：'+(time||'未知');
  document.getElementById('coord').textContent='坐标：'+gcj[0].toFixed(6)+', '+gcj[1].toFixed(6);
}

function addFence(id,name,lat,lng,radius,isBreached){
  if(!map)initMap(lat,lng);
  var gcj=wgs2gcj(lat,lng);
  var c=isBreached?'#F44336':'#4CAF50';
  var circle=L.circle([gcj[0],gcj[1]],{radius:radius,color:c,fillColor:c,fillOpacity:.1}).addTo(map);
  circle.bindPopup(name+' ('+radius+'米)'+(isBreached?' ⚠️已越界':''));
  circle.on('click',function(){editFence(id,name,lat,lng,radius)});
  fCircles.push(circle);
  fData.push({id:id,name:name,radius:radius,isBreached:isBreached});
  updateInfo();
}

function clearFences(){fCircles.forEach(function(c){map.removeLayer(c)});fCircles=[];fData=[]}

function updateInfo(){
  var h='';if(fData.length>0){
    h='<b>电子围栏 ('+fData.length+')</b>';
    fData.forEach(function(f){
      h+='<div class="fence-item"><span class="fence-dot" style="background:'+(f.isBreached?'#F44336':'#4CAF50')+'"></span>'+f.name+' ('+f.radius+'米)'+(f.isBreached?' ⚠️':'')+'</div>';
    });
  }else{h='<span style="color:#999">暂未设置电子围栏</span>'}
  document.getElementById('fence-info').innerHTML=h;
}

function getEP(){
  if(!ctr||!map)return[0,0];
  var p=map.latLngToContainerPoint(ctr);
  var z=map.getZoom();
  var mpp=156543.03392*Math.cos(ctr.lat*Math.PI/180)/Math.pow(2,z);
  return map.containerPointToLatLng(L.point(p.x+cRad/mpp,p.y));
}

function updEdge(){if(edgeMarker&&ctr)edgeMarker.setLatLng(getEP())}

function setupEditableFence(lat,lng,radius){
  if(!map)initMap(lat,lng);
  if(editCircle)map.removeLayer(editCircle);
  if(ctrMarker)map.removeLayer(ctrMarker);
  if(edgeMarker)map.removeLayer(edgeMarker);
  var gcj=wgs2gcj(lat,lng);
  ctr=L.latLng(gcj[0],gcj[1]);cRad=radius||200;
  editCircle=L.circle(ctr,{radius:cRad,color:'#1976D2',fillColor:'#1976D2',fillOpacity:.08}).addTo(map);
  ctrMarker=L.marker(ctr,{
    icon:L.divIcon({html:'<div style="width:20px;height:20px;background:#1976D2;border:3px solid white;border-radius:50%;box-shadow:0 2px 4px rgba(0,0,0,.3)"></div>',iconSize:[20,20],iconAnchor:[10,10]}),
    draggable:true
  }).addTo(map);
  ctrMarker.on('drag',function(e){ctr=e.target.getLatLng();editCircle.setLatLng(ctr);updEdge()});
  edgeMarker=L.marker(getEP(),{
    icon:L.divIcon({html:'<div style="width:14px;height:14px;background:#FF9800;border:2px solid white;border-radius:50%;box-shadow:0 2px 4px rgba(0,0,0,.3)"></div>',iconSize:[14,14],iconAnchor:[7,7]}),
    draggable:true
  }).addTo(map);
  edgeMarker.on('drag',function(e){
    var d=ctr.distanceTo(e.target.getLatLng());
    cRad=Math.round(Math.max(50,Math.min(2000,d)));
    editCircle.setRadius(cRad);
    document.getElementById('fence-radius').value=cRad;
  });
  edgeMarker.on('dragend',updEdge);
  map.setView(ctr,15);
  document.getElementById('add-panel').style.display='block';
  document.getElementById('fence-radius').value=cRad;
}

function saveFence(){
  if(!ctr)return;
  var n=document.getElementById('fence-name').value.trim()||'围栏';
  var r=parseInt(document.getElementById('fence-radius').value)||200;
  r=Math.max(50,Math.min(2000,r));
  var wgs=gcj2wgs(ctr.lat,ctr.lng);
  if(window.Android){
    if(editingFenceId){window.Android.onUpdateFence(editingFenceId,n,wgs[0],wgs[1],r)}
    else{window.Android.onSaveFence(n,wgs[0],wgs[1],r)}
  }else{alert('保存失败：请重试')}
}

function showSingleFence(name,lat,lng,radius,isBreached){
  if(!map)initMap(lat,lng);
  var gcj=wgs2gcj(lat,lng);
  var c=isBreached?'#F44336':'#4CAF50';
  L.circle([gcj[0],gcj[1]],{radius:radius,color:c,fillColor:c,fillOpacity:.12}).addTo(map);
  L.circleMarker([gcj[0],gcj[1]],{radius:6,color:c,fillColor:c,fillOpacity:1}).addTo(map);
  map.setView([gcj[0],gcj[1]],15);
  document.getElementById('info-panel').style.display='block';
  document.getElementById('title').textContent='📌 '+name;
  document.getElementById('coord').textContent='坐标：'+gcj[0].toFixed(6)+', '+gcj[1].toFixed(6)+' | 半径：'+radius+'米';
  document.getElementById('time').textContent='';
}
</script>
</body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL("https://localhost", html, "text/html", "UTF-8", null)
    }

    // ========================================
    // 查看模式：加载老人位置 + 所有围栏
    // ========================================
    private fun loadElderData() {
        if (!CloudBaseClient.hasBoundElder()) {
            Toast.makeText(this, "请先绑定老人设备", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                // 1. 先展示缓存位置
                val status = CloudBaseClient.getElderStatus()
                var hasData = false
                if (status != null && status.lastLocation != null) {
                    val loc = status.lastLocation
                    evalJs("setElderLocation(${loc.latitude},${loc.longitude},'${esc(status.name)}','${esc(formatTime(loc.timestamp))}')")
                    hasData = true
                }
                // 加载围栏
                val fences = CloudBaseClient.getGeofences()
                if (fences.isNotEmpty()) {
                    evalJs("clearFences()")
                    fences.forEach { f ->
                        evalJs("addFence('${esc(f.id)}','${esc(f.name)}',${f.latitude},${f.longitude},${f.radius},${f.isBreached})")
                    }
                }

                // 2. 请求老人实时位置（延长弹窗提示）
                runOnUiThread { Toast.makeText(this@MapActivity, "📡 正在获取实时位置...", Toast.LENGTH_LONG).show() }

                val requestTime = CloudBaseClient.requestElderLocation()
                if (requestTime == null) {
                    evalJs("hideLoading()")
                    runOnUiThread { Toast.makeText(this@MapActivity, "❌ 无法连接到老人设备", Toast.LENGTH_LONG).show() }
                    return@launch
                }

                // 3. 轮询等待老人上传新位置（最多30秒）
                // 老人端：每3秒轮询(最多3s延迟) + GPS单次定位(最多15s) = 最坏18s
                // 留足余量：30s
                val startWait = System.currentTimeMillis()
                while (System.currentTimeMillis() - startWait < 30_000) {
                    kotlinx.coroutines.delay(1500)
                    val fresh = CloudBaseClient.getElderStatus()
                    if (fresh != null && fresh.lastLocation != null) {
                        val loc = fresh.lastLocation
                        // 双重检测：时间戳新于请求 或 pullLocationStatus变为done
                        if (loc.timestamp > requestTime || fresh.pullLocationStatus == "done") {
                            evalJs("hideLoading()")
                            evalJs("setElderLocation(${loc.latitude},${loc.longitude},'${esc(fresh.name)}','${esc(formatTime(loc.timestamp))}')")
                            runOnUiThread { Toast.makeText(this@MapActivity, "✅ 已获取实时位置", Toast.LENGTH_SHORT).show() }
                            return@launch
                        }
                        if (fresh.pullLocationStatus == "pending") {
                            evalJs("document.getElementById('time')?.textContent='等待老人设备响应...'")
                        }
                    }
                }
                AppLogger.w(TAG, "位置拉取超时(>30s)，显示最近位置")
                evalJs("hideLoading()")
                runOnUiThread { Toast.makeText(this@MapActivity, "⏱ 位置获取超时，显示最近位置", Toast.LENGTH_LONG).show() }
                evalJs("document.getElementById('time')?.textContent='显示最近位置（实时获取超时）'")
                // 超时：保留步骤1的缓存位置
            } catch (e: Exception) {
                evalJs("hideLoading()")
                Toast.makeText(this@MapActivity, "加载失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ========================================
    // 添加模式：可编辑围栏 + 保存按钮
    // ========================================
    private fun setupAddMode() {
        if (!CloudBaseClient.hasBoundElder()) {
            Toast.makeText(this, "请先绑定老人设备", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val status = CloudBaseClient.getElderStatus()
                val loc = status?.lastLocation
                val lat = loc?.latitude ?: 39.9042
                val lng = loc?.longitude ?: 116.4074
                if (loc == null) {
                    Toast.makeText(this@MapActivity, "暂无老人位置，请拖拽调整", Toast.LENGTH_LONG).show()
                }
                val fences = CloudBaseClient.getGeofences()
                val dn = "围栏${fences.size + 1}"
                evalJs("editingFenceId=null;document.getElementById('add-panel-title').textContent='📍 添加电子围栏'")
                evalJs("setupEditableFence($lat,$lng,200)")
                evalJs("document.getElementById('fence-name').value='${esc(dn)}'")
            } catch (e: Exception) {
                Toast.makeText(this@MapActivity, "加载失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ========================================
    // 查看单个围栏模式
    // ========================================
    private fun setupViewFenceMode() {
        val name = intent.getStringExtra("fenceName") ?: "围栏"
        val lat = intent.getDoubleExtra("fenceLat", 39.9042)
        val lng = intent.getDoubleExtra("fenceLng", 116.4074)
        val radius = intent.getIntExtra("fenceRadius", 200)

        evalJs("showSingleFence('${esc(name)}',$lat,$lng,$radius,false)")

        // 叠加载老人位置（WGS84→GCJ02转换后显示）
        lifecycleScope.launch {
            try {
                val status = CloudBaseClient.getElderStatus()
                if (status != null && status.lastLocation != null) {
                    val loc = status.lastLocation
                    evalJs(
                        "if(map){var g=wgs2gcj(${loc.latitude},${loc.longitude});L.circleMarker([g[0],g[1]]," +
                        "{radius:8,color:'#F44336',fillColor:'#F44336',fillOpacity:1})" +
                        ".addTo(map).bindPopup('${esc(status.name)}').openPopup();}"
                    )
                }
            } catch (_: Exception) { }
        }
    }

    // ========================================
    // 编辑模式：加载已有围栏直接进入编辑
    // ========================================
    private fun setupEditMode() {
        val fenceId = intent.getStringExtra("fenceId") ?: ""
        val name = intent.getStringExtra("fenceName") ?: "围栏"
        val lat = intent.getDoubleExtra("fenceLat", 39.9042)
        val lng = intent.getDoubleExtra("fenceLng", 116.4074)
        val radius = intent.getIntExtra("fenceRadius", 200)
        
        evalJs("editingFenceId='${esc(fenceId)}';document.getElementById('add-panel-title').textContent='✏️ 编辑围栏'")
        evalJs("setupEditableFence($lat,$lng,$radius)")
        evalJs("document.getElementById('fence-name').value='${esc(name)}'")
        evalJs("document.getElementById('fence-radius').value=$radius")
    }
    
    // ========================================
    // 工具函数
    // ========================================
    private fun evalJs(js: String) {
        webView.evaluateJavascript(js, null)
    }

    private fun esc(s: String): String {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
    }

    private fun formatTime(ts: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(ts))
    }

    // ========================================
    // 生命周期
    // ========================================
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
