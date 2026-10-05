package com.limradigitals.realcaller.screening

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.Call
import android.telecom.CallScreeningService
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.limradigitals.realcaller.MainActivity
import com.limradigitals.realcaller.R
import com.limradigitals.realcaller.data.CallerRepository
import com.limradigitals.realcaller.data.ReputationEngine

/** Local-first V1 call screening with notification + actionable caller card. */
class RealCallerScreeningService : CallScreeningService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var overlayWindowManager: WindowManager? = null

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart.orEmpty()
        val repository = CallerRepository(applicationContext)
        val record = repository.lookup(number)
        repository.recordIncomingCall(number)

        showCallerNotification(number, record?.displayName, record?.category, record?.reputationScore, record?.reportCount)
        showCallerOverlay(number, record?.displayName, record?.category, record?.reputationScore, record?.reportCount)

        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSilenceCall(ReputationEngine.shouldSilence(record))
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()

        respondToCall(callDetails, response)
    }

    private fun showCallerOverlay(
        number: String,
        name: String?,
        category: String?,
        reputation: Int?,
        reports: Int?
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !Settings.canDrawOverlays(this)) return

        mainHandler.post {
            removeCallerOverlay()

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(22).toFloat()
                    setStroke(dp(1), Color.rgb(225, 228, 232))
                }
                elevation = dp(10).toFloat()
            }

            val header = TextView(this).apply {
                text = "SmartCaller  •  INCOMING CALL"
                textSize = 14f
                setTextColor(Color.rgb(35, 45, 55))
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }
            card.addView(header, matchWrap())

            val title = TextView(this).apply {
                text = name ?: number.ifBlank { "Unknown caller" }
                textSize = 25f
                setTextColor(Color.rgb(20, 25, 30))
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(0, dp(5), 0, 0)
            }
            card.addView(title, matchWrap())

            val details = buildString {
                append(number)
                category?.let { append("  •  ").append(it) }
            }
            val detailView = TextView(this).apply {
                text = details
                textSize = 14f
                setTextColor(Color.rgb(85, 95, 105))
                setPadding(0, dp(3), 0, 0)
            }
            card.addView(detailView, matchWrap())

            val score = reputation ?: 50
            val reputationView = TextView(this).apply {
                text = "Reputation  $score/100" + if ((reports ?: 0) > 0) "   •   ${reports} reports" else ""
                textSize = 15f
                setTextColor(if (score >= 70) Color.rgb(25, 125, 65) else Color.rgb(190, 75, 45))
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(0, dp(9), 0, 0)
            }
            card.addView(reputationView, matchWrap())

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }

            val spamButton = Button(this).apply {
                text = "Mark as spam"
                isAllCaps = false
                setOnClickListener {
                    CallerRepository(applicationContext).markAsSpam(number)
                    Toast.makeText(context, "Reported to SmartCaller", Toast.LENGTH_SHORT).show()
                    removeCallerOverlay()
                }
            }
            val updateButton = Button(this).apply {
                text = "Update call info"
                isAllCaps = false
                setOnClickListener {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("update_number", number)
                    }
                    context.startActivity(intent)
                    removeCallerOverlay()
                }
            }
            actions.addView(spamButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
            actions.addView(updateButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
            card.addView(actions, matchWrap())

            val footer = TextView(this).apply {
                text = "Tap the caller card to open SmartCaller"
                textSize = 13f
                setTextColor(Color.rgb(100, 110, 120))
                setPadding(0, dp(5), 0, 0)
            }
            card.addView(footer, matchWrap())

            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 2002, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            card.setOnClickListener { pendingIntent.send(); removeCallerOverlay() }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(54)
            }

            try {
                overlayWindowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                overlayWindowManager?.addView(card, params)
                overlayView = card
                mainHandler.postDelayed({ removeCallerOverlay() }, 20000L)
            } catch (_: Exception) {
                overlayView = null
            }
        }
    }

    private fun removeCallerOverlay() {
        mainHandler.post {
            overlayView?.let { view ->
                try { overlayWindowManager?.removeView(view) } catch (_: Exception) { }
            }
            overlayView = null
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

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
            this, 1001, intent,
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
