package com.abbas.hudlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** One carousel entry: label, outline glyph, and what happens on tap. */
data class AppEntry(val label: String, val iconRes: Int, val watchReturn: Boolean = true, val action: () -> Unit)

/**
 * Rokid-style app picker: a horizontal carousel with the selected item centred and enlarged.
 * Entries mirror Rokid's own list: its scenes (via the assist server), its exported pages
 * (explicit intents) and Android Settings. Swipe = move, tap = open, double tap (BACK) = close.
 */
class AppPicker(
    private val context: Context,
    private val panel: View,
    private val strip: LinearLayout,
    private val label: TextView,
    private val scenes: RokidScenes,
) {
    private val apps: List<AppEntry> = buildEntries()
    private var selected = 0

    val isOpen: Boolean get() = panel.visibility == View.VISIBLE

    fun open() { selected = 0; panel.visibility = View.VISIBLE; render() }
    fun close() { panel.visibility = View.GONE }

    fun move(delta: Int) {
        selected = (selected + delta).coerceIn(0, apps.lastIndex)
        render()
    }

    fun launchSelected() {
        val app = apps.getOrNull(selected) ?: return
        close()
        // Rokid scenes/pages finish() onto Rokid's own home; the watcher brings us back afterwards.
        if (app.watchReturn) ReturnWatch.arm(context)
        try { app.action() } catch (e: Exception) { Log.w(TAG, "cannot open ${app.label}: ${e.message}") }
    }

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
                view.setImageResource(app.iconRes)
                val centre = offset == 0
                view.scaleX = if (centre) 1.25f else 0.85f
                view.scaleY = view.scaleX
                view.imageTintList = android.content.res.ColorStateList.valueOf(if (centre) 0xFFFFFFFF.toInt() else 0xFF8C8C8C.toInt())
                view.setOnClickListener { selected = idx; launchSelected() }
            }
            strip.addView(view)
        }
    }

    private fun activity(pkg: String, cls: String): () -> Unit = {
        context.startActivity(Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(pkg, cls)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        })
    }

    private fun buildEntries(): List<AppEntry> = listOf(
        AppEntry("Translation", R.drawable.app_translate) { scenes.openScene(RokidScenes.SCENE_TRANSLATE) },
        AppEntry("Teleprompter", R.drawable.app_prompter) { scenes.openScene(RokidScenes.SCENE_TELEPROMPTER) },
        AppEntry("Subtitles", R.drawable.app_subtitles) { scenes.openScene(RokidScenes.SCENE_SUBTITLES) },
        AppEntry("Music", R.drawable.app_music, action = activity(RokidScenes.ROKID_LAUNCHER_PKG, RokidScenes.ACT_MUSIC)),
        AppEntry("Camera", R.drawable.app_camera) { scenes.openScene(RokidScenes.SCENE_CAMERA) },
        AppEntry("Sound recorder", R.drawable.app_recorder) { scenes.openScene(RokidScenes.SCENE_AUDIO_RECORD) },
        AppEntry("Navigation", R.drawable.app_navigation) { scenes.openScene(RokidScenes.SCENE_NAVIGATION) },
        AppEntry("Vision AI", R.drawable.app_vision) { scenes.openScene(RokidScenes.SCENE_VISION_AI) },
        AppEntry("Device info", R.drawable.app_info, action = activity(RokidScenes.ROKID_LAUNCHER_PKG, RokidScenes.ACT_DEVICE_INFO)),
        AppEntry("Settings", R.drawable.app_settings, watchReturn = false, action = activity("com.android.settings", "com.android.settings.Settings")),
        AppEntry("Rokid home", R.drawable.app_rokid, watchReturn = false, action = activity(RokidScenes.ROKID_LAUNCHER_PKG, RokidScenes.ACT_HOME)),
    )

    companion object {
        private const val TAG = "AppPicker"
        private const val SIDE = 2
    }
}
