package com.example.pixelchat

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity:Activity() {
 override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState)
  val prefs=getSharedPreferences("config",MODE_PRIVATE)
  val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(22,24,22,24) }
  fun title(t:String) { layout.addView(TextView(this).apply { text=t; textSize=20f }) }
  fun field(hint:String,key:String):EditText { val e=EditText(this).apply { this.hint=hint; setText(prefs.getString(key,"")); setSingleLine() }; layout.addView(e); return e }
  fun button(label:String, action:()->Unit) { layout.addView(Button(this).apply { text=label; setOnClickListener { action() } }) }
  title("AI PIXEL CHAT STUDIO v5")
  button("1. Cấp quyền Accessibility") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
  button("2. Kéo thả nhiều vùng pixel") {
   val svc=ChatAccessibilityService.instance
   if(svc==null) Toast.makeText(this,"Bật Accessibility trước",Toast.LENGTH_LONG).show()
   else svc.captureForEditor { bmp -> runOnUiThread { if(bmp!=null) setContentView(RegionEditor(this,bmp) { recreate() }) else Toast.makeText(this,"Không thể chụp màn hình ứng dụng hiện tại",Toast.LENGTH_LONG).show() } }
  }
  title("Thiết lập các user ở danh sách chat")
  val slotName=field("Tên user chính xác trên thanh tiêu đề chat", "slot_name_edit")
  val slotPkg=field("Package ứng dụng của user", "slot_pkg_edit")
  val slotIndex=field("Số vùng pixel ứng với user (0,1,2...)", "slot_index_edit")
  button("Lưu user vào danh sách theo dõi") {
   val name=slotName.text.toString().trim(); val p=slotPkg.text.toString().trim()
   val index=slotIndex.text.toString().toIntOrNull()
   val regions=try { JSONArray(prefs.getString("regions","[]")) } catch (_:Exception) { JSONArray() }
   if(name.isBlank() || p.isBlank() || index==null || index !in 0 until regions.length()) {
    Toast.makeText(this,"Nhập tên, package và chỉ số vùng hợp lệ",Toast.LENGTH_LONG).show()
   } else {
    val slots=try { JSONArray(prefs.getString("slots","[]")) } catch (_:Exception) { JSONArray() }
    slots.put(JSONObject().put("name",name).put("pkg",p).put("region",index))
    prefs.edit().putString("slots",slots.toString()).apply()
    Toast.makeText(this,"Đã thêm $name (#$index)",Toast.LENGTH_SHORT).show()
   }
  }
  button("Xem / xóa user đã lưu") {
   val slots=try { JSONArray(prefs.getString("slots","[]")) } catch (_:Exception) { JSONArray() }
   val names=(0 until slots.length()).map { i -> val x=slots.getJSONObject(i); "${x.optString("name")} • ${x.optString("pkg")} • vùng ${x.optInt("region")}" }.toTypedArray()
   android.app.AlertDialog.Builder(this).setTitle("Chạm user để xóa").setItems(names) { _,index ->
    val updated=JSONArray(); for(i in 0 until slots.length()) if(i!=index) updated.put(slots.getJSONObject(i))
    prefs.edit().putString("slots",updated.toString()).apply()
   }.setNegativeButton("Đóng",null).show()
  }
  title("Cấu hình ứng dụng chat")
  val packages=field("Package được phép, cách nhau bằng dấu phẩy", "allowed_packages")
  val pkg=field("Package đang cấu hình (vd org.telegram.messenger)","editing_pkg")
  val contact=EditText(this).apply { hint="Tên cuộc trò chuyện cố định" }; layout.addView(contact)
  val send=EditText(this).apply { hint="View ID nút gửi (để trống = chỉ nhập nháp)" }; layout.addView(send)
  val endpoint=field("Backend HTTPS URL", "endpoint")
  val auto=CheckBox(this).apply { text="Tự động nhập/gửi (chỉ khi đã cấu hình nút gửi)"; isChecked=prefs.getBoolean("auto_send",false) }; layout.addView(auto)
  button("Lưu cấu hình") {
   val p=pkg.text.toString().trim()
   prefs.edit().putString("allowed_packages",packages.text.toString().trim()).putString("editing_pkg",p).putString("contact_$p",contact.text.toString().trim()).putString("send_id_$p",send.text.toString().trim()).putString("endpoint",endpoint.text.toString().trim()).putBoolean("auto_send",auto.isChecked).apply()
   Toast.makeText(this,"Đã lưu",Toast.LENGTH_SHORT).show()
  }
  button("BẮT ĐẦU QUÉT") { prefs.edit().putBoolean("running",true).apply(); Toast.makeText(this,"Đang theo dõi ứng dụng được phép",Toast.LENGTH_SHORT).show() }
  button("DỪNG KHẨN CẤP") { prefs.edit().putBoolean("running",false).apply(); ChatAccessibilityService.instance?.stopNow(); Toast.makeText(this,"Đã dừng",Toast.LENGTH_SHORT).show() }
  button("Xem bản nháp cuộc trò chuyện") { val key="${pkg.text.toString().trim()}|${contact.text.toString().trim()}"; android.app.AlertDialog.Builder(this).setTitle("Bản nháp").setMessage(prefs.getString("draft_$key","Chưa có bản nháp")).setPositiveButton("Đóng",null).show() }
  layout.addView(TextView(this).apply { text="Bảo mật: không lưu OpenAI API key trong APK. Backend HTTPS giữ API key. Mỗi app phải cấu hình package và người nhận riêng. Không gửi khi thiếu nút gửi hoặc ô nhập không rõ ràng. OCR có thể đọc sai; chỉ bật tự gửi sau khi kiểm tra." })
  setContentView(ScrollView(this).apply { addView(layout) })
 }
}
class RegionEditor(private val activity:Activity,private val bmp:Bitmap,private val done:()->Unit):View(activity) {
 private val paint=Paint(3)
 private val prefs=activity.getSharedPreferences("config",Activity.MODE_PRIVATE)
 private val areas=try { JSONArray(prefs.getString("regions","[]")) } catch (_:Exception) { JSONArray() }
 private var x0=0f; private var y0=0f; private var x1=0f; private var y1=0f
 private var role="messages"
 init { setOnLongClickListener { if(areas.length()>0) { val arr=JSONArray(); for(i in 0 until areas.length()-1) arr.put(areas.getJSONObject(i)); prefs.edit().putString("regions",arr.toString()).apply(); activity.recreate() }; true } }
 override fun onDraw(c:Canvas) { super.onDraw(c)
  val scale=minOf(width.toFloat()/bmp.width,height.toFloat()/bmp.height)
  c.drawBitmap(bmp,null,RectF(0f,0f,bmp.width*scale,bmp.height*scale),paint)
  paint.style=Paint.Style.STROKE; paint.strokeWidth=4f; paint.color=Color.GREEN
  for(i in 0 until areas.length()) { val a=areas.optJSONObject(i)?:continue; c.drawRect((a.optDouble("x")*bmp.width*scale).toFloat(),(a.optDouble("y")*bmp.height*scale).toFloat(),(a.optDouble("right")*bmp.width*scale).toFloat(),(a.optDouble("bottom")*bmp.height*scale).toFloat(),paint) }
  c.drawRect(x0,y0,x1,y1,paint); paint.style=Paint.Style.FILL; paint.color=Color.WHITE
 }
 override fun onTouchEvent(e:MotionEvent):Boolean { when(e.action) {
  MotionEvent.ACTION_DOWN -> { x0=e.x;y0=e.y;x1=e.x;y1=e.y;invalidate();return true }
  MotionEvent.ACTION_MOVE -> { x1=e.x;y1=e.y;invalidate();return true }
  MotionEvent.ACTION_UP -> {
   x1=e.x;y1=e.y
   val s=minOf(width.toFloat()/bmp.width,height.toFloat()/bmp.height)
   val l=(minOf(x0,x1)/s).coerceIn(0f,bmp.width.toFloat());val t=(minOf(y0,y1)/s).coerceIn(0f,bmp.height.toFloat())
   val r=(maxOf(x0,x1)/s).coerceIn(0f,bmp.width.toFloat());val b=(maxOf(y0,y1)/s).coerceIn(0f,bmp.height.toFloat())
   if(r-l>10 && b-t>10) {
    val choices=arrayOf("Vùng tin nhắn", "Vùng tên người", "Vị trí user trong danh sách")
    android.app.AlertDialog.Builder(activity).setTitle("Lưu vùng pixel #${areas.length()+1}").setItems(choices) { _,idx ->
     areas.put(JSONObject().put("role",if(idx==0) "messages" else if(idx==1) "contact" else "slot").put("x",l/bmp.width).put("y",t/bmp.height).put("right",r/bmp.width).put("bottom",b/bmp.height))
     prefs.edit().putString("regions",areas.toString()).apply(); invalidate()
     android.widget.Toast.makeText(activity,"Đã lưu ${areas.length()} vùng. Nhấn Back để trở về; giữ lâu để xóa vùng cuối",android.widget.Toast.LENGTH_LONG).show()
    }.show()
   }; return true
  }
 }; return true }
}
