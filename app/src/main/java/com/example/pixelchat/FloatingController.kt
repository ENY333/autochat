
package com.example.pixelchat

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

class FloatingController(
    private val service: AccessibilityService
) {
    private val wm = service.getSystemService(
        Context.WINDOW_SERVICE
    ) as WindowManager

    private val prefs = service.getSharedPreferences(
        "config", Context.MODE_PRIVATE
    )

    private val overlayType =
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    private var bubble: View? = null
    private var menu: View? = null
    private var selector: View? = null

    private fun dp(n: Int): Int =
        (n * service.resources.displayMetrics.density).toInt()

    private fun params(w: Int, h: Int) =
        WindowManager.LayoutParams(
            w, h, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

    private fun remove(v: View?) {
        if (v != null) runCatching { wm.removeView(v) }
    }

    private fun toast(s: String) {
        Toast.makeText(service, s, Toast.LENGTH_SHORT).show()
    }

    fun show() {
        if (bubble != null) return

        val btn = TextView(service).apply {
            text = "AI"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(36, 112, 220))
            }
        }

        val lp = params(dp(58), dp(58)).apply {
            x = dp(12)
            y = dp(180)
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        btn.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (kotlin.math.abs(dx) > 8 ||
                        kotlin.math.abs(dy) > 8) moved = true
                    lp.x = (startX + dx).toInt().coerceAtLeast(0)
                    lp.y = (startY + dy).toInt().coerceAtLeast(0)
                    wm.updateViewLayout(btn, lp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleMenu()
                    true
                }
                else -> true
            }
        }

        wm.addView(btn, lp)
        bubble = btn
    }

    private fun toggleMenu() {
        if (menu != null) {
            remove(menu)
            menu = null
            return
        }

        val layout = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(27, 38, 53))
        }

        fun action(label: String, callback: () -> Unit) {
            layout.addView(Button(service).apply {
                text = label
                setOnClickListener {
                    remove(menu)
                    menu = null
                    callback()
                }
            })
        }

        action("Chọn vị trí User") { select("slot") }
        action("Chọn tên người nhận") { select("contact") }
        action("Chọn vùng tin nhắn") { select("messages") }
        action("Chọn ô nhập") { select("input") }
        action("Chọn nút gửi") { select("send") }
        action("Xóa vùng cuối") { deleteLast() }

        action("BẬT BOT") {
            prefs.edit().putBoolean("running", true).apply()
            toast("Đã bật theo dõi")
        }

        action("DỪNG KHẨN CẤP") {
            prefs.edit().putBoolean("running", false).apply()
            (service as? ChatAccessibilityService)?.stopNow()
            toast("Đã dừng bot")
        }

        val p = params(
            dp(270),
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            x = dp(75)
            y = dp(90)
        }

        wm.addView(layout, p)
        menu = layout
    }

    private fun select(role: String) {
        val pkg = service.rootInActiveWindow
            ?.packageName?.toString().orEmpty()

        if (pkg.isBlank() || pkg == service.packageName) {
            toast("Mở ứng dụng chat trước khi chọn vùng")
            return
        }

        remove(bubble)
        bubble = null

        val view = RegionOverlay(service) { rect ->
            remove(selector)
            selector = null
            show()

            if (rect == null) {
                toast("Đã hủy chọn vùng")
            } else {
                val arr = runCatching {
                    JSONArray(prefs.getString("regions", "[]"))
                }.getOrDefault(JSONArray())

                val item = JSONObject()
                    .put("role", role)
                    .put("pkg", pkg)
                    .put("x", rect.left.toDouble())
                    .put("y", rect.top.toDouble())
                    .put("right", rect.right.toDouble())
                    .put("bottom", rect.bottom.toDouble())

                val index = arr.length()
                arr.put(item)

                prefs.edit()
                    .putString("regions", arr.toString())
                    .putString("editing_pkg", pkg)
                    .apply()

                if (role == "slot") {
                    val input = EditText(service).apply {
                        hint = "Tên user chính xác"
                        setSingleLine()
                    }

                    val dialog = android.app.AlertDialog.Builder(service)
                        .setTitle("Gán vùng cho user")
                        .setView(input)
                        .setPositiveButton("Lưu") { _, _ ->
                            val name = input.text.toString().trim()
                            if (name.isNotBlank()) {
                                val slots = runCatching {
                                    JSONArray(prefs.getString("slots", "[]"))
                                }.getOrDefault(JSONArray())

                                slots.put(
                                    JSONObject()
                                        .put("name", name)
                                        .put("pkg", pkg)
                                        .put("region", index)
                                )

                                val allowed = prefs.getString(
                                    "allowed_packages", ""
                                ).orEmpty().split(",")
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                    .plus(pkg).distinct().joinToString(",")

                                prefs.edit()
                                    .putString("slots", slots.toString())
                                    .putString("allowed_packages", allowed)
                                    .apply()

                                toast("Đã lưu user $name")
                            }
                        }
                        .setNegativeButton("Hủy", null)
                        .create()

                    dialog.window?.setType(overlayType)
                    dialog.show()
                } else {
                    toast("Đã lưu vùng $role")
                }
            }
        }

        val p = params(-1, -1).apply {
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }

        wm.addView(view, p)
        selector = view
    }

    private fun deleteLast() {
        val arr = runCatching {
            JSONArray(prefs.getString("regions", "[]"))
        }.getOrDefault(JSONArray())

        if (arr.length() == 0) {
            toast("Chưa có vùng nào")
            return
        }

        val updated = JSONArray()
        for (i in 0 until arr.length() - 1) {
            updated.put(arr.get(i))
        }

        prefs.edit()
            .putString("regions", updated.toString())
            .putString("slots", "[]")
            .apply()

        toast("Đã xóa vùng cuối. Cần gán lại user.")
    }

    fun close() {
        remove(selector)
        remove(menu)
        remove(bubble)
        selector = null
        menu = null
        bubble = null
    }
}

private class RegionOverlay(
    context: Context,
    private val done: (RectF?) -> Unit
) : View(context) {
    private val paint = Paint(3).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private var x0 = 0f
    private var y0 = 0f
    private var x1 = 0f
    private var y1 = 0f

    init {
        setBackgroundColor(0x33000000)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(x0, y0, x1, y1, paint)

        val label = Paint(3).apply {
            color = Color.WHITE
            textSize = 32f
        }

        canvas.drawText(
            "Kéo để chọn vùng pixel",
            16f, 65f, label
        )
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.x
                y0 = e.y
                x1 = x0
                y1 = y0
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                x1 = e.x
                y1 = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                x1 = e.x
                y1 = e.y

                val l = minOf(x0, x1)
                val t = minOf(y0, y1)
                val r = maxOf(x0, x1)
                val b = maxOf(y0, y1)

                val rect = if (r - l < 18 || b - t < 18) {
                    null
                } else {
                    RectF(
                        l / width, t / height,
                        r / width, b / height
                    )
                }

                done(rect)
            }
        }
        return true
    }
}
