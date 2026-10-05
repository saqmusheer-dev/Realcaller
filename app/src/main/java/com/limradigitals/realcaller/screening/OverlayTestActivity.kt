package com.limradigitals.realcaller.screening

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class OverlayTestActivity : Activity() {
    private var overlay: LinearLayout? = null
    private var windowManager: WindowManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            finish()
            return
        }
        showTestCard()
        setContentView(TextView(this).apply { text = "SmartCaller overlay test running…" })
    }

    private fun showTestCard() {
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
        card.addView(TextView(this).apply {
            text = "SmartCaller  •  TEST CALLER CARD"
            textSize = 14f
            setTextColor(Color.rgb(35, 45, 55))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = "Samad New"
            textSize = 25f
            setTextColor(Color.rgb(20, 25, 30))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(5), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = "+91 9999999999  •  Test Business"
            textSize = 14f
            setTextColor(Color.rgb(85, 95, 105))
            setPadding(0, dp(3), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = "Reputation  92/100   •   0 reports"
            textSize = 15f
            setTextColor(Color.rgb(25, 125, 65))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(9), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = "✓ Overlay permission works"
            textSize = 13f
            setTextColor(Color.rgb(25, 125, 65))
            setPadding(0, dp(7), 0, 0)
        })
        card.setOnClickListener { finish() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(54)
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager?.addView(card, params)
        overlay = card
    }

    override fun onDestroy() {
        overlay?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        overlay = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
