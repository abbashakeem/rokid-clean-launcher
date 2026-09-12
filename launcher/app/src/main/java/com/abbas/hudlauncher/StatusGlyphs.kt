package com.abbas.hudlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Battery outline with proportional fill and a bolt while charging. White on transparent. */
class BatteryView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var level = 100; set(v) { field = v.coerceIn(0, 100); invalidate() }
    var charging = false; set(v) { field = v; invalidate() }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFFFFFFF.toInt() }
    private val bolt = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val sw = h / 9f; stroke.strokeWidth = sw
        val capW = w * 0.08f
        val body = RectF(sw / 2, sw / 2, w - capW - sw / 2, h - sw / 2)
        c.drawRoundRect(body, sw, sw, stroke)
        c.drawRoundRect(RectF(w - capW, h * 0.3f, w, h * 0.7f), sw / 2, sw / 2, fill)
        val inner = RectF(body.left + sw * 1.4f, body.top + sw * 1.4f, body.right - sw * 1.4f, body.bottom - sw * 1.4f)
        val fw = inner.width() * level / 100f
        if (fw > 0) c.drawRoundRect(RectF(inner.left, inner.top, inner.left + fw, inner.bottom), sw / 2, sw / 2, fill)
        if (charging) {
            val cx = body.centerX(); val cy = body.centerY(); val bh = inner.height() * 0.9f; val bw = bh * 0.55f
            bolt.reset()
            bolt.moveTo(cx + bw * 0.1f, cy - bh / 2); bolt.lineTo(cx - bw / 2, cy + bh * 0.1f)
            bolt.lineTo(cx - bw * 0.05f, cy + bh * 0.1f); bolt.lineTo(cx - bw * 0.1f, cy + bh / 2)
            bolt.lineTo(cx + bw / 2, cy - bh * 0.1f); bolt.lineTo(cx + bw * 0.05f, cy - bh * 0.1f); bolt.close()
            // punch the bolt out of the fill so it stays visible at any level
            val punch = Paint(fill).apply { color = 0xFF000000.toInt() }
            c.drawPath(bolt, punch)
        }
    }
}

/** Wi-Fi fan: four arcs lit up to [level] (0..4); level -1 = disconnected (dim outline only). */
class WifiView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var level = -1; set(v) { field = v.coerceIn(-1, 4); invalidate() }

    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeCap = Paint.Cap.ROUND }
    private val off = Paint(on).apply { color = 0xFF505050.toInt() }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFFFFFFF.toInt() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h * 0.95f
        val sw = h / 9f; on.strokeWidth = sw; off.strokeWidth = sw
        val maxR = h * 0.9f
        // arcs 1..3 (outer to inner) + centre dot = 4 levels
        for (i in 3 downTo 1) {
            val r = maxR * i / 3f
            val p = if (level >= i + 1) on else off
            c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 225f, 90f, false, p)
        }
        dot.color = if (level >= 1) 0xFFFFFFFF.toInt() else 0xFF505050.toInt()
        c.drawCircle(cx, cy - sw, sw * 0.9f, dot)
    }
}
