package com.limradigitals.realcaller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.InCallService
import androidx.core.app.NotificationCompat
import com.limradigitals.realcaller.data.CallerRepository
import com.limradigitals.realcaller.data.ReputationLevel

class InCallServiceImpl : InCallService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var callerRepository: CallerRepository
    private val managedCalls = LinkedHashSet<Call>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        callerRepository = CallerRepository(applicationContext)
        createChannelsForSettings()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        managedCalls.clear()
        if (instance === this) instance = null
        currentCall = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        managedCalls.add(call)
        call.registerCallback(callback)
        selectForegroundCall()
        val number = call.details.handle?.schemeSpecificPart.orEmpty()
        if (number.isNotBlank()) callerRepository.recordIncomingCall(number)
        showCallNotification(call)
        launchCallUi()
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        managedCalls.remove(call)
        selectForegroundCall()
        if (managedCalls.isEmpty()) {
            currentCall = null
            getSystemService(NotificationManager::class.java).cancel(CALL_NOTIFICATION_ID)
        } else {
            currentCall?.let { showCallNotification(it) }
        }
    }

    override fun onBringToForeground(showDialpad: Boolean) {
        super.onBringToForeground(showDialpad)
        selectForegroundCall()
        launchCallUi()
    }

    /** Returns all live calls currently exposed by Telecom to SmartCaller. */
    fun getManagedCalls(): List<Call> = try {
        getCalls().filter { it.state != Call.STATE_DISCONNECTED }
    } catch (_: Exception) {
        managedCalls.filter { it.state != Call.STATE_DISCONNECTED }.toList()
    }

    /** Silences the current incoming ringtone without rejecting the call. */
    fun silenceRinger() {
        try {
            val audioManager = getSystemService(AudioManager::class.java) ?: return
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
            if (currentVolume <= 0) return
            audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
            // Restore the user's previous ring volume shortly after the ignore action.
            mainHandler.postDelayed({
                try {
                    if (audioManager.getStreamVolume(AudioManager.STREAM_RING) == 0) {
                        audioManager.setStreamVolume(AudioManager.STREAM_RING, currentVolume, 0)
                    }
                } catch (_: Exception) { }
            }, 2000L)
        } catch (_: Exception) { }
    }

    fun answerCall(call: Call) {
        try { call.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY) } catch (_: Exception) { }
    }

    fun disconnectCall(call: Call) {
        try { call.disconnect() } catch (_: Exception) { }
    }

    fun holdCall(call: Call) {
        try {
            if ((call.details.callCapabilities and Call.Details.CAPABILITY_HOLD) != 0) call.hold()
        } catch (_: Exception) { }
    }

    fun unholdCall(call: Call) {
        try { call.unhold() } catch (_: Exception) { }
    }

    /** Swaps an active call with a held call using Telecom's managed call controls. */
    fun swapCalls(active: Call, held: Call) {
        try {
            if ((active.details.callCapabilities and Call.Details.CAPABILITY_HOLD) != 0) active.hold()
            held.unhold()
        } catch (_: Exception) { }
    }

    /** Requests Telecom to merge two compatible calls into a carrier-managed conference. */
    fun conferenceCalls(first: Call, second: Call) {
        try {
            if (first.conferenceableCalls.contains(second)) {
                first.conference(second)
            } else if (second.conferenceableCalls.contains(first)) {
                second.conference(first)
            }
        } catch (_: Exception) { }
    }

    fun splitConference(call: Call) {
        try { call.splitFromConference() } catch (_: Exception) { }
    }

    fun mergeConference(call: Call) {
        try {
            if ((call.details.callCapabilities and Call.Details.CAPABILITY_MERGE_CONFERENCE) != 0) call.mergeConference()
        } catch (_: Exception) { }
    }

    fun swapConference(call: Call) {
        try {
            if ((call.details.callCapabilities and Call.Details.CAPABILITY_SWAP_CONFERENCE) != 0) call.swapConference()
        } catch (_: Exception) { }
    }

    private fun selectForegroundCall() {
        val live = try { getCalls().filter { it.state != Call.STATE_DISCONNECTED } } catch (_: Exception) { managedCalls.filter { it.state != Call.STATE_DISCONNECTED }.toList() }
        managedCalls.retainAll(live.toSet())
        managedCalls.addAll(live)
        currentCall = live.firstOrNull { it.state == Call.STATE_RINGING }
            ?: live.firstOrNull { it.state == Call.STATE_ACTIVE }
            ?: live.firstOrNull { it.state == Call.STATE_DIALING }
            ?: live.firstOrNull { it.state == Call.STATE_CONNECTING }
            ?: live.firstOrNull { it.state == Call.STATE_HOLDING }
            ?: live.firstOrNull()
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            selectForegroundCall()
            showCallNotification(call)
            if (state == Call.STATE_RINGING || state == Call.STATE_DIALING || state == Call.STATE_CONNECTING || state == Call.STATE_ACTIVE || state == Call.STATE_HOLDING) {
                launchCallUi()
            }
        }
    }

    private fun launchCallUi() {
        mainHandler.post {
            if (getManagedCalls().isEmpty()) return@post
            val intent = Intent(this, CallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            try { startActivity(intent) } catch (_: Exception) { }
        }
    }

    fun createChannelsForSettings() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val selected = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
            .getString(SettingsActivity.KEY_RINGTONE, null)
            ?.let { Uri.parse(it) }
            ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)

        // v3 is intentional: Android locks a notification channel's sound after it is created.
        val incoming = NotificationChannel(INCOMING_CHANNEL_ID, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Incoming SmartCaller calls and ringtone"
            setSound(selected, audio)
            enableVibration(true)
            setVibrationPattern(longArrayOf(0, 350, 180, 350))
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val ongoing = NotificationChannel(ONGOING_CHANNEL_ID, "Ongoing calls", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannels(listOf(incoming, ongoing))
    }

    private fun showCallNotification(call: Call) {
        val handle = call.details.handle?.schemeSpecificPart.orEmpty()
        val contactName = call.details.contactDisplayName?.takeIf { it.isNotBlank() } ?: lookupContactName(handle)
        val record = if (handle.isNotBlank()) callerRepository.lookup(handle) else null
        val isSpam = record?.level == ReputationLevel.SPAM || record?.level == ReputationLevel.SCAM
        val displayName = contactName ?: record?.displayName ?: handle.ifBlank { "Unknown caller" }
        val ringing = call.state == Call.STATE_RINGING
        val channel = if (ringing) INCOMING_CHANNEL_ID else ONGOING_CHANNEL_ID
        val intent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(this, 700, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, channel)
            .setSmallIcon(com.limradigitals.realcaller.R.drawable.ic_smartcaller)
            .setContentTitle(
                when {
                    ringing && isSpam -> "⚠ Spam call · $displayName"
                    ringing -> "Incoming call · $displayName"
                    else -> "SmartCaller · $displayName"
                }
            )
            .setContentText(handle.ifBlank { "Phone call" })
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(if (ringing) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)
            .setOngoing(ringing || call.state == Call.STATE_ACTIVE)
            .setAutoCancel(!ringing)
            .setContentIntent(pending)

        if (ringing) {
            val answerIntent = Intent(this, CallActionReceiver::class.java).apply { action = CallActionReceiver.ACTION_ANSWER }
            val ignoreIntent = Intent(this, CallActionReceiver::class.java).apply { action = CallActionReceiver.ACTION_IGNORE }
            val rejectIntent = Intent(this, CallActionReceiver::class.java).apply { action = CallActionReceiver.ACTION_REJECT }
            val answerPending = PendingIntent.getBroadcast(this, 701, answerIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val ignorePending = PendingIntent.getBroadcast(this, 703, ignoreIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val rejectPending = PendingIntent.getBroadcast(this, 702, rejectIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (isSpam) {
                builder.addAction(0, "Accept", answerPending)
                    .addAction(0, "Ignore", ignorePending)
                    .addAction(0, "Decline", rejectPending)
            } else {
                builder.addAction(0, "Answer", answerPending)
                    .addAction(0, "Decline", rejectPending)
            }
            builder.setFullScreenIntent(pending, true)
        }
        getSystemService(NotificationManager::class.java).notify(CALL_NOTIFICATION_ID, builder.build())
    }

    private fun lookupContactName(number: String): String? {
        if (number.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (_: SecurityException) { null } catch (_: Exception) { null }
    }

    companion object {
        const val INCOMING_CHANNEL_ID = "smartcaller_incoming_v3"
        const val ONGOING_CHANNEL_ID = "smartcaller_ongoing_v2"
        const val CALL_CHANNEL_ID = ONGOING_CHANNEL_ID
        const val CALL_NOTIFICATION_ID = 9001
        @Volatile var currentCall: Call? = null
            private set
        @Volatile var instance: InCallServiceImpl? = null
            private set
    }
}
