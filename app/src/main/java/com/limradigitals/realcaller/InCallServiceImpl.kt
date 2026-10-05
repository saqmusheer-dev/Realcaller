package com.limradigitals.realcaller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import androidx.core.app.NotificationCompat

class InCallServiceImpl : InCallService() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        createCallChannel()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        currentCall = call
        call.registerCallback(callback)
        showCallNotification(call)
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        if (currentCall === call) currentCall = null
        getSystemService(NotificationManager::class.java).cancel(CALL_NOTIFICATION_ID)
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            showCallNotification(call)
        }
    }

    private fun createCallChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(
            CALL_CHANNEL_ID,
            "SmartCaller Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(ringtone, audio)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun showCallNotification(call: Call) {
        val details = call.details
        val handle = details.handle?.schemeSpecificPart ?: "Unknown number"
        val name = details.contactDisplayName?.takeIf { it.isNotBlank() } ?: handle
        val ringing = call.state == Call.STATE_RINGING
        val intent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            700,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CALL_CHANNEL_ID)
            .setSmallIcon(com.limradigitals.realcaller.R.drawable.ic_smartcaller)
            .setContentTitle(if (ringing) "Incoming call" else "SmartCaller")
            .setContentText(name)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(ringing || call.state == Call.STATE_ACTIVE)
            .setAutoCancel(!ringing)
            .setContentIntent(pending)

        if (ringing) {
            builder.setFullScreenIntent(pending, true)
        }

        getSystemService(NotificationManager::class.java)
            .notify(CALL_NOTIFICATION_ID, builder.build())
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
