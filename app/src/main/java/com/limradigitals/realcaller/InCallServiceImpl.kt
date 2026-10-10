package com.limradigitals.realcaller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.Call
import android.telecom.InCallService
import androidx.core.app.NotificationCompat
import com.limradigitals.realcaller.data.CallerRepository
import com.limradigitals.realcaller.data.ReputationLevel

class InCallServiceImpl : InCallService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var callerRepository: CallerRepository
    private val managedCalls = LinkedHashSet<Call>()
    private var incomingRingtone: Ringtone? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        callerRepository = CallerRepository(applicationContext)
        createChannelsForSettings()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        stopIncomingRingtone()
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
        val direction = if (call.state == Call.STATE_RINGING) "INCOMING" else "OUTGOING_OR_EXISTING"
        CallDiagnostics.markCallAdded(this, number, call.state, direction)
        if (number.isNotBlank()) callerRepository.recordIncomingCall(number)
        showCallNotification(call)
        // Contact data/permissions may become available just after Telecom reports the call.
        // Refresh the notification shortly afterwards so the saved name replaces the number.
        mainHandler.postDelayed({ if (managedCalls.contains(call)) showCallNotification(call) }, 700L)
        mainHandler.postDelayed({ if (managedCalls.contains(call)) showCallNotification(call) }, 1700L)
        if (call.state == Call.STATE_RINGING) startIncomingRingtone()
        launchCallUi()
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        managedCalls.remove(call)
        if (managedCalls.none { it.state == Call.STATE_RINGING }) stopIncomingRingtone()
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
        // Do not change the user's global ring volume. Stopping the active ringtone
        // gives the same "mute this call" behavior as the hardware volume keys.
        stopIncomingRingtone()
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
            if (state == Call.STATE_RINGING) startIncomingRingtone() else if (managedCalls.none { it.state == Call.STATE_RINGING }) stopIncomingRingtone()
            val number = call.details.handle?.schemeSpecificPart.orEmpty()
            if (state == Call.STATE_DISCONNECTED) {
                val cause = call.details.disconnectCause
                CallDiagnostics.markDisconnected(this@InCallServiceImpl, number, cause.code, cause.label?.toString(), cause.description?.toString())
            }
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

    private fun startIncomingRingtone() {
        try {
            if (incomingRingtone?.isPlaying == true) return
            val uri = resolveIncomingRingtoneUri()
            val ringtone = RingtoneManager.getRingtone(this, uri) ?: return
            ringtone.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            if (Build.VERSION.SDK_INT >= 28) ringtone.isLooping = true
            incomingRingtone = ringtone
            ringtone.play()
        } catch (_: Exception) {
            incomingRingtone = null
        }
    }

    /**
     * Resolves the ringtone for the SIM that owns this incoming call.
     * Many OEM dual-SIM builds expose SIM 2's ringtone as Settings.System
     * "ringtone_2"; AOSP's normal RingtoneManager API is global and can otherwise
     * make both SIMs use SIM 1's tone.
     */
    private fun resolveIncomingRingtoneUri(): Uri {
        val call = currentCall
        val handle = call?.details?.accountHandle
        val subId = handle?.id?.toIntOrNull()
        val slot = if (subId != null) resolveSlotForSubscription(subId) else -1

        if (slot >= 0) {
            val systemKey = if (slot == 0) Settings.System.RINGTONE else if (slot == 1) "ringtone_2" else null
            if (systemKey != null) {
                try {
                    val systemUri = Settings.System.getString(contentResolver, systemKey)
                        ?.takeIf { it.isNotBlank() }
                        ?.let(Uri::parse)
                    if (systemUri != null) return systemUri
                } catch (_: Exception) { }
            }
        }

        return getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
            .getString(SettingsActivity.KEY_RINGTONE, null)
            ?.let { Uri.parse(it) }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    }

    private fun resolveSlotForSubscription(subId: Int): Int {
        return try {
            val sm = getSystemService(android.telephony.SubscriptionManager::class.java)
            sm?.getActiveSubscriptionInfo(subId)?.simSlotIndex ?: -1
        } catch (_: SecurityException) {
            -1
        } catch (_: Exception) {
            -1
        }
    }

    private fun stopIncomingRingtone() {
        try { incomingRingtone?.stop() } catch (_: Exception) { }
        incomingRingtone = null
    }

    fun createChannelsForSettings() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        // SmartCaller plays the selected ringtone directly through RingtoneManager.
        // The notification channel remains high-importance for heads-up/vibration but is silent,
        // preventing a second notification sound from playing alongside the call ringtone.
        val incoming = NotificationChannel(INCOMING_CHANNEL_ID, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Incoming SmartCaller calls and ringtone"
            setSound(null, null)
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
        val contactName = call.details.contactDisplayName?.trim()?.takeIf { it.isNotBlank() && !it.equals(handle, ignoreCase = true) && !isGenericCallerName(it) } ?: lookupContactName(handle)
        val record = if (handle.isNotBlank()) callerRepository.lookup(handle) else null
        val reputationName = record?.displayName?.trim()?.takeIf { it.isNotBlank() && !isGenericCallerName(it) && !it.equals(handle, ignoreCase = true) }
        val isSpam = record?.level == ReputationLevel.SPAM || record?.level == ReputationLevel.SCAM
        val displayName = contactName ?: reputationName ?: handle.ifBlank { "Unknown caller" }
        val ringing = call.state == Call.STATE_RINGING
        val channel = if (ringing) INCOMING_CHANNEL_ID else ONGOING_CHANNEL_ID
        val intent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(this, 700, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, channel)
            .setSmallIcon(com.limradigitals.realcaller.R.drawable.ic_smartcaller_notification_bell)
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

    private fun isGenericCallerName(value: String): Boolean = value.trim().lowercase() in setOf(
        "unknown", "unknown caller", "private number", "private caller", "unavailable", "no caller id", "anonymous"
    )

    private fun lookupContactName(number: String): String? {
        if (number.isBlank()) return null
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null

        val digits = number.filter { it.isDigit() }
        val candidates = linkedSetOf<String>().apply {
            add(number.trim())
            if (digits.isNotBlank()) add(digits)
            if (digits.length > 10) add(digits.takeLast(10))
            if (digits.length == 12 && digits.startsWith("91")) add(digits.substring(2))
            if (digits.length == 11 && digits.startsWith("0")) add(digits.substring(1))
        }

        return try {
            for (candidate in candidates) {
                val lookupUris = listOf(
                    Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(candidate)),
                    Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(candidate))
                )
                for (uri in lookupUris) {
                    val name = contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0)?.trim()?.takeIf { it.isNotBlank() && !it.equals(number, ignoreCase = true) && !it.equals(candidate, ignoreCase = true) } else null
                    }
                    if (!name.isNullOrBlank()) return name
                }
            }
            null
        } catch (_: SecurityException) { null } catch (_: Exception) { null }
    }

    companion object {
        const val INCOMING_CHANNEL_ID = "smartcaller_incoming_v4"
        const val ONGOING_CHANNEL_ID = "smartcaller_ongoing_v2"
        const val CALL_CHANNEL_ID = ONGOING_CHANNEL_ID
        const val CALL_NOTIFICATION_ID = 9001
        @Volatile var currentCall: Call? = null
            private set
        @Volatile var instance: InCallServiceImpl? = null
            private set
    }
}
