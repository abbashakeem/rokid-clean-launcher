package com.abbas.hudlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class PhoneMessage(val app: String, val title: String, val text: String, val time: Long)

/** Weather relayed by the Rokid phone app (Weather_SendData). weatherId follows Rokid's table. */
data class RokidWeather(val address: String, val temp: Float, val tempHigh: Float, val tempLow: Float, val weatherId: Int, val receivedAt: Long)

/** Phone calendar entry relayed by the Rokid app (Ntf_ResetScheduleList). No calendar name is included. */
data class RokidSchedule(val title: String, val description: String, val scheduleTime: Long)

/** Navigation turn update from the phone (Nav_UpdateInfo, overseas format). */
data class NavUpdate(
    val iconType: Int, val iconPng: ByteArray?, val nextRoadName: String, val curRoadName: String,
    val stepRemainM: Int, val routeRemainM: Int, val routeRemainS: Int, val speedKmh: Int,
)

/**
 * Client of Rokid's assist server (com.rokid.os.sprite.assistserver/MasterAssistService, exported, no
 * permission). Protocol reverse-engineered from Rokid's launcher APK:
 *
 *  - IAssistServer (descriptor com.rokid.os.sprite.assist.server.IAssistServer)
 *      1 registerClient(String pkg, IAssistClient client)
 *      3 controlMsgJson(String pkg, String json)   json = {"type": cmd, "data": {...}}
 *  - IAssistClient (descriptor com.rokid.os.sprite.assist.client.IAssistClient), implemented by us:
 *      1 onRegisterResult(RegisterResult)       2 onMessageReceive(AssistMessage) -> boolean
 *      3 onDataReceive(String, String, byte[])
 *    AssistMessage parcel: messageId(long) packageName infoType time(long) message(json string)
 *
 * Message JSON types we use:
 *   cmd_bluetooth_gatt_status          data {"status": bool, ...}          phone app link
 *   cmd_bluetooth_gatt_normal_result   data {"cmd": "Ntf_SendNewMsg", "caps1": MobileNotifyData}
 *                                      data {"cmd": "Ntf_ResetMsgList", "caps1": [MobileNotifyData]}
 */
class RokidScenes(private val context: Context) {
    var onPhoneLink: ((Boolean) -> Unit)? = null
    var onMessages: ((List<PhoneMessage>) -> Unit)? = null
    var onWeather: ((RokidWeather) -> Unit)? = null
    var onSchedule: ((List<RokidSchedule>) -> Unit)? = null
    var onNavStart: ((destination: String) -> Unit)? = null
    var onNavUpdate: ((NavUpdate) -> Unit)? = null
    var onNavStop: (() -> Unit)? = null
    /** App-level hook fired on Nav_Start and every Nav_UpdateInfo (used to take the foreground). */
    var onNavAny: (() -> Unit)? = null
    /** Map image from the phone (Nav_Map_Data); mode "1" = route overview, "0" = follow-car. */
    var onNavMap: ((mode: String, png: ByteArray) -> Unit)? = null

    var weather: RokidWeather? = null; private set
    var schedule: List<RokidSchedule> = emptyList(); private set
    var navActive = false; private set

    var phoneLinked = false; private set
    val messages = ArrayDeque<PhoneMessage>()

    private var server: IBinder? = null
    private var pending: String? = null
    private val main = Handler(Looper.getMainLooper())

    private val client = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code in 1..16777215) data.enforceInterface(CLIENT_DESCRIPTOR)
            when (code) {
                1 -> { reply?.writeNoException(); Log.d(TAG, "registered with assist server") }
                2 -> {
                    if (data.readInt() != 0) {           // typed object present
                        data.readLong(); data.readString(); data.readString(); data.readLong()
                        val json = data.readString()
                        json?.let { main.post { handleMessage(it) } }
                    }
                    reply?.writeNoException(); reply?.writeInt(1)
                }
                3 -> {
                    val key = data.readString(); val param = data.readString(); val b = data.createByteArray()
                    if (key == "Nav" && param != null) main.post { handleNav(param, b) }
                    reply?.writeNoException(); reply?.writeByteArray(b)
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            server = binder
            register()
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

    /** Send a navigation sub-command to the phone, e.g. sendNav("Nav_SetShowMode", {"mode":"0"}). */
    fun sendNav(subCmd: String, data: JSONObject) {
        val payload = JSONObject().put("key", "Nav").put("cmd", subCmd).put("data", data.toString())
        val msg = JSONObject().put("type", CMD_PHONE_GATT_SEND).put("data", payload).toString()
        if (server?.isBinderAlive == true) send(msg) else { pending = msg; bind() }
    }

    private fun register() {
        val binder = server ?: return
        val req = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            req.writeInterfaceToken(SERVER_DESCRIPTOR)
            req.writeString(context.packageName)
            req.writeStrongBinder(client)
            binder.transact(TRANSACTION_REGISTER_CLIENT, req, reply, 0)
            reply.readException()
        } catch (e: Exception) { Log.w(TAG, "registerClient failed: ${e.message}") }
        finally { req.recycle(); reply.recycle() }
        // ask for the current phone-app link state; later changes arrive as cmd_bluetooth_gatt_status
        send(JSONObject().put("type", CMD_GET_BLE_STATUS).toString())
    }

    private fun send(json: String) {
        val binder = server ?: return
        val req = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            req.writeInterfaceToken(SERVER_DESCRIPTOR)
            req.writeString(CALLER_PKG)
            req.writeString(json)
            binder.transact(TRANSACTION_CONTROL_MSG_JSON, req, reply, 0)
            reply.readException()
            Log.d(TAG, "sent $json")
        } catch (e: Exception) { Log.w(TAG, "controlMsgJson failed: ${e.message}") }
        finally { req.recycle(); reply.recycle() }
    }

    private fun handleMessage(json: String) {
        try {
            val o = JSONObject(json)
            when (o.optString("type")) {
                "cmd_bluetooth_gatt_status" -> {
                    val d = JSONObject(o.optString("data"))
                    phoneLinked = d.optBoolean("status")
                    onPhoneLink?.invoke(phoneLinked)
                }
                "cmd_bluetooth_gatt_normal_result" -> {
                    val d = JSONObject(o.optString("data"))
                    when (d.optString("cmd")) {
                        "Weather_SendData" -> {
                            val w = JSONObject(d.optString("caps1"))
                            weather = RokidWeather(w.optString("address"), w.optDouble("temp", 0.0).toFloat(),
                                w.optDouble("tempHigh", 0.0).toFloat(), w.optDouble("tempLow", 0.0).toFloat(),
                                w.optInt("weatherId", 0), System.currentTimeMillis())
                            weather?.let { onWeather?.invoke(it) }
                        }
                        "Ntf_ResetScheduleList" -> {
                            val arr = JSONArray(d.optString("caps1"))
                            schedule = (0 until arr.length()).map { arr.getJSONObject(it) }
                                .map { RokidSchedule(it.optString("title"), it.optString("description"), it.optLong("scheduleTime")) }
                            onSchedule?.invoke(schedule)
                        }
                        "Ntf_SendNewMsg" -> { addMessage(JSONObject(d.optString("caps1"))); onMessages?.invoke(messages.toList()) }
                        "Ntf_ResetMsgList" -> {
                            messages.clear()
                            val arr = JSONArray(d.optString("caps1"))
                            for (i in 0 until arr.length()) addMessage(arr.getJSONObject(i))
                            onMessages?.invoke(messages.toList())
                        }
                    }
                }
                "cmd_bluetooth_status", "cmd_bluetooth_phone_status" -> Log.d(TAG, "${o.optString("type")} ${o.optString("data").take(300)}")
                else -> Log.v(TAG, "msg ${o.optString("type")}")
            }
        } catch (e: Exception) { Log.w(TAG, "bad message: ${e.message} :: ${json.take(200)}") }
    }

    /** Nav stream: param = {"subCmd": "Nav_Start|Nav_UpdateInfo|Nav_Stop", "data": json}, bytes = turn icon PNG. */
    private fun handleNav(param: String, bytes: ByteArray?) {
        try {
            val p = JSONObject(param)
            val d = p.optString("data")
            when (p.optString("subCmd")) {
                "Nav_Start" -> { navActive = true; onNavAny?.invoke(); onNavStart?.invoke(JSONObject(d).optString("destination")) }
                "Nav_Stop" -> { navActive = false; onNavStop?.invoke() }
                "Nav_Map_Data" -> {
                    if (bytes != null && bytes.size > 16) onNavMap?.invoke(JSONObject(d).optString("mode", "0"), bytes)
                }
                "Nav_UpdateInfo" -> {
                    navActive = true
                    onNavAny?.invoke()
                    val u = JSONObject(d)
                    onNavUpdate?.invoke(NavUpdate(
                        iconType = u.optInt("iconType"), iconPng = bytes?.takeIf { it.size > 16 },
                        nextRoadName = u.optString("nextRoadName"), curRoadName = u.optString("curRoadName"),
                        stepRemainM = u.optInt("curStepRetainDis"), routeRemainM = u.optInt("routeRemainDis"),
                        routeRemainS = u.optInt("routeRemainTime"), speedKmh = u.optInt("currentSpeed")))
                }
                else -> Log.v(TAG, "nav ${p.optString("subCmd")}")
            }
        } catch (e: Exception) { Log.w(TAG, "bad nav data: ${e.message}") }
    }

    private fun addMessage(m: JSONObject) {
        messages.addLast(PhoneMessage(m.optString("appName"), m.optString("titleValue"), m.optString("msgValue"), m.optLong("msgTime")))
        while (messages.size > MAX_MESSAGES) messages.removeFirst()
    }

    companion object {
        private const val TAG = "RokidScenes"
        const val ASSIST_PKG = "com.rokid.os.sprite.assistserver"
        const val ASSIST_SERVICE = "com.rokid.os.sprite.assist.MasterAssistService"
        private const val SERVER_DESCRIPTOR = "com.rokid.os.sprite.assist.server.IAssistServer"
        private const val CLIENT_DESCRIPTOR = "com.rokid.os.sprite.assist.client.IAssistClient"
        private const val TRANSACTION_REGISTER_CLIENT = 1
        private const val TRANSACTION_CONTROL_MSG_JSON = 3
        private const val CMD_OPEN_SCENE = "cmd_open_scene_with_ignore_tips"
        private const val CMD_GET_BLE_STATUS = "cmd_get_ble_status"
        private const val CMD_PHONE_GATT_SEND = "cmd_phone_gatt_send_data"
        /** Scene opening keys off the caller name; Rokid's launcher identifies as itself. */
        private const val CALLER_PKG = "com.rokid.os.sprite.launcher"
        private const val MAX_MESSAGES = 5

        const val SCENE_TRANSLATE = "translate"
        const val SCENE_TELEPROMPTER = "word_tips"
        const val SCENE_SUBTITLES = "accessibility"
        const val SCENE_VISION_AI = "ai_chat"
        const val SCENE_NAVIGATION = "navigation"
        const val SCENE_CAMERA = "camera_page"
        const val SCENE_AUDIO_RECORD = "audio_record"

        const val ROKID_LAUNCHER_PKG = "com.rokid.os.sprite.launcher"
        const val ACT_MUSIC = "com.rokid.os.sprite.launcher.page.music.MusicPageActivity"
        const val ACT_DEVICE_INFO = "com.rokid.os.sprite.launcher.setting.info.SettingSystemInfoActivity"
        const val ACT_HOME = "com.rokid.os.sprite.launcher.main.SpriteMainActivity"
    }
}
