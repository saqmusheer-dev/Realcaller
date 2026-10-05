package com.limradigitals.realcaller

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CallBlue = Color(0xFF1565C0)
private val CallGreen = Color(0xFF15945B)
private val CallRed = Color(0xFFD93636)
private val CallBg = Color(0xFFF6F9FC)

class CallActivity : ComponentActivity() {
    private var call by mutableStateOf<Call?>(null)
    private var state by mutableStateOf(Call.STATE_DISCONNECTED)
    private var muted by mutableStateOf(false)
    private var speaker by mutableStateOf(false)
    private var recording by mutableStateOf(false)
    private var recordMessage by mutableStateOf("")
    private var resolvedName by mutableStateOf<String?>(null)
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private val handler = Handler(Looper.getMainLooper())

    private val poller = object : Runnable {
        override fun run() {
            val active = InCallServiceImpl.currentCall
            call = active
            state = active?.state ?: Call.STATE_DISCONNECTED
            resolvedName = active?.let { resolveContactName(it.details.handle?.schemeSpecificPart.orEmpty()) }
            if (active == null) {
                stopRecording()
                finish()
                return
            }
            handler.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        setContent {
            Surface(color = CallBg) {
                CallScreen(
                    call = call,
                    state = state,
                    contactName = resolvedName,
                    muted = muted,
                    speaker = speaker,
                    recording = recording,
                    recordMessage = recordMessage,
                    onAnswer = { call?.answer(VideoProfile.STATE_AUDIO_ONLY) },
                    onReject = { call?.disconnect() },
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
                    onRecord = { toggleRecording() },
                    onEnd = { stopRecording(); call?.disconnect() }
                )
            }
        }
        handler.post(poller)
    }

    private fun toggleRecording() {
        if (recording) {
            stopRecording()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 5510)
            recordMessage = "Allow microphone access to start recording"
            return
        }
        startRecording()
    }

    private fun startRecording() {
        val dir = File(getExternalFilesDir(null), "CallRecordings")
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "SmartCaller_$stamp.m4a")
        try {
            val r = MediaRecorder(this)
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128000)
            r.setAudioSamplingRate(44100)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recordingFile = file
            recording = true
            recordMessage = "Recording this call"
        } catch (_: Exception) {
            try { recorder?.release() } catch (_: Exception) { }
            recorder = null
            file.delete()
            recordMessage = "Recording isn't supported for this call on this device"
            recording = false
        }
    }

    private fun stopRecording() {
        val wasRecording = recording
        try { recorder?.stop() } catch (_: Exception) { recordingFile?.delete() }
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
        recording = false
        if (wasRecording) recordMessage = "Recording saved in SmartCaller"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 5510 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecording()
    }

    private fun resolveContactName(number: String): String? {
        if (number.isBlank() || checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0)?.takeIf { n -> n.isNotBlank() } else null
            }
        } catch (_: Exception) { null }
    }

    override fun onDestroy() {
        stopRecording()
        handler.removeCallbacks(poller)
        super.onDestroy()
    }
}

@androidx.compose.runtime.Composable
private fun CallScreen(
    call: Call?,
    state: Int,
    contactName: String?,
    muted: Boolean,
    speaker: Boolean,
    recording: Boolean,
    recordMessage: String,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    onRecord: () -> Unit,
    onEnd: () -> Unit
) {
    val number = call?.details?.handle?.schemeSpecificPart ?: "Unknown number"
    val telecomName = call?.details?.contactDisplayName?.takeIf { it.isNotBlank() }
    val displayName = contactName ?: telecomName ?: number
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
    val initials = displayName.trim().split(Regex("\\s+")).let { if (it.size > 1) "${it.first().first()}${it.last().first()}" else it.firstOrNull()?.take(1) ?: "?" }.uppercase()

    Column(
        modifier = Modifier.fillMaxSize().background(CallBg).padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("SmartCaller", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = CallBlue)
        Spacer(Modifier.height(26.dp))
        Box(
            Modifier.size(92.dp).clip(CircleShape).background(Color(0xFFE8F1FB)).border(2.dp, Color(0xFFB9D3EE), CircleShape),
            contentAlignment = Alignment.Center
        ) { Text(initials, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = CallBlue) }
        Spacer(Modifier.height(18.dp))
        Text(displayName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, color = Color(0xFF17202A))
        if (contactName != null || telecomName != null) {
            Spacer(Modifier.height(7.dp))
            Text(number, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF687684))
            Spacer(Modifier.height(7.dp))
            Box(Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0xFFE8F7EF)).padding(horizontal = 9.dp, vertical = 4.dp)) {
                Text("✓ Contact", color = CallGreen, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.clip(RoundedCornerShape(30.dp)).background(Color.White).border(1.dp, Color(0xFFD9E2EA), RoundedCornerShape(30.dp)).padding(horizontal = 16.dp, vertical = 7.dp)) {
            Text(status, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (isActive) CallGreen else CallBlue)
        }
        if (recordMessage.isNotBlank() && isActive) {
            Spacer(Modifier.height(10.dp))
            Text(recordMessage, color = if (recording) CallRed else Color(0xFF687684), style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(42.dp))

        if (isRinging) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Button(onClick = onReject, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = CallRed)) { Text("Decline", fontWeight = FontWeight.Bold) }
                Button(onClick = onAnswer, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = CallGreen)) { Text("Answer", fontWeight = FontWeight.Bold) }
            }
        } else if (isActive) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onMute, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(17.dp)) { Text(if (muted) "Unmute" else "Mute") }
                OutlinedButton(onClick = onSpeaker, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(17.dp)) { Text(if (speaker) "Earpiece" else "Speaker") }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRecord, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(17.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = if (recording) CallRed else CallBlue)) {
                Text(if (recording) "■ Stop recording" else "● Record call", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(22.dp))
            Button(onClick = onEnd, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(20.dp), colors = ButtonDefaults.buttonColors(containerColor = CallRed)) {
                Text("End call", fontWeight = FontWeight.ExtraBold)
            }
        } else {
            OutlinedButton(onClick = onEnd, modifier = Modifier.height(52.dp), shape = RoundedCornerShape(17.dp)) { Text("Cancel") }
        }
    }
}
