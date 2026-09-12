package com.abbas.hudlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import org.json.JSONObject

/**
 * Opens Rokid "scenes" (translation, teleprompter, subtitles, vision AI, navigation) through the
 * assist server's binder, exactly like Rokid's own launcher does:
 *   IAssistServer.controlMsgJson(pkg, {"type":"cmd_open_scene_with_ignore_tips","data":{...}})
 * The service is exported without a permission. Interface details come from Rokid's launcher APK.
 */
class RokidScenes(private val context: Context) {
    private var server: IBinder? = null
    private var pending: String? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            server = binder
            pending?.let { send(it); pending = null }
        }
        override fun onServiceDisconnected(name: ComponentName) { server = null }
    }

    fun bind() {
        if (server != null) return
        val intent = Intent().setComponent(ComponentName(ASSIST_PKG, ASSIST_SERVICE))
        try { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) } catch (e: Exception) {
            Log.w(TAG, "bind failed: ${e.message}")
        }
    }

    fun unbind() {
        try { context.unbindService(connection) } catch (_: Exception) {}
        server = null
    }

    fun openScene(sceneKey: String) {
        val data = JSONObject().put("sceneKey", sceneKey).put("useTips", true).put("aiAssist", false)
        val msg = JSONObject().put("type", CMD_OPEN_SCENE).put("data", data).toString()
        if (server?.isBinderAlive == true) send(msg) else { pending = msg; bind() }
    }

    private fun send(json: String) {
        val binder = server ?: return
        val req = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            req.writeInterfaceToken(DESCRIPTOR)
            req.writeString(CALLER_PKG)
            req.writeString(json)
            binder.transact(TRANSACTION_CONTROL_MSG_JSON, req, reply, 0)
            reply.readException()
            Log.d(TAG, "sent $json")
        } catch (e: Exception) {
            Log.w(TAG, "controlMsgJson failed: ${e.message}")
        } finally { req.recycle(); reply.recycle() }
    }

    companion object {
        private const val TAG = "RokidScenes"
        const val ASSIST_PKG = "com.rokid.os.sprite.assistserver"
        const val ASSIST_SERVICE = "com.rokid.os.sprite.assist.MasterAssistService"
        private const val DESCRIPTOR = "com.rokid.os.sprite.assist.server.IAssistServer"
        private const val TRANSACTION_CONTROL_MSG_JSON = 3
        private const val CMD_OPEN_SCENE = "cmd_open_scene_with_ignore_tips"
        /** The server keys behaviour off the caller name; Rokid's launcher identifies as itself. */
        private const val CALLER_PKG = "com.rokid.os.sprite.launcher"

        const val SCENE_TRANSLATE = "translate"
        const val SCENE_TELEPROMPTER = "word_tips"
        const val SCENE_SUBTITLES = "accessibility"
        const val SCENE_VISION_AI = "ai_chat"
        const val SCENE_NAVIGATION = "navigation"

        const val ROKID_LAUNCHER_PKG = "com.rokid.os.sprite.launcher"
        const val ACT_MUSIC = "com.rokid.os.sprite.launcher.page.music.MusicPageActivity"
        const val ACT_DEVICE_INFO = "com.rokid.os.sprite.launcher.setting.info.SettingSystemInfoActivity"
        const val ACT_HOME = "com.rokid.os.sprite.launcher.main.SpriteMainActivity"
    }
}
