package com.limradigitals.realcaller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.InCallService
import androidx.core.app.NotificationCompat
import java.util.Locale

class InCallServiceImpl : InCallService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        createCallChannel()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        currentCall = call
        call.registerCallback(callback)
        showCallNotification(call)
        launchCallUi()
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        if (currentCall === call) currentCall = null
        getSystemService(NotificationManager::class.java).cancel(CALL_NOTIFICATION_ID)
    }

    override fun onBringToForeground(showDialpad: Boolean) {
        super.onBringToForeground(showDialpad)
        launchCallUi()
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            showCallNotification(call)
            if (state == Call.STATE_RINGING || state == Call.STATE_DIALING ||
                state == Call.STATE_CONNECTING || state == Call.STATE_ACTIVE) {
                launchCallUi()
            }
        }
    }

    private fun launchCallUi() {
        mainHandler.post {
            if (currentCall == null) return@post
            val intent = Intent(this, CallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            try { startActivity(intent) } catch (_: Exception) { }
        }
    }

    private fun createCallChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(
            CALL_CHANNEL_ID, "SmartCaller Calls", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE), audio)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun showCallNotification(call: Call) {
        val handle = call.details.handle?.schemeSpecificPart.orEmpty()
        val contactName = call.details.contactDisplayName?.takeIf { it.isNotBlank() }
            ?: lookupContactName(handle)
        val displayName = contactName ?: handle.ifBlank { "Unknown caller" }
        val ringing = call.state == Call.STATE_RINGING
        val intent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 700, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CALL_CHANNEL_ID)
            .setSmallIcon(com.limradigitals.realcaller.R.drawable.ic_smartcaller)
            .setContentTitle(if (ringing) "Incoming call • $displayName" else "SmartCaller • $displayName")
            .setContentText(handle.ifBlank { "Phone call" })
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(ringing || call.state == Call.STATE_ACTIVE)
            .setAutoCancel(!ringing)
            .setContentIntent(pending)

        if (ringing) {
            val answerIntent = Intent(this, CallActionReceiver::class.java).apply { action = CallActionReceiver.ACTION_ANSWER }
            val rejectIntent = Intent(this, CallActionReceiver::class.java).apply { action = CallActionReceiver.ACTION_REJECT }
            val answerPending = PendingIntent.getBroadcast(this, 701, answerIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val rejectPending = PendingIntent.getBroadcast(this, 702, rejectIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.setFullScreenIntent(pending, true)
                .addAction(0, "Answer", answerPending)
                .addAction(0, "Decline", rejectPending)
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
        const val CALL_CHANNEL_ID = "smartcaller_calls"
        const val CALL_NOTIFICATION_ID = 9001
        @Volatile var currentCall: Call? = null
            private set
        @Volatile var instance: InCallServiceImpl? = null
            private set
    }
}
