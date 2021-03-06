package com.tutpro.baresip

import android.annotation.TargetApi
import android.app.*
import android.app.Notification.VISIBILITY_PUBLIC
import android.content.*
import android.media.*
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.support.annotation.Keep
import android.support.v4.app.NotificationCompat
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import android.support.v4.content.LocalBroadcastManager
import android.os.Build
import android.support.v4.app.NotificationCompat.VISIBILITY_PRIVATE
import android.support.v4.content.ContextCompat

import java.io.File
import java.nio.charset.StandardCharsets
import java.io.InputStream
import java.util.*

class BaresipService: Service() {

    private val LOG_TAG = "Baresip Service"
    internal lateinit var intent: Intent
    internal lateinit var am: AudioManager
    internal lateinit var rt: Ringtone
    internal lateinit var nm: NotificationManager
    internal lateinit var snb: NotificationCompat.Builder
    internal lateinit var nr: BroadcastReceiver
    internal lateinit var wl: PowerManager.WakeLock
    internal lateinit var fl: WifiManager.WifiLock

    internal var rtTimer: Timer? = null
    internal var filesPath = ""
    internal var audioFocusRequest: AudioFocusRequest? = null
    internal var audioFocused = false

    override fun onCreate() {

        Log.d(LOG_TAG, "At onCreate")

        intent = Intent("com.tutpro.baresip.EVENT")
        intent.setPackage("com.tutpro.baresip")

        filesPath = applicationContext.filesDir.absolutePath

        am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val rtUri = RingtoneManager.getActualDefaultRingtoneUri(applicationContext,
                RingtoneManager.TYPE_RINGTONE)
        rt = RingtoneManager.getRingtone(applicationContext, rtUri)

        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()
        snb = NotificationCompat.Builder(this, DEFAULT_CHANNEL_ID)

        nr = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ConnectivityManager.CONNECTIVITY_ACTION) {
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val activeNetwork: NetworkInfo? = cm.activeNetworkInfo
                    val isConnected: Boolean = activeNetwork?.isConnectedOrConnecting == true
                    if (isConnected) {
                        Log.d(LOG_TAG, "Network is connected/connecting")
                        if (disconnected) {
                            UserAgent.register()
                            disconnected = false
                        }
                    } else {
                        Log.d(LOG_TAG, "Network is NOT connected/connecting")
                        disconnected = true
                    }
                }
            }
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.tutpro.baresip:wakelog")

        /* if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent()
            intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } */

        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        val action: String

        if (intent == null) {
            action = "Start"
            Log.d(LOG_TAG, "Received onStartCommand with null intent")
        } else {
            action = intent.getAction()
            Log.d(LOG_TAG, "Received onStartCommand action $action")
        }

        when (action) {

            "Start" -> {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                fl = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Baresip")

                val assets = arrayOf("accounts", "contacts", "config", "busy.wav", "callwaiting.wav",
                        "error.wav", "notfound.wav", "ring.wav", "ringback.wav")
                var file = File(filesPath)
                if (!file.exists()) {
                    Log.d(LOG_TAG, "Creating baresip directory")
                    try {
                        File(filesPath).mkdirs()
                    } catch (e: Error) {
                        Log.e(LOG_TAG, "Failed to create directory: " + e.toString())
                    }
                }
                for (a in assets) {
                    file = File("${filesPath}/$a")
                    if (!file.exists()) {
                        Log.d(LOG_TAG, "Copying asset $a")
                        Utils.copyAssetToFile(applicationContext, a, "$filesPath/$a")
                    } else {
                        Log.d(LOG_TAG, "Asset $a already copied")
                        if (a == "config") {
                            val inputStream: InputStream = file.inputStream()
                            var contents = inputStream.bufferedReader().use { it.readText() }
                            inputStream.close()
                            var write = false
                            if (!contents.contains("zrtp_hash")) {
                                contents = "${contents}zrtp_hash yes\n"
                                write = true
                            }
                            if (contents.contains(Regex("#module_app[ ]+mwi.so"))) {
                                contents = contents.replace(Regex("#module_app[ ]+mwi.so"),
                                        "module_app mwi.so")
                                write = true
                            }
                            if (!contents.contains("opus_application")) {
                                contents = "${contents}opus_application voip\n"
                                write = true
                            }
                            if (write) {
                                Log.d(LOG_TAG, "Writing $contents")
                                Utils.putFileContents(file, contents)
                            }
                        }
                    }
                }
                ContactsActivity.restoreContacts(applicationContext.filesDir)
                wl.acquire()
                Thread(Runnable { baresipStart(filesPath) }).start()
                BaresipService.isServiceRunning = true
                registerReceiver(nr, IntentFilter("android.net.conn.CONNECTIVITY_CHANGE"))
                showStatusNotification()
            }

            "UpdateNotification" -> {
                updateStatusNotification()
            }

            "ToggleSpeaker" -> {
                Log.d(LOG_TAG, "Toggling speakerphone from $speakerPhone")
                am.isSpeakerphoneOn = !am.isSpeakerphoneOn
                speakerPhone = am.isSpeakerphoneOn
            }

            "Stop", "Stop Force" -> {
                if (!isServiceClean) cleanService()
                if (isServiceRunning) baresipStop(action == "Stop Force")
            }

            "Kill" -> {
                if (!isServiceClean) cleanService()
                isServiceRunning = false
                stopForeground(true)
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        Log.d(LOG_TAG, "In onDestroy restart baresip killed by Android")
        super.onDestroy()
        cleanService()
        val broadcastIntent = Intent("com.tutpro.baresip.Restart")
        sendBroadcast(broadcastIntent)
    }

    @Keep
    fun uaAdd(uap: String) {
        Log.d(LOG_TAG, "uaAdd at BaresipService")
        val ua = UserAgent(uap)
        uas.add(ua)
        if (Api.ua_isregistered(uap)) {
            Log.d(LOG_TAG, "Ua ${ua.account.aor} is registered")
            status.add(R.drawable.dot_green)
        } else {
            Log.d(LOG_TAG, "Ua ${ua.account.aor} is NOT registered")
            status.add(R.drawable.dot_yellow)
        }
        val intent = Intent("service event")
        intent.putExtra("event", "ua added")
        intent.putExtra("params", arrayListOf(uap))
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        updateStatusNotification()
    }

    @Keep
    fun uaEvent(event: String, uap: String, callp: String) {
        Log.d(LOG_TAG, "uaEvent got event $event/$uap/$callp")
        if (!isServiceRunning) return
        val ua = UserAgent.find(uap)
        if (ua == null) {
            Log.w(LOG_TAG, "uaEvent did not find ua $uap")
            return
        }
        val aor = ua.account.aor
        var newEvent: String? = null
        val ev = event.split(",")
        for (account_index in uas.indices) {
            if (uas[account_index].account.aor == aor) {
                when (ev[0]) {
                    "registering" -> {
                        return
                    }
                    "registered" -> {
                        if (ua.account.regint == 0)
                            status[account_index] = R.drawable.dot_yellow
                        else
                            status[account_index] = R.drawable.dot_green
                        updateStatusNotification()
                    }
                    "registering failed" -> {
                        status[account_index] = R.drawable.dot_red
                        updateStatusNotification()
                    }
                    "unregistering" -> {
                        status[account_index] = R.drawable.dot_yellow
                        updateStatusNotification()
                    }
                    "call incoming" -> {
                        val peerUri = Api.call_peeruri(callp)
                        if (Call.calls().size > 0) {
                            Log.i(LOG_TAG, "Auto-rejecting incoming call $uap/$callp/$peerUri")
                            Api.ua_hangup(uap, callp, 486, "Busy Here")
                            CallHistory.add(CallHistory(aor, peerUri, "in", false))
                            CallHistory.save(filesPath)
                            ua.account.missedCalls = true
                            newEvent = "call rejected"
                        } else {
                            Log.d(LOG_TAG, "Incoming call $uap/$callp/$peerUri")
                            calls.add(Call(callp, ua, peerUri, "in", "incoming",
                                    Utils.dtmfWatcher(callp)))
                            startRinging()
                        }
                        if ((newEvent == null) && !Utils.isVisible()) {
                            val intent = Intent(this, MainActivity::class.java)
                                    .setAction(Intent.ACTION_MAIN)
                                    .addCategory(Intent.CATEGORY_LAUNCHER)
                            val pi = PendingIntent.getActivity(this, CALL_REQ_CODE, intent,
                                    0)
                            val nb = NotificationCompat.Builder(this, HIGH_CHANNEL_ID)
                            val caller = Utils.friendlyUri(ContactsActivity.contactName(peerUri),
                                    Utils.aorDomain(aor))
                            val title = "Incoming call from $caller"
                            nb.setSmallIcon(R.drawable.ic_stat)
                                    .setColor(ContextCompat.getColor(this,
                                            R.color.colorBaresip))
                                    .setContentIntent(pi)
                                    .setAutoCancel(true)
                                    .setContentTitle(title)
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                                nb.setVibrate(LongArray(0))
                                        .setVisibility(VISIBILITY_PRIVATE)
                                        .setPriority(Notification.PRIORITY_HIGH)
                            }
                            val answerIntent = Intent(this, MainActivity::class.java)
                            answerIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            answerIntent.putExtra("action", "answer")
                            answerIntent.putExtra("callp", callp)
                            val answerPendingIntent = PendingIntent.getActivity(this,
                                    ANSWER_REQ_CODE, answerIntent, PendingIntent.FLAG_UPDATE_CURRENT)
                            val rejectIntent = Intent(this, MainActivity::class.java)
                            rejectIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            rejectIntent.putExtra("action", "reject")
                            rejectIntent.putExtra("callp", callp)
                            val rejectPendingIntent = PendingIntent.getActivity(this,
                                    REJECT_REQ_CODE, rejectIntent, PendingIntent.FLAG_UPDATE_CURRENT)
                            nb.addAction(R.drawable.ic_stat, "Answer", answerPendingIntent)
                            nb.addAction(R.drawable.ic_stat, "Reject", rejectPendingIntent)
                            nm.notify(CALL_NOTIFICATION_ID, nb.build())
                        }
                    }
                    "call established" -> {
                        nm.cancel(CALL_NOTIFICATION_ID)
                        val call = Call.find(callp)
                        if (call == null) {
                            Log.w(LOG_TAG, "Established AoR $aor call $callp is not found")
                            return
                        }
                        Log.d(LOG_TAG, "AoR $aor call $callp established")
                        call.status = "connected"
                        call.onhold = false
                        CallHistory.add(CallHistory(aor, call.peerURI, call.dir, true))
                        CallHistory.save(filesPath)
                        call.hasHistory = true
                        stopRinging()
                        am.mode = AudioManager.MODE_IN_COMMUNICATION
                        requestAudioFocus(AudioManager.STREAM_VOICE_CALL)
                        am.isSpeakerphoneOn = false
                    }
                    "call transfer" -> {
                        val call = Call.find(callp)
                        if (call == null) {
                            Log.d(LOG_TAG, "AoR $aor call $callp to be transferred is not found")
                            return
                        }
                        if (!Utils.isVisible()) {
                            val intent = Intent(this, MainActivity::class.java)
                                    .setAction(Intent.ACTION_MAIN)
                                    .addCategory(Intent.CATEGORY_LAUNCHER)
                            val pi = PendingIntent.getActivity(this, TRANSFER_REQ_CODE,
                                    intent, 0)
                            val nb = NotificationCompat.Builder(this, HIGH_CHANNEL_ID)
                            val target = Utils.friendlyUri(ContactsActivity.contactName(ev[1]),
                                    Utils.aorDomain(aor))
                            val title = "Call transfer request to $target"
                            nb.setSmallIcon(R.drawable.ic_stat)
                                    .setColor(ContextCompat.getColor(this,
                                            R.color.colorBaresip))
                                    .setContentIntent(pi)
                                    .setDefaults(Notification.DEFAULT_SOUND)
                                    .setAutoCancel(true)
                                    .setContentTitle(title)
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                                nb.setVibrate(LongArray(0))
                                        .setVisibility(VISIBILITY_PRIVATE)
                                        .setPriority(Notification.PRIORITY_HIGH)
                            }
                            val acceptIntent = Intent(this, MainActivity::class.java)
                            acceptIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            acceptIntent.putExtra("action", "transfer")
                            acceptIntent.putExtra("uap", uap)
                            acceptIntent.putExtra("callp", callp)
                            acceptIntent.putExtra("uri", ev[1])
                            val acceptPendingIntent = PendingIntent.getActivity(this,
                                    ACCEPT_REQ_CODE, acceptIntent, PendingIntent.FLAG_UPDATE_CURRENT)
                            val denyIntent = Intent(this, MainActivity::class.java)
                            denyIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            denyIntent.putExtra("action", "deny")
                            denyIntent.putExtra("callp", callp)
                            val denyPendingIntent = PendingIntent.getActivity(this,
                                    DENY_REQ_CODE, denyIntent, PendingIntent.FLAG_UPDATE_CURRENT)
                            nb.addAction(R.drawable.ic_stat, "Accept", acceptPendingIntent)
                            nb.addAction(R.drawable.ic_stat, "Deny", denyPendingIntent)
                            nm.notify(CALL_NOTIFICATION_ID, nb.build())
                            return
                        }
                    }
                    "call closed" -> {
                        nm.cancel(CALL_NOTIFICATION_ID)
                        val call = Call.find(callp)
                        if (call == null) {
                            Log.d(LOG_TAG, "AoR $aor call $callp that is closed is not found")
                            return
                        }
                        Log.d(LOG_TAG, "AoR $aor call $callp is closed")
                        stopRinging()
                        calls.remove(call)
                        if (!call.hasHistory) {
                            CallHistory.add(CallHistory(aor, call.peerURI, call.dir, false))
                            CallHistory.save(filesPath)
                            if (call.dir == "in") ua.account.missedCalls = true
                        }
                        if (Call.calls().size == 0) {
                            am.mode = AudioManager.MODE_NORMAL
                            if (am.isSpeakerphoneOn) am.isSpeakerphoneOn = false
                            if (audioFocused) abandonAudioFocus()
                        }
                    }
                    "transfer failed" -> {
                        Log.d(LOG_TAG, "AoR $aor hanging up call $callp with ${ev[1]}")
                        Api.ua_hangup(uap, callp, 0, "")
                        return
                    }
                }
            }
        }
        if (newEvent == null) newEvent = event
        val intent = Intent("service event")
        intent.putExtra("event", newEvent)
        intent.putExtra("params", arrayListOf(uap, callp))
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    @Keep
    fun messageEvent(uap: String, peer: String, msg: ByteArray) {
        var s = "Decoding of message failed!"
        try {
            s = String(msg, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "UTF-8 decode failed")
        }
        Log.d(LOG_TAG, "Message event for $uap from $peer")
        val ua = UserAgent.find(uap)
        if (ua == null) {
            Log.w(LOG_TAG, "messageEvent did not find ua $uap")
            return
        }
        val timeStamp = System.currentTimeMillis().toString()
        if (!Utils.isVisible()) {
            val intent = Intent(this, MainActivity::class.java)
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("action", "show")
                    .putExtra("uap", uap)
                    .putExtra("peer", peer)
            val pi = PendingIntent.getActivity(this, MESSAGE_REQ_CODE, intent, 0)
            val nb = NotificationCompat.Builder(this, HIGH_CHANNEL_ID)
            val sender = Utils.friendlyUri(ContactsActivity.contactName(peer),
                    Utils.aorDomain(ua.account.aor))
            nb.setSmallIcon(R.drawable.ic_stat)
                    .setColor(ContextCompat.getColor(this, R.color.colorBaresip))
                    .setContentIntent(pi)
                    .setDefaults(Notification.DEFAULT_SOUND)
                    .setAutoCancel(true)
                    .setContentTitle("Message from $sender")
                    .setContentText(s)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                nb.setVibrate(LongArray(0))
                        .setVisibility(VISIBILITY_PRIVATE)
                        .setPriority(Notification.PRIORITY_HIGH)
            }
            val replyIntent = Intent(this, MainActivity::class.java)
            replyIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            replyIntent.putExtra("action", "reply")
            replyIntent.putExtra("uap", uap)
            replyIntent.putExtra("peer", peer)
            val replyPendingIntent = PendingIntent.getActivity(this,
                    REPLY_REQ_CODE, replyIntent, PendingIntent.FLAG_UPDATE_CURRENT)
            val saveIntent = Intent(this, MainActivity::class.java)
            saveIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP)
            saveIntent.putExtra("action", "save")
            saveIntent.putExtra("uap", uap)
            saveIntent.putExtra("time", timeStamp)
            val savePendingIntent = PendingIntent.getActivity(this,
                    SAVE_REQ_CODE, saveIntent, PendingIntent.FLAG_UPDATE_CURRENT)
            val deleteIntent = Intent(this, MainActivity::class.java)
            deleteIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP)
            deleteIntent.putExtra("action", "delete")
            deleteIntent.putExtra("uap", uap)
            deleteIntent.putExtra("time", timeStamp)
            val deletePendingIntent = PendingIntent.getActivity(this,
                    DELETE_REQ_CODE, deleteIntent, PendingIntent.FLAG_UPDATE_CURRENT)
            nb.addAction(R.drawable.ic_stat, "Reply", replyPendingIntent)
            nb.addAction(R.drawable.ic_stat, "Save", savePendingIntent)
            nb.addAction(R.drawable.ic_stat, "Delete", deletePendingIntent)
            nm.notify(MESSAGE_NOTIFICATION_ID, nb.build())
        }
        val intent = Intent("service event")
        intent.putExtra("event", "message")
        intent.putExtra("params", arrayListOf(uap, peer, s, timeStamp))
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    @Keep
    fun messageResponse(responseCode: Int, responseReason: String, time: String) {
        Log.d(LOG_TAG, "Message response '$responseCode $responseReason' at $time")
        val intent = Intent("message response")
        intent.putExtra("response code", responseCode)
        intent.putExtra("response reason", responseReason)
        intent.putExtra("time", time)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    @Keep
    fun stopped() {
        Log.d(LOG_TAG, "'stopped' from baresip")
        isServiceRunning = false
        val intent = Intent("service event")
        intent.putExtra("event", "stopped")
        intent.putExtra("params", arrayListOf<String>())
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        stopForeground(true)
        stopSelf()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val defaultChannel = NotificationChannel(DEFAULT_CHANNEL_ID, "Default",
                    NotificationManager.IMPORTANCE_LOW)
            defaultChannel.description = "Tells that baresip is running"
            defaultChannel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(defaultChannel)
            val highChannel = NotificationChannel(HIGH_CHANNEL_ID, "High",
                    NotificationManager.IMPORTANCE_HIGH)
            highChannel.description = "Tells about incoming call or message"
            highChannel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            highChannel.enableVibration(true)
            nm.createNotificationChannel(highChannel)
        }
    }

    private fun showStatusNotification() {
        val intent = Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
        val pi = PendingIntent.getActivity(this, STATUS_REQ_CODE, intent, 0)
        snb.setVisibility(VISIBILITY_PUBLIC)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentIntent(pi)
                .setOngoing(true)
                .setContent(RemoteViews(packageName, R.layout.status_notification))
        startForeground(STATUS_NOTIFICATION_ID, snb.build())
    }

    private fun updateStatusNotification() {
        val contentView = RemoteViews(getPackageName(), R.layout.status_notification)
        for (i: Int in 0..5) {
            val resID = resources.getIdentifier("status$i", "id", packageName)
            if (i < status.size) {
                contentView.setImageViewResource(resID, status[i])
                contentView.setViewVisibility(resID, View.VISIBLE)
            } else {
                contentView.setViewVisibility(resID, View.INVISIBLE)
            }
        }
        if (status.size > 4)
            contentView.setViewVisibility(R.id.etc, View.VISIBLE)
        else
            contentView.setViewVisibility(R.id.etc, View.INVISIBLE)
        snb.setContent(contentView)
        nm.notify(STATUS_NOTIFICATION_ID, snb.build())
    }

    private fun requestAudioFocus(streamType: Int) {
        val res: Int
        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) && (audioFocusRequest == null)) {
            @TargetApi(26)
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).run {
                setAudioAttributes(AudioAttributes.Builder().run {
                    setLegacyStreamType(streamType)
                    build()
                })
                build()
            }
            @TargetApi(26)
            res = am.requestAudioFocus(audioFocusRequest)
            if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.d(LOG_TAG, "Audio focus granted")
            } else {
                Log.d(LOG_TAG, "Audio focus denied")
                audioFocusRequest = null
            }
        } else {
            res = am.requestAudioFocus(null, streamType, AudioManager.AUDIOFOCUS_GAIN)
            if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.d(LOG_TAG, "Audio focus granted")
                audioFocused = true
            } else {
                Log.d(LOG_TAG, "Audio focus denied")
                audioFocused = false
            }
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest != null) {
                am.abandonAudioFocusRequest(audioFocusRequest)
                audioFocusRequest = null
            }
        } else {
            if (audioFocused) {
                am.abandonAudioFocus(null)
                audioFocused = false
            }
        }
    }

    private fun startRinging() {
        am.mode = AudioManager.MODE_RINGTONE
        requestAudioFocus(AudioManager.STREAM_RING)
        rt.play()
        rtTimer = Timer()
        rtTimer!!.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (!rt.isPlaying()) {
                    rt.play()
                }
            }
        }, 1000 * 1, 1000 * 1)
    }

    private fun stopRinging() {
        if (rtTimer != null) {
            rtTimer!!.cancel()
            rtTimer = null
            if (rt.isPlaying) rt.stop()
            abandonAudioFocus()
        }
    }

    private fun cleanService() {
        uas.clear()
        status.clear()
        history.clear()
        messages.clear()
        try {
            unregisterReceiver(nr)
        } catch (e: Exception) {
            Log.d(LOG_TAG, "Receiver nr has not been registered")
        }
        if (this::nm.isInitialized) nm.cancelAll()
        if (this::wl.isInitialized && wl.isHeld) wl.release()
        if (this::fl.isInitialized && fl.isHeld) fl.release()
        isServiceClean = true
    }

    external fun baresipStart(path: String)
    external fun baresipStop(force: Boolean)

    companion object {

        val STATUS_NOTIFICATION_ID = 101
        val CALL_NOTIFICATION_ID = 102
        val MESSAGE_NOTIFICATION_ID = 103

        val STATUS_REQ_CODE = 1
        val CALL_REQ_CODE = 2
        val ANSWER_REQ_CODE = 3
        val REJECT_REQ_CODE = 4
        val TRANSFER_REQ_CODE = 5
        val ACCEPT_REQ_CODE = 6
        val DENY_REQ_CODE = 7
        val MESSAGE_REQ_CODE = 8
        val REPLY_REQ_CODE = 9
        val SAVE_REQ_CODE = 10
        val DELETE_REQ_CODE = 11

        val DEFAULT_CHANNEL_ID = "com.tutpro.baresip.default"
        val HIGH_CHANNEL_ID = "com.tutpro.baresip.high"

        var isServiceRunning = false
        var disconnected = false
        var libraryLoaded = false
        var isServiceClean = false

        var uas = ArrayList<UserAgent>()
        var status = ArrayList<Int>()
        var calls = ArrayList<Call>()
        var history = ArrayList<CallHistory>()
        var messages = ArrayList<Message>()
        var contacts = ArrayList<Contact>()
        var chatTexts: MutableMap<String, String> = mutableMapOf<String, String>()

        var speakerPhone = false

    }

    init {
        if (!libraryLoaded) {
            Log.d(LOG_TAG, "Loading baresip library")
            System.loadLibrary("baresip")
            libraryLoaded = true
        }
    }
}
