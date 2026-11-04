package com.example.pixelchat

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

class ChatAccessibilityService : AccessibilityService() {
 companion object { var instance: ChatAccessibilityService? = null; private set }
 private val main = Handler(Looper.getMainLooper())
 private val bubble by lazy { FloatingController(this) }
 private var previouslyInTarget = false
 private val io = Executors.newSingleThreadExecutor()
 private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
 private var scanning = false
 private var generation = 0
 private var lastScan = 0L
 private var pendingSlot: JSONObject? = null
 private var pendingAt = 0L
 private val seen = mutableMapOf<String, String>()
 override fun onServiceConnected() { super.onServiceConnected(); instance = this; refreshBubble() }
 fun refreshBubble() {
  val target=getSharedPreferences("config",MODE_PRIVATE).getString("locked_package","").orEmpty()
  val foreground=rootInActiveWindow?.packageName?.toString().orEmpty()
  val inTarget=target.isNotBlank() && target==foreground
  if(inTarget) bubble.show() else bubble.close()
  if(previouslyInTarget && !inTarget) stopNow()
  previouslyInTarget=inTarget
 }
 private fun allowedForeground():Boolean {
  val target=getSharedPreferences("config",MODE_PRIVATE).getString("locked_package","").orEmpty()
  return target.isNotBlank() && rootInActiveWindow?.packageName?.toString()==target
 }
 override fun onAccessibilityEvent(event: AccessibilityEvent?) {
  val prefs = getSharedPreferences("config", MODE_PRIVATE)
  if (!prefs.getBoolean("running",false) || scanning || System.currentTimeMillis()-lastScan < 2500) return
  val pkg = event?.packageName?.toString().orEmpty()
  if (pkg.isBlank() || pkg == packageName) return
  val allow = prefs.getString("allowed_packages", "").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
  if (allow.isEmpty() || pkg !in allow) return
  if (pendingSlot != null && System.currentTimeMillis()-pendingAt>12000) pendingSlot=null
  lastScan = System.currentTimeMillis(); scanning = true
  takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object: TakeScreenshotCallback {
   override fun onSuccess(result: ScreenshotResult) {
    val bmp = try { Bitmap.wrapHardwareBuffer(result.hardwareBuffer,result.colorSpace)?.copy(Bitmap.Config.ARGB_8888,false) } finally { result.hardwareBuffer.close() }
    if (bmp == null) { scanning=false; return }
    if(pendingSlot!=null && pendingSlot?.optString("pkg")==pkg) analyze(bmp,pkg)
    else if(prefs.getString("slots","[]")!="[]") scanSlots(bmp,pkg)
    else analyze(bmp,pkg)
   }
   override fun onFailure(errorCode:Int) { scanning=false }
  })
 }
 fun captureForEditor(callback:(Bitmap?)->Unit) {
  takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object:TakeScreenshotCallback {
   override fun onSuccess(result:ScreenshotResult) {
    val bmp=try { Bitmap.wrapHardwareBuffer(result.hardwareBuffer,result.colorSpace)?.copy(Bitmap.Config.ARGB_8888,false) } finally { result.hardwareBuffer.close() }
    callback(bmp)
   }
   override fun onFailure(errorCode:Int) { callback(null) }
  })
 }
 private fun regionRect(a:JSONObject,bmp:Bitmap):Rect? {
  val l=(a.optDouble("x",0.0)*bmp.width).toInt().coerceIn(0,bmp.width-1)
  val t=(a.optDouble("y",0.0)*bmp.height).toInt().coerceIn(0,bmp.height-1)
  val r=(a.optDouble("right",1.0)*bmp.width).toInt().coerceIn(1,bmp.width)
  val b=(a.optDouble("bottom",1.0)*bmp.height).toInt().coerceIn(1,bmp.height)
  return if(r-l>5 && b-t>5) Rect(l,t,r,b) else null
 }
 private fun scanSlots(bmp:Bitmap,pkg:String) {
  val prefs=getSharedPreferences("config",MODE_PRIVATE)
  val slots=try { JSONArray(prefs.getString("slots","[]")) } catch (_:Exception) { JSONArray() }
  val regions=try { JSONArray(prefs.getString("regions","[]")) } catch (_:Exception) { JSONArray() }
  val candidates=(0 until slots.length()).mapNotNull { slots.optJSONObject(it) }
   .filter { it.optString("pkg")==pkg && it.optInt("region",-1) in 0 until regions.length() }
  if(candidates.isEmpty()) { scanning=false; return }
  var left=candidates.size
  candidates.forEach { slot ->
   val a=regions.optJSONObject(slot.optInt("region"))
   val rect=if(a!=null && a.optString("role")=="slot") regionRect(a,bmp) else null
   if(rect==null) { if(--left==0) scanning=false; return@forEach }
   val crop=Bitmap.createBitmap(bmp,rect.left,rect.top,rect.width(),rect.height())
   recognizer.process(InputImage.fromBitmap(crop,0)).addOnSuccessListener { result ->
    val name=slot.optString("name").trim()
    // Only enter a slot if its configured name is visible in its own rectangle.
    if(pendingSlot==null && name.isNotEmpty() && result.text.contains(name,ignoreCase=true)) {
     pendingSlot=slot; pendingAt=System.currentTimeMillis()
     prefs.edit().remove("verified_${pkg}").apply()
     val x=rect.centerX().toFloat(); val y=rect.centerY().toFloat()
     val path=Path().apply { moveTo(x,y) }
     val gesture=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,90)).build()
     dispatchGesture(gesture,null,null)
    }
   }.addOnCompleteListener { crop.recycle(); if(--left==0) scanning=false }
  }
 }
 private fun analyze(bmp:Bitmap,pkg:String) {
  val prefs=getSharedPreferences("config",MODE_PRIVATE)
  val regions=try { JSONArray(prefs.getString("regions","[]")) } catch (_:Exception) { JSONArray() }
  val selected=mutableListOf<Rect>()
  for(i in 0 until regions.length()) {
   val a=regions.optJSONObject(i) ?: continue
   if(a.optString("role")!="messages" && a.optString("role")!="contact") continue
   val r=Rect((a.optDouble("x",0.0)*bmp.width).toInt().coerceIn(0,bmp.width-1), (a.optDouble("y",0.0)*bmp.height).toInt().coerceIn(0,bmp.height-1), (a.optDouble("right",1.0)*bmp.width).toInt().coerceIn(1,bmp.width), (a.optDouble("bottom",1.0)*bmp.height).toInt().coerceIn(1,bmp.height))
   if(r.width()>5 && r.height()>5) selected.add(r)
  }
  if(selected.isEmpty()) { scanning=false; return }
  val slot=pendingSlot
  if(slot!=null) {
   // Verify chat header before OCR message processing; never trust list coordinates alone.
   val contactRegion=(0 until regions.length()).mapNotNull { regions.optJSONObject(it) }.firstOrNull { it.optString("role")=="contact" }
   val rect=contactRegion?.let { regionRect(it,bmp) }
   if(rect==null) { scanning=false; pendingSlot=null; return }
   val crop=Bitmap.createBitmap(bmp,rect.left,rect.top,rect.width(),rect.height())
   recognizer.process(InputImage.fromBitmap(crop,0)).addOnSuccessListener { result ->
    if(result.text.contains(slot.optString("name"),ignoreCase=true)) {
     val key="verified_${pkg}"; prefs.edit().putString(key,slot.optString("name")).apply()
    } else { pendingSlot=null; prefs.edit().remove("verified_${pkg}").apply() }
   }.addOnCompleteListener { crop.recycle(); scanning=false }
   // Message extraction continues only after verification on a later event.
   if(prefs.getString("verified_${pkg}","")!=slot.optString("name")) return
  }
  val outputs=mutableListOf<String>(); var pending=selected.size
  selected.forEach { r ->
   val crop=Bitmap.createBitmap(bmp,r.left,r.top,r.width(),r.height())
   recognizer.process(InputImage.fromBitmap(crop,0)).addOnSuccessListener { outputs.add(it.text.trim()) }.addOnCompleteListener {
    crop.recycle(); pending--
    if(pending==0) { scanning=false; val content=outputs.filter { it.isNotBlank() }.joinToString("\n"); if(content.isNotBlank()) processMessage(pkg,content) }
   }
  }
 }
 private fun processMessage(pkg:String, content:String) {
  val prefs=getSharedPreferences("config",MODE_PRIVATE)
  val contact=(pendingSlot?.optString("name") ?: prefs.getString("contact_$pkg", ""))?.trim().orEmpty()
  // No auto-send unless the contact is explicitly configured for this package.
  if(contact.isBlank()) return
  if(pendingSlot!=null && prefs.getString("verified_${pkg}","")!=contact) return
  val key="$pkg|$contact"
  val hash=MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
  if(seen[key]==hash || prefs.getString("seen_$key", "")==hash) return
  seen[key]=hash; prefs.edit().putString("seen_$key",hash).apply()
  val token=generation
  io.execute {
   try {
    val endpoint=prefs.getString("endpoint","").orEmpty()
    if(!endpoint.startsWith("https://")) return@execute
    val conn=URL(endpoint).openConnection() as HttpURLConnection
    conn.requestMethod="POST"; conn.connectTimeout=10000; conn.readTimeout=30000; conn.doOutput=true
    conn.setRequestProperty("Content-Type","application/json; charset=utf-8")
    val payload=JSONObject().put("conversation_id",key).put("message",content).put("language","auto")
    conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
    val reply=if(conn.responseCode in 200..299) JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optString("reply") else ""
    conn.disconnect()
    if(reply.isNotBlank()) {
     prefs.edit().putString("draft_$key",reply).apply()
     if(prefs.getBoolean("auto_send",false) && prefs.getBoolean("running",false) && token==generation) main.post { sendReply(pkg,reply,token) }
    }
   } catch (e:Exception) { prefs.edit().putString("last_error",e.message.orEmpty()).apply() }
  }
 }
 private fun sendReply(pkg:String,reply:String,token:Int) {
  val prefs=getSharedPreferences("config",MODE_PRIVATE)
  if(token!=generation || !prefs.getBoolean("running",false) || !prefs.getBoolean("auto_send",false)) return
  if(rootInActiveWindow?.packageName?.toString()!=pkg) return
  if(pendingSlot!=null && prefs.getString("verified_${pkg}","")!=pendingSlot?.optString("name")) return
  val root=rootInActiveWindow ?: return
  val inputs=mutableListOf<AccessibilityNodeInfo>()
  fun walk(n:AccessibilityNodeInfo) { if(n.isEditable && n.isVisibleToUser) inputs.add(n); for(i in 0 until n.childCount) n.getChild(i)?.let { walk(it) } }
  walk(root)
  if(inputs.size!=1) return // Ambiguous chat UI: never guess.
  val input=inputs.first()
  val args=android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,reply) }
  if(!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)) return
  // Send only when an explicit send-button view ID is configured for this app.
  val sendId=prefs.getString("send_id_$pkg","").orEmpty()
  if(sendId.isBlank()) return
  val buttons=root.findAccessibilityNodeInfosByViewId(sendId).filter { it.isVisibleToUser && it.isClickable }
  if(buttons.size==1 && token==generation && prefs.getBoolean("running",false)) {
   if(buttons.first().performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
    // Return to the list to continue monitoring other separately configured chats.
    if(pendingSlot!=null) main.postDelayed({
     if(token==generation && prefs.getBoolean("running",false)) {
      pendingSlot=null
      prefs.edit().remove("verified_${pkg}").apply()
      performGlobalAction(GLOBAL_ACTION_BACK)
     }
    }, 1200)
   }
  }
 }
 fun stopNow() { generation++; scanning=false; pendingSlot=null }
 override fun onInterrupt() { stopNow() }
 override fun onDestroy() { stopNow(); instance=null; recognizer.close(); io.shutdownNow(); super.onDestroy() }
}
