package com.abbas.hudlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

data class AppEntry(val label: String, val icon: Drawable, val component: ComponentName)

/**
 * Our replacement for Rokid's app list page (which cannot be opened from outside its launcher).
 * Shows every LAUNCHER activity except ourselves in a scrolling window of [visibleRows] rows.
 * Swipe = move selection, tap = launch, double tap (BACK) = close.
 */
class AppPicker(
    private val context: Context,
    private val panel: View,
    private val rowsContainer: LinearLayout,
    private val title: TextView,
    private val visibleRows: Int,
) {
    private var apps: List<AppEntry> = emptyList()
    private var selected = 0
    private var windowStart = 0

    val isOpen: Boolean get() = panel.visibility == View.VISIBLE

    fun open() {
        apps = loadApps()
        selected = 0; windowStart = 0
        panel.visibility = View.VISIBLE
        render()
    }

    fun close() { panel.visibility = View.GONE }

    fun move(delta: Int) {
        if (apps.isEmpty()) return
        selected = (selected + delta).coerceIn(0, apps.lastIndex)
        if (selected < windowStart) windowStart = selected
        if (selected >= windowStart + visibleRows) windowStart = selected - visibleRows + 1
        render()
    }

    fun launchSelected() {
        val app = apps.getOrNull(selected) ?: return
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = app.component
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        try { context.startActivity(intent); close() } catch (e: Exception) {
            Log.w(TAG, "cannot launch ${app.component}: ${e.message}")
        }
    }

    private fun render() {
        title.text = context.getString(R.string.btn_apps) + "  ${selected + 1}/${apps.size}"
        rowsContainer.removeAllViews()
        val inflater = LayoutInflater.from(context)
        apps.drop(windowStart).take(visibleRows).forEachIndexed { i, app ->
            val row = inflater.inflate(R.layout.row_app, rowsContainer, false)
            row.findViewById<ImageView>(R.id.app_icon).setImageDrawable(app.icon)
            row.findViewById<TextView>(R.id.app_label).text = app.label
            val isSel = (windowStart + i == selected)
            row.setBackgroundResource(if (isSel) R.drawable.row_selected else 0)
            row.alpha = if (isSel) 1f else 0.75f
            row.setOnClickListener { selected = windowStart + i; launchSelected() }
            rowsContainer.addView(row)
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

    companion object { private const val TAG = "AppPicker" }
}
