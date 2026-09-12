package com.abbas.hudlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** One carousel entry: either an installed app or a built-in action (e.g. brightness). */
data class AppEntry(
    val label: String,
    val icon: Drawable,
    val component: ComponentName? = null,
    val action: (() -> Unit)? = null,
)

/**
 * Rokid-style app picker: a horizontal carousel where the selected item sits in the centre,
 * enlarged, with its name underneath. Swipe = move, tap = open, double tap (BACK) = close.
 * Rokid's own picker lives inside its launcher activity and cannot be opened from outside.
 */
class AppPicker(
    private val context: Context,
    private val panel: View,
    private val strip: LinearLayout,
    private val label: TextView,
    private val builtIns: List<AppEntry>,
) {
    private var apps: List<AppEntry> = emptyList()
    private var selected = 0
    private val grayscale = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })

    val isOpen: Boolean get() = panel.visibility == View.VISIBLE

    fun open() {
        apps = builtIns + loadApps()
        selected = 0
        panel.visibility = View.VISIBLE
        render()
    }

    fun close() { panel.visibility = View.GONE }

    fun move(delta: Int) {
        if (apps.isEmpty()) return
        selected = (selected + delta).coerceIn(0, apps.lastIndex)
        render()
    }

    fun launchSelected() {
        val app = apps.getOrNull(selected) ?: return
        if (app.action != null) { close(); app.action.invoke(); return }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = app.component
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        try { context.startActivity(intent); close() } catch (e: Exception) {
            Log.w(TAG, "cannot launch ${app.component}: ${e.message}")
        }
    }

    /** Show SIDE items either side of the selection; empty slots keep the centre fixed. */
    private fun render() {
        label.text = apps.getOrNull(selected)?.label ?: ""
        strip.removeAllViews()
        val inflater = LayoutInflater.from(context)
        for (offset in -SIDE..SIDE) {
            val idx = selected + offset
            val view = inflater.inflate(R.layout.carousel_item, strip, false) as ImageView
            val app = apps.getOrNull(idx)
            if (app == null) {
                view.visibility = View.INVISIBLE
            } else {
                view.setImageDrawable(app.icon)
                view.colorFilter = grayscale                    // the display is monochrome anyway
                val centre = offset == 0
                view.scaleX = if (centre) 1.25f else 0.85f
                view.scaleY = view.scaleX
                view.alpha = if (centre) 1f else 0.55f
                view.setOnClickListener { selected = idx; launchSelected() }
            }
            strip.addView(view)
        }
    }

    private fun loadApps(): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map {
                AppEntry(
                    label = it.loadLabel(pm).toString(),
                    icon = it.loadIcon(pm),
                    component = ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    companion object {
        private const val TAG = "AppPicker"
        private const val SIDE = 2
    }
}
