package com.example.pixelchat

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("config", MODE_PRIVATE) }
    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drawHome()
    }

    private fun drawHome() {
        val dp = resources.displayMetrics.density
        fun px(value: Int) = (value * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(26), px(20), px(26))
        }

        fun text(value: String, size: Float = 16f) {
            root.addView(TextView(this).apply {
                this.text = value
                textSize = size
                setPadding(0, px(8), 0, px(8))
            })
        }

        fun button(value: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                text = value
                setOnClickListener { action() }
            })
        }

        val chosen = prefs.getString("locked_package", "").orEmpty()
        text("AI PIXEL CHAT STUDIO v7", 23f)
        text("Chỉ hoạt động trong MỘT ứng dụng do anh chọn. Bong bóng AI xuất hiện trong ứng dụng đó; khi rời đi bot tự tạm dừng.")
        text("Ứng dụng đang khóa: " + if (chosen.isEmpty()) "CHƯA CHỌN" else labelFor(chosen), 18f)
        text(if (chosen.isEmpty()) "" else "Package: $chosen", 12f)

        button("1. CHỌN ỨNG DỤNG TRÊN MÁY") { showInstalledApps() }
        button("2. BẬT QUYỀN TRỢ NĂNG (ACCESSIBILITY)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("3. MỞ ỨNG DỤNG ĐÃ CHỌN") {
            if (chosen.isBlank()) {
                toast("Anh hãy chọn ứng dụng trước")
            } else {
                val intent = packageManager.getLaunchIntentForPackage(chosen)
                if (intent == null) toast("Không tìm được ứng dụng để mở")
                else {
                    prefs.edit().putBoolean("bubble_enabled", true).apply()
                    startActivity(intent)
                }
            }
        }

        text("Cấu hình AI (không lưu khóa API trong APK)", 18f)
        val endpoint = EditText(this).apply {
            hint = "Backend HTTPS, ví dụ https://.../reply"
            setText(prefs.getString("endpoint", ""))
            setSingleLine()
        }
        root.addView(endpoint)

        val autoSend = CheckBox(this).apply {
            text = "Cho phép tự gửi (chỉ khi đã xác minh đúng người nhận và nút gửi)"
            isChecked = prefs.getBoolean("auto_send", false)
        }
        root.addView(autoSend)
        button("LƯU CẤU HÌNH AI") {
            val url = endpoint.text.toString().trim()
            if (url.isNotEmpty() && !url.startsWith("https://")) {
                toast("Backend phải dùng HTTPS")
            } else {
                prefs.edit()
                    .putString("endpoint", url)
                    .putBoolean("auto_send", autoSend.isChecked)
                    .apply()
                toast("Đã lưu cấu hình")
            }
        }

        button("BẬT THEO DÕI") {
            if (chosen.isBlank()) {
                toast("Chưa chọn ứng dụng")
            } else {
                prefs.edit().putBoolean("running", true).apply()
                toast("Đã bật. Mở ứng dụng đã chọn để dùng bong bóng AI.")
            }
        }
        button("DỪNG KHẨN CẤP") {
            prefs.edit().putBoolean("running", false).apply()
            ChatAccessibilityService.instance?.stopNow()
            toast("Đã dừng tất cả thao tác tự động")
        }
        button("XEM BẢN NHÁP") {
            val current = prefs.getString("locked_package", "").orEmpty()
            val name = prefs.getString("verified_$current", "").orEmpty()
            val draft = prefs.getString("draft_$current|$name", "") ?: ""
            AlertDialog.Builder(this)
                .setTitle("Bản nháp - " + (name.ifBlank { "chưa xác định user" }))
                .setMessage(draft.ifBlank { "Chưa có bản nháp cho cuộc trò chuyện được xác minh" })
                .setPositiveButton("Đóng", null).show()
        }
        text("Trong app chat: nhấn bong bóng AI → chọn vị trí user, tên người nhận, vùng tin nhắn, ô nhập và nút gửi. Vị trí chỉ có hiệu lực cho app đã khóa.", 14f)
        text("Chế độ tự gửi mặc định TẮT. Hãy thử bản nháp và kiểm tra đúng người nhận trước.", 13f)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun labelFor(pkg: String): String = try {
        val app = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(app).toString()
    } catch (_: Exception) { pkg }

    private fun showInstalledApps() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps: List<ResolveInfo> = packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }

        if (apps.isEmpty()) { toast("Không tìm thấy ứng dụng nào"); return }
        val labels = apps.map {
            "${it.loadLabel(packageManager)}  (${it.activityInfo.packageName})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Chỉ chọn 1 ứng dụng")
            .setItems(labels) { _, which ->
                val selected = apps[which].activityInfo.packageName
                val old = prefs.getString("locked_package", "").orEmpty()
                val editor = prefs.edit()
                    .putString("locked_package", selected)
                    .putString("allowed_packages", selected)
                    .putString("editing_pkg", selected)
                    .putBoolean("running", false)
                    .putBoolean("auto_send", false)
                    .putBoolean("bubble_enabled", true)
                if (old != selected) {
                    // Regions from an old app must never be reused in a new app.
                    editor.putString("regions", "[]")
                        .putString("slots", "[]")
                        .remove("verified_$old")
                }
                editor.apply()
                ChatAccessibilityService.instance?.stopNow()
                ChatAccessibilityService.instance?.refreshBubble()
                toast("Đã khóa vào: " + labelFor(selected))
                drawHome()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }
}
