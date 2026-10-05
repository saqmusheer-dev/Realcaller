package com.limradigitals.realcaller.screening

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import com.limradigitals.realcaller.MainActivity
import com.limradigitals.realcaller.R
import com.limradigitals.realcaller.data.CallerRepository
import com.limradigitals.realcaller.data.ReputationEngine

/** Local-first V1 call screening with a SmartCaller heads-up notification. */
class RealCallerScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart.orEmpty()
        val repository = CallerRepository(applicationContext)
        val record = repository.lookup(number)
        repository.recordIncomingCall(number)

        showCallerNotification(number, record?.displayName, record?.category, record?.reputationScore, record?.reportCount)

        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSilenceCall(ReputationEngine.shouldSilence(record))
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()

        respondToCall(callDetails, response)
    }

    private fun showCallerNotification(
        number: String,
        name: String?,
        category: String?,
        reputation: Int?,
        reports: Int?
    ) {
        val manager = getSystemService(NotificationManager::class.java)
        val channelId = "incoming_caller"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Incoming Caller Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "SmartCaller caller identification and spam alerts"
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
        }

        val title = name ?: "Unknown caller"
        val details = buildString {
            append(number)
            category?.let { append(" • ").append(it) }
            reputation?.let { append(" • Reputation ").append(it).append("/100") }
            if ((reports ?: 0) > 0) append(" • ").append(reports).append(" reports")
        }

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_smartcaller)
                .setContentTitle("SmartCaller • $title")
                .setContentText(details)
                .setStyle(Notification.BigTextStyle().bigText(details))
                .setCategory(Notification.CATEGORY_CALL)
                .setPriority(Notification.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
        } else {
            Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_smartcaller)
                .setContentTitle("SmartCaller • $title")
                .setContentText(details)
                .setCategory(Notification.CATEGORY_CALL)
                .setPriority(Notification.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
        }

        manager.notify(2001, notification)
    }
}
