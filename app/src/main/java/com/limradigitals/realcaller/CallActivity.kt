package com.limradigitals.realcaller

import android.os.Bundle
import android.telecom.Call
import android.telecom.VideoProfile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class CallActivity : ComponentActivity() {
    private var call by mutableStateOf<Call?>(null)
    private var state by mutableStateOf(Call.STATE_DISCONNECTED)
    private var muted by mutableStateOf(false)
    private var speaker by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        setContent {
            Surface(color = MaterialTheme.colorScheme.background) {
                CallScreen(
                    call = call,
                    state = state,
                    muted = muted,
                    speaker = speaker,
                    onAnswer = {
                        call?.answer(VideoProfile.STATE_AUDIO_ONLY)
                    },
                    onReject = {
                        call?.disconnect()
                    },
                    onMute = {
                        muted = !muted
                        InCallServiceImpl.instance?.setMuted(muted)
                    },
                    onSpeaker = {
                        speaker = !speaker
                        @Suppress("DEPRECATION")
                        InCallServiceImpl.instance?.setAudioRoute(
                            if (speaker) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE
                        )
                    },
                    onEnd = { call?.disconnect() }
                )
            }
        }

        startPolling()
    }

    private fun startPolling() {
        lifecycleScope.launchWhenStarted {
            while (true) {
                val active = InCallServiceImpl.currentCall
                call = active
                state = active?.state ?: Call.STATE_DISCONNECTED
                if (active == null) {
                    finish()
                    break
                }
                delay(250)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun CallScreen(
    call: Call?,
    state: Int,
    muted: Boolean,
    speaker: Boolean,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    onEnd: () -> Unit
) {
    val number = call?.details?.handle?.schemeSpecificPart ?: "Unknown number"
    val contact = call?.details?.contactDisplayName?.takeIf { it.isNotBlank() }
    val isRinging = state == Call.STATE_RINGING
    val isActive = state == Call.STATE_ACTIVE
    val status = when (state) {
        Call.STATE_RINGING -> "Incoming call"
        Call.STATE_DIALING -> "Calling…"
        Call.STATE_CONNECTING -> "Connecting…"
        Call.STATE_ACTIVE -> "Connected"
        Call.STATE_HOLDING -> "On hold"
        Call.STATE_DISCONNECTED -> "Call ended"
        else -> "Connecting…"
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("SmartCaller", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(40.dp))
        Text(
            text = (contact ?: number).takeIf { it.isNotBlank() } ?: "Unknown caller",
            style = MaterialTheme.typography.headlineMedium
        )
        if (contact != null) {
            Spacer(Modifier.height(8.dp))
            Text(number, style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(10.dp))
        Text(status, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(60.dp))

        if (isRinging) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Button(onClick = onReject) { Text("Decline") }
                Button(onClick = onAnswer) { Text("Answer") }
            }
        } else if (isActive) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedButton(onClick = onMute) { Text(if (muted) "Unmute" else "Mute") }
                OutlinedButton(onClick = onSpeaker) { Text(if (speaker) "Earpiece" else "Speaker") }
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onEnd,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
            ) { Text("End call") }
        } else {
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = onEnd) { Text("Cancel") }
        }
    }
}
