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
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.limradigitals.realcaller.data.CallerRecord
import com.limradigitals.realcaller.data.CallerRepository
import com.limradigitals.realcaller.data.ReputationLevel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Blue = Color(0xFF0757A6)
private val Green = Color(0xFF159B5B)
private val Red = Color(0xFFD93636)
private val SpamRed = Color(0xFFB42318)
private val SpamBg = Color(0xFFFFE8E6)
private val Bg = Color(0xFFF5F8FC)

class CallActivity : ComponentActivity() {
    private var call by mutableStateOf<Call?>(null)
    private var state by mutableStateOf(Call.STATE_DISCONNECTED)
    private var muted by mutableStateOf(false)
    private var speaker by mutableStateOf(false)
    private var recording by mutableStateOf(false)
    private var recordMessage by mutableStateOf("")
    private var resolvedName by mutableStateOf<String?>(null)
    private var callerRecord by mutableStateOf<CallerRecord?>(null)
    private var lastLookupNumber = ""
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private val callerRepository by lazy { CallerRepository(applicationContext) }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else recordMessage = "Microphone permission is required"
    }

    private val poller = object : Runnable {
        override fun run() {
            val active = InCallServiceImpl.currentCall
            call = active
            state = active?.state ?: Call.STATE_DISCONNECTED
            resolvedName = active?.let { resolveContactName(it.details.handle?.schemeSpecificPart.orEmpty()) }
            val number = active?.details?.handle?.schemeSpecificPart.orEmpty()
            if (number != lastLookupNumber) {
                lastLookupNumber = number
                callerRecord = if (number.isNotBlank()) callerRepository.lookup(number) else null
            }
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
            Surface(color = Bg) {
                CallScreen(
                    call = call,
                    state = state,
                    contactName = resolvedName,
                    callerRecord = callerRecord,
                    muted = muted,
                    speaker = speaker,
                    recording = recording,
                    recordMessage = recordMessage,
                    showDialpad = showDialpad,
                    dialpadMode = dialpadMode,
                    dialpadText = dialpadText,
                    onAnswer = { call?.answer(VideoProfile.STATE_AUDIO_ONLY) },
                    onResume = { call?.let { InCallServiceImpl.instance?.unholdCall(it) }; heldByUser = false },
                    onIgnore = {
                        // Ignore means silence the ringtone but keep the call ringing.
                        InCallServiceImpl.instance?.silenceRinger()
                        finish()
                    },
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
                    onEnd = { stopRecording(); call?.disconnect() },
                    onToggleDialpad = { showDialpad = !showDialpad },
                    onAddCall = { prepareNewCall() },
                    onDtmfDigit = { sendDtmf(it) },
                    onNewCallDigit = { digit -> if (dialpadText.length < 24) dialpadText += digit },
                    onNewCallBackspace = { if (dialpadText.isNotEmpty()) dialpadText = dialpadText.dropLast(1) },
                    onPlaceNewCall = { placeNewCall(dialpadText) },
                    onCloseDialpad = { showDialpad = false; dialpadMode = DialpadMode.DTMF; dialpadText = "" }
                )
            }
        }
        handler.post(poller)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        // While an incoming call is ringing, either hardware volume key should
        // silence only this call. Do not alter the user's saved ring volume.
        if ((keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) &&
            state == Call.STATE_RINGING) {
            InCallServiceImpl.instance?.silenceRinger()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun holdCurrentCall() {
        val active = InCallServiceImpl.instance?.getManagedCalls()?.firstOrNull { it.state == Call.STATE_ACTIVE } ?: call?.takeIf { it.state == Call.STATE_ACTIVE }
        if (active == null) { recordMessage = "No active call to hold"; return }
        val canHold = (active.details.callCapabilities and Call.Details.CAPABILITY_HOLD) != 0
        if (!canHold) { recordMessage = "This call cannot be put on hold"; return }
        InCallServiceImpl.instance?.holdCall(active)
        heldByUser = true
        recordMessage = "Call on hold"
    }

    private fun prepareNewCall() {
        val active = InCallServiceImpl.instance?.getManagedCalls()
            ?.firstOrNull { it.state == Call.STATE_ACTIVE }
            ?: call?.takeIf { it.state == Call.STATE_ACTIVE }

        if (active == null) {
            recordMessage = "No active call to hold"
            showDialpad = true
            dialpadMode = DialpadMode.NEW_CALL
            return
        }

        val canHold = (active.details.callCapabilities and Call.Details.CAPABILITY_HOLD) != 0
        if (!canHold) {
            recordMessage = "This call cannot be put on hold"
            return
        }

        InCallServiceImpl.instance?.holdCall(active)
        heldByUser = true
        dialpadMode = DialpadMode.NEW_CALL
        dialpadText = ""
        showDialpad = true
        recordMessage = "First call on hold"
    }

    private fun sendDtmf(digit: Char) {
        val target = InCallServiceImpl.currentCall ?: call ?: return
        if (target.state != Call.STATE_ACTIVE) return
        try {
            target.playDtmfTone(digit)
            handler.postDelayed({
                try { target.stopDtmfTone() } catch (_: Exception) { }
            }, 180L)
            if (dialpadMode == DialpadMode.DTMF) {
                dialpadText = (dialpadText + digit).takeLast(24)
            }
        } catch (_: Exception) { }
    }

    private fun placeNewCall(number: String) {
        val target = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (target.isBlank()) {
            recordMessage = "Enter a phone number"
            return
        }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            recordMessage = "Phone permission is required"
            return
        }

        try {
            val telecom = getSystemService(TelecomManager::class.java)
            val accounts = if (
                checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
                (android.os.Build.VERSION.SDK_INT < 31 ||
                    checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED)
            ) {
                telecom.getCallCapablePhoneAccounts().filter { handle ->
                    telecom.getPhoneAccount(handle)?.hasCapabilities(
                        android.telecom.PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION
                    ) == true
                }
            } else emptyList()

            val selected = telecom.getDefaultOutgoingPhoneAccount("tel")
                ?.takeIf { account -> accounts.isEmpty() || accounts.contains(account) }
                ?: accounts.firstOrNull()

            val extras = Bundle().apply {
                if (selected != null) putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, selected)
            }

            telecom.placeCall(Uri.fromParts("tel", target, null), extras)
            dialpadMode = DialpadMode.DTMF
            dialpadText = ""
            recordMessage = "Calling $target…"
            showDialpad = false
        } catch (_: SecurityException) {
            recordMessage = "Phone permission denied"
        } catch (_: Exception) {
            recordMessage = "Unable to start new call"
        }
    }

    private fun toggleRecording() {
        if (recording) {
            stopRecording()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startRecording()
        }
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
            recording = false
            recordMessage = "Call recording is not supported on this device"
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

    private fun resolveContactName(number: String): String? {
        if (number.isBlank() || checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
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
    callerRecord: CallerRecord?,
    muted: Boolean,
    speaker: Boolean,
    recording: Boolean,
    recordMessage: String,
    showDialpad: Boolean,
    dialpadMode: DialpadMode,
    dialpadText: String,
    onAnswer: () -> Unit,
    onResume: () -> Unit,
    onIgnore: () -> Unit,
    onReject: () -> Unit,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    onRecord: () -> Unit,
    onEnd: () -> Unit,
    onToggleDialpad: () -> Unit,
    onAddCall: () -> Unit,
    onDtmfDigit: (Char) -> Unit,
    onNewCallDigit: (Char) -> Unit,
    onNewCallBackspace: () -> Unit,
    onPlaceNewCall: () -> Unit,
    onCloseDialpad: () -> Unit
) {
    val number = call?.details?.handle?.schemeSpecificPart ?: "Unknown number"
    val telecomName = call?.details?.contactDisplayName?.takeIf { it.isNotBlank() }
    val displayName = contactName ?: telecomName ?: callerRecord?.displayName ?: number
    val isRinging = state == Call.STATE_RINGING
    val isActive = state == Call.STATE_ACTIVE
    val isSpam = isRinging && (callerRecord?.level == ReputationLevel.SPAM || callerRecord?.level == ReputationLevel.SCAM)
    val status = when {
        isSpam -> "Potential spam call"
        state == Call.STATE_RINGING -> "Incoming call"
        state == Call.STATE_DIALING -> "Calling…"
        state == Call.STATE_CONNECTING -> "Connecting…"
        state == Call.STATE_ACTIVE -> "Connected"
        state == Call.STATE_HOLDING -> "On hold"
        state == Call.STATE_DISCONNECTED -> "Call ended"
        else -> "Connecting…"
    }
    val initials = displayName.trim().split(Regex("\\s+")).let {
        if (it.size > 1) "${it.first().first()}${it.last().first()}" else it.firstOrNull()?.take(1) ?: "?"
    }.uppercase()

    Column(
        modifier = Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            color = if (isSpam) SpamBg else Color.White,
            shadowElevation = if (isSpam) 6.dp else 3.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("SmartCaller", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if (isSpam) SpamRed else Blue)

                if (isSpam) {
                    Spacer(Modifier.height(12.dp))
                    Surface(shape = RoundedCornerShape(50.dp), color = SpamRed) {
                        Text(
                            "⚠ SPAM CALL",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                Spacer(Modifier.height(22.dp))
                Box(
                    Modifier.size(96.dp)
                        .clip(CircleShape)
                        .background(if (isSpam) Color(0xFFFFD2CE) else Color(0xFFE7F1FC))
                        .border(2.dp, if (isSpam) Color(0xFFF39A91) else Color(0xFFB7D1EA), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(initials, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = if (isSpam) SpamRed else Blue)
                }
                Spacer(Modifier.height(16.dp))
                Text(displayName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, color = Color(0xFF17202A))
                if (contactName != null || telecomName != null || callerRecord?.displayName != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(number, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF687684))
                }
                if (isSpam && callerRecord != null) {
                    Spacer(Modifier.height(7.dp))
                    val reports = callerRecord.reportCount
                    Text(
                        if (reports > 0) "$reports user report${if (reports == 1) "" else "s"}" else "SmartCaller warning",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = SpamRed
                    )
                } else if (contactName != null || telecomName != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("✓ Contact", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Green)
                }
                Spacer(Modifier.height(12.dp))
                Text(status, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (isSpam) SpamRed else if (isActive) Green else Blue)
                if (recordMessage.isNotBlank() && isActive) {
                    Spacer(Modifier.height(8.dp))
                    Text(recordMessage, color = if (recording) Red else Color(0xFF687684), style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(34.dp))

                if (isRinging && isSpam) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onIgnore,
                            modifier = Modifier.weight(1f).height(56.dp),
                            shape = RoundedCornerShape(17.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SpamRed)
                        ) {
                            Text("Ignore", fontWeight = FontWeight.Bold, color = SpamRed)
                        }
                        Button(
                            onClick = onReject,
                            modifier = Modifier.weight(1f).height(56.dp),
                            shape = RoundedCornerShape(17.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Red)
                        ) {
                            Text("Decline", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = onAnswer,
                            modifier = Modifier.weight(1f).height(56.dp),
                            shape = RoundedCornerShape(17.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Green)
                        ) {
                            Text("Accept", fontWeight = FontWeight.Bold)
                        }
                    }
                } else if (isRinging) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(onClick = onReject, modifier = Modifier.weight(1f).height(58.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Red)) {
                            Text("Decline", fontWeight = FontWeight.Bold)
                        }
                        Button(onClick = onAnswer, modifier = Modifier.weight(1f).height(58.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                            Text("Answer", fontWeight = FontWeight.Bold)
                        }
                    }
                } else if (isActive) {
                    if (!showDialpad) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onMute, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(17.dp)) { Text(if (muted) "Unmute" else "Mute") }
                            OutlinedButton(onClick = onSpeaker, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(17.dp)) { Text(if (speaker) "Earpiece" else "Speaker") }
                            OutlinedButton(onClick = onToggleDialpad, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(17.dp)) { Text("Keypad") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { holdCurrentCall() }, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(16.dp)) { Text("Hold") }
                            OutlinedButton(onClick = onAddCall, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(16.dp)) { Text("New call") }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = onAddCall, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(17.dp)) { Text("New call") }
                            OutlinedButton(onClick = onRecord, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(17.dp)) {
                                Text(if (recording) "■ Recording" else "● Record call", fontWeight = FontWeight.Bold, color = if (recording) Red else Blue)
                            }
                        }
                    } else {
                        InCallDialpad(
                            mode = dialpadMode,
                            text = dialpadText,
                            onDigit = if (dialpadMode == DialpadMode.DTMF) onDtmfDigit else onNewCallDigit,
                            onBackspace = onNewCallBackspace,
                            onCall = onPlaceNewCall,
                            onClose = onCloseDialpad
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    MultiCallControls(
                        modifier = Modifier.fillMaxWidth(),
                        onAddCall = onAddCall
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onEnd, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(20.dp), colors = ButtonDefaults.buttonColors(containerColor = Red)) {
                        Text("End call", fontWeight = FontWeight.ExtraBold)
                    }
                } else if (state == Call.STATE_HOLDING) {
                    Text("Call on hold", color = Blue, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = onResume,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue)
                    ) { Text("Resume call", fontWeight = FontWeight.ExtraBold) }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onEnd,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Red)
                    ) { Text("End call", fontWeight = FontWeight.ExtraBold) }
                } else {
                    OutlinedButton(onClick = onEnd, modifier = Modifier.height(52.dp), shape = RoundedCornerShape(17.dp)) { Text("Cancel") }
                }
            }
        }
    }
}


private enum class DialpadMode { DTMF, NEW_CALL }

@androidx.compose.runtime.Composable
private fun InCallDialpad(
    mode: DialpadMode,
    text: String,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onCall: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (mode == DialpadMode.NEW_CALL) "New call" else "Dial pad",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Blue
            )
            OutlinedButton(onClick = onClose, shape = RoundedCornerShape(14.dp)) { Text("Close") }
        }

        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFFF5F8FC)
        ) {
            Text(
                text.ifBlank { if (mode == DialpadMode.NEW_CALL) "Enter number" else "DTMF ready" },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFF27313B),
                fontWeight = FontWeight.SemiBold
            )
        }

        val rows = listOf(
            listOf('1', '2', '3'),
            listOf('4', '5', '6'),
            listOf('7', '8', '9'),
            listOf('*', '0', '#')
        )
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { digit ->
                    OutlinedButton(
                        onClick = { onDigit(digit) },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(digit.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (mode == DialpadMode.NEW_CALL) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onBackspace,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("⌫") }
                Button(
                    onClick = onCall,
                    modifier = Modifier.weight(2f).height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Green)
                ) { Text("Call", fontWeight = FontWeight.ExtraBold) }
            }
        } else {
            Text(
                "Tap a key to send tones to the connected call",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF687684),
                textAlign = TextAlign.Center
            )
        }
    }
}
