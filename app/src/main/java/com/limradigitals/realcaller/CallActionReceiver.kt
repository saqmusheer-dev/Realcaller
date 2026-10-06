package com.limradigitals.realcaller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.VideoProfile

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val call = InCallServiceImpl.currentCall ?: return
        when (intent.action) {
            ACTION_ANSWER -> call.answer(VideoProfile.STATE_AUDIO_ONLY)
            ACTION_IGNORE -> InCallServiceImpl.instance?.silenceRinger()
            ACTION_REJECT -> call.disconnect()
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.limradigitals.realcaller.ANSWER_CALL"
        const val ACTION_IGNORE = "com.limradigitals.realcaller.IGNORE_CALL"
        const val ACTION_REJECT = "com.limradigitals.realcaller.REJECT_CALL"
    }
}
