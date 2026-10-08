package com.limradigitals.realcaller

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.CallLog
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
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
import androidx.compose.ui.graphics.toArgb
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

private data class QuickContactEntry(val name: String, val number: String)
private data class QuickCallEntry(val name: String, val number: String, val type: String)

class CallActivity : ComponentActivity() {
    private var call by mutableStateOf<Call?>(null)
    private var state by mutableStateOf(Call.STATE_DISCONNECTED)
    private var muted by mutableStateOf(false)
    private var speaker by mutableStateOf(false)
    private var recording by mutableStateOf(false)
    private var recordMessage by mutableStateOf("")
    private var showDialpad by mutableStateOf(false)
    private var dialpadMode by mutableStateOf(DialpadMode.DTMF)
    private var dialpadText by mutableStateOf("")
    private var heldByUser by mutableStateOf(false)
    private var resolvedName by mutableStateOf<String?>(null)
    private var callerRecord by mutableStateOf<CallerRecord?>(null)
    private var lastLookupNumber = ""
    private var quickContacts by mutableStateOf<List<QuickContactEntry>>(emptyList())
    private var quickCalls by mutableStateOf<List<QuickCallEntry>>(emptyList())
    private var lastQuickState = Call.STATE_DISCONNECTED
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
            if (state != lastQuickState) {
                lastQuickState = state
                if (state == Call.STATE_HOLDING || state == Call.STATE_ACTIVE) refreshQuickCallChoices()
            }
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
        @Suppress("DEPRECATION")
        window.statusBarColor = Blue.toArgb()
        @Suppress("DEPRECATION")
        window.navigationBarColor = Bg.toArgb()
        window.decorView.systemUiVisibility = 0
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
                    quickContacts = quickContacts,
                    quickCalls = quickCalls,
                    onAnswer = { call?.answer(VideoProfile.STATE_AUDIO_ONLY) },
                    onResume = { call?.let { InCallServiceImpl.instance?.unholdCall(it) }; heldByUser = false },
                    onHold = { holdCurrentCall() },
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
                    onQuickCall = { placeNewCall(it) },
                    onCloseDialpad = { showDialpad = false; dialpadMode = DialpadMode.DTMF; dialpadText = "" }
                )
            }
        }
        handler.post(poller)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Volume keys silence only the current ringing call. Consume DOWN and UP
        // so Android does not also change the user's global ring volume.
        if ((event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) &&
            state == Call.STATE_RINGING) {
            InCallServiceImpl.instance?.silenceRinger()
            return true
        }
        return super.dispatchKeyEvent(event)
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

    private fun ensureActiveCallHeldForNewCall() {
        val active = InCallServiceImpl.instance?.getManagedCalls()
            ?.firstOrNull { it.state == Call.STATE_ACTIVE }
        if (active != null &&
            (active.details.callCapabilities and Call.Details.CAPABILITY_HOLD) != 0) {
            InCallServiceImpl.instance?.holdCall(active)
        }
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
            ensureActiveCallHeldForNewCall()
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

    private fun refreshQuickCallChoices() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            quickContacts = try {
                val result = mutableListOf<QuickContactEntry>()
                contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    null,
                    null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                )?.use { cursor ->
                    while (cursor.moveToNext() && result.size < 12) {
                        val name = cursor.getString(0)?.trim().orEmpty()
                        val number = cursor.getString(1)?.trim().orEmpty()
                        if (name.isNotBlank() && number.isNotBlank() &&
                            result.none { it.number == number }) {
                            result += QuickContactEntry(name, number)
                        }
                    }
                }
                result
            } catch (_: Exception) { emptyList() }
        }

        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            quickCalls = try {
                val result = mutableListOf<QuickCallEntry>()
                contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(
                        CallLog.Calls.NUMBER,
                        CallLog.Calls.CACHED_NAME,
                        CallLog.Calls.TYPE,
                        CallLog.Calls.DATE
                    ),
                    null,
                    null,
                    CallLog.Calls.DATE + " DESC"
                )?.use { cursor ->
                    while (cursor.moveToNext() && result.size < 18) {
                        val number = cursor.getString(0)?.trim().orEmpty()
                        if (number.isBlank()) continue
                        val cached = cursor.getString(1)?.trim().orEmpty()
                        val type = when (cursor.getInt(2)) {
                            CallLog.Calls.MISSED_TYPE -> "Missed"
                            CallLog.Calls.INCOMING_TYPE -> "Received"
                            CallLog.Calls.OUTGOING_TYPE -> "Dialled"
                            else -> "Call"
                        }
                        val name = cached.ifBlank { resolveContactName(number) ?: number }
                        result += QuickCallEntry(name, number, type)
                    }
                }
                result
            } catch (_: Exception) { emptyList() }
        }
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
    quickContacts: List<QuickContactEntry>,
    quickCalls: List<QuickCallEntry>,
    onAnswer: () -> Unit,
    onResume: () -> Unit,
    onHold: () -> Unit,
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
    onQuickCall: (String) -> Unit,
    onCloseDialpad: () -> Unit
) {
    val number = call?.details?.handle?.schemeSpecificPart ?: "Unknown number"
    val telecomName = call?.details?.contactDisplayName?.takeIf { it.isNotBlank() }
    val displayName = contactName ?: telecomName ?: callerRecord?.displayName ?: number
    val isRinging = state == Call.STATE_RINGING
    val isActive = state == Call.STATE_ACTIVE
    val isHolding = state == Call.STATE_HOLDING
    val isSpam = isRinging && (callerRecord?.level == ReputationLevel.SPAM || callerRecord?.level == ReputationLevel.SCAM)
    val status = when {
        isSpam -> "Potential spam call"
        isRinging -> "Incoming call"
        state == Call.STATE_DIALING -> "Calling…"
        state == Call.STATE_CONNECTING -> "Connecting…"
        isActive -> "Connected"
        isHolding -> "On hold"
        state == Call.STATE_DISCONNECTED -> "Call ended"
        else -> "Connecting…"
    }
    val initials = displayName.trim().split(Regex("\\s+")).let {
        if (it.size > 1) "${it.first().first()}${it.last().first()}" else it.firstOrNull()?.take(1) ?: "?"
    }.uppercase()

    Column(
        modifier = Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(Blue)) {
            Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars))
            Row(
                modifier = Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SmartCaller", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(50.dp), color = Color.White.copy(alpha = 0.16f)) {
                    Text(
                        if (isRinging) "INCOMING" else if (isActive) "LIVE" else if (isHolding) "HOLD" else "CALL",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
                color = if (isSpam) SpamBg else Color.White,
                shadowElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isSpam) {
                        Surface(shape = RoundedCornerShape(50.dp), color = SpamRed) {
                            Text(
                                "⚠  SPAM CALL",
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    Box(
                        Modifier.size(86.dp).clip(CircleShape)
                            .background(if (isSpam) Color(0xFFFFD2CE) else Color(0xFFE8F2FF))
                            .border(2.dp, if (isSpam) Color(0xFFF39A91) else Color(0xFFB7D1EA), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            initials,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isSpam) SpamRed else Blue
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        color = Color(0xFF17202A)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(number, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF687684), textAlign = TextAlign.Center)

                    if (isSpam && callerRecord != null) {
                        Spacer(Modifier.height(6.dp))
                        val reports = callerRecord.reportCount
                        Text(
                            if (reports > 0) "${reports} user report${if (reports == 1) "" else "s"}" else "SmartCaller warning",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = SpamRed
                        )
                    } else if (contactName != null || telecomName != null) {
                        Spacer(Modifier.height(5.dp))
                        Text("✓ Saved contact", color = Green, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    }

                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(50.dp),
                        color = when {
                            isSpam -> Color(0xFFFFDAD6)
                            isActive -> Color(0xFFE7F7EF)
                            isHolding -> Color(0xFFE8F0FA)
                            else -> Color(0xFFF1F5F9)
                        }
                    ) {
                        Text(
                            status,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            color = when {
                                isSpam -> SpamRed
                                isActive -> Green
                                else -> Blue
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (recordMessage.isNotBlank() && (isActive || isHolding)) {
                        Spacer(Modifier.height(7.dp))
                        Text(
                            recordMessage,
                            color = if (recording) Red else Color(0xFF687684),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            if (isRinging) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White,
                    shadowElevation = 2.dp
                ) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (isSpam) CallActionButton("Ignore", "✕", SpamRed, false, onIgnore, Modifier.weight(1f))
                        CallActionButton("Decline", "✕", Red, true, onReject, Modifier.weight(1f))
                        CallActionButton("Answer", "☎", Green, true, onAnswer, Modifier.weight(1f))
                    }
                }
            } else if (isActive || isHolding) {
                if (showDialpad && isActive) {
                    InCallDialpad(
                        mode = dialpadMode,
                        text = dialpadText,
                        onDigit = if (dialpadMode == DialpadMode.DTMF) onDtmfDigit else onNewCallDigit,
                        onBackspace = onNewCallBackspace,
                        onCall = onPlaceNewCall,
                        onClose = onCloseDialpad
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(26.dp),
                        color = Color.White,
                        shadowElevation = 2.dp
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CallActionButton("Mute", "MIC", Blue, muted, onMute, Modifier.weight(1f))
                                CallActionButton("Speaker", "SPK", Blue, speaker, onSpeaker, Modifier.weight(1f))
                                CallActionButton(
                                    "Keypad",
                                    "123",
                                    Blue,
                                    false,
                                    if (isHolding) onAddCall else onToggleDialpad,
                                    Modifier.weight(1f)
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CallActionButton(
                                    if (isHolding) "Resume" else "Hold",
                                    if (isHolding) "▶" else "Ⅱ",
                                    Blue,
                                    isHolding,
                                    if (isHolding) onResume else onHold,
                                    Modifier.weight(1f)
                                )
                                CallActionButton("New call", "+", Blue, false, onAddCall, Modifier.weight(1f))
                                CallActionButton(
                                    "Record",
                                    "●",
                                    if (recording) Red else Blue,
                                    recording,
                                    onRecord,
                                    Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                if (isHolding) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        color = Color.White,
                        shadowElevation = 2.dp
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Your call is safely on hold", color = Blue, fontWeight = FontWeight.ExtraBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Resume anytime, or choose a contact/recent call to start a second call.",
                                color = Color(0xFF687684),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Spacer(Modifier.height(10.dp))
                            QuickCallPicker(
                                contacts = quickContacts,
                                calls = quickCalls,
                                onCall = onQuickCall
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                MultiCallControls(modifier = Modifier.fillMaxWidth(), onAddCall = onAddCall)
                Spacer(Modifier.height(10.dp))

                Button(
                    onClick = onEnd,
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Red),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                ) {
                    Text("End call", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                }
            } else {
                OutlinedButton(onClick = onEnd, modifier = Modifier.height(52.dp), shape = RoundedCornerShape(17.dp)) {
                    Text("Cancel")
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun QuickCallPicker(
    contacts: List<QuickContactEntry>,
    calls: List<QuickCallEntry>,
    onCall: (String) -> Unit
) {
    var filter by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("Contacts") }
    val items = when (filter) {
        "Contacts" -> contacts.map { QuickCallEntry(it.name, it.number, "Contact") }
        "Received" -> calls.filter { it.type == "Received" }
        "Missed" -> calls.filter { it.type == "Missed" }
        else -> calls
    }.distinctBy { it.number }.take(6)

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        listOf("Contacts", "Received", "Missed", "Recent").forEach { tab ->
            androidx.compose.material3.FilterChip(
                selected = filter == tab,
                onClick = { filter = tab },
                label = { Text(tab, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.weight(1f)
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    if (items.isEmpty()) {
        Text(
            when (filter) {
                "Contacts" -> "No contacts available"
                "Received" -> "No received calls"
                "Missed" -> "No missed calls"
                else -> "No recent calls"
            },
            color = Color(0xFF687684),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            textAlign = TextAlign.Center
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { item ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFF5F8FC),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1E7EF))
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(38.dp).clip(CircleShape).background(Color(0xFFE8F2FF)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(item.name.take(1).uppercase(), color = Blue, fontWeight = FontWeight.ExtraBold)
                        }
                        Spacer(Modifier.size(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(item.number, color = Color(0xFF687684), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            Text(
                                item.type,
                                color = when (item.type) {
                                    "Missed" -> Red
                                    "Received" -> Green
                                    else -> Blue
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Button(
                            onClick = { onCall(item.number) },
                            modifier = Modifier.height(40.dp),
                            shape = RoundedCornerShape(13.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Green),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)
                        ) { Text("Call", fontWeight = FontWeight.ExtraBold) }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun CallActionButton(
    label: String,
    symbol: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val background = if (selected) color else Color(0xFFF3F6FA)
    val content = if (selected) Color.White else Color(0xFF263442)

    androidx.compose.material3.Surface(
        modifier = modifier.height(76.dp),
        onClick = onClick,
        shape = RoundedCornerShape(19.dp),
        color = background,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1E7EF))
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(symbol, color = if (selected) Color.White else color, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(label, color = content, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = Color.White,
        shadowElevation = 3.dp
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        if (mode == DialpadMode.NEW_CALL) "New call" else "In-call keypad",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Blue
                    )
                    Text(
                        if (mode == DialpadMode.NEW_CALL) "Enter a number to add another call" else "For IVR: press 1, 2, *, #…",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF687684)
                    )
                }
                OutlinedButton(onClick = onClose, shape = RoundedCornerShape(14.dp)) { Text("Close") }
            }

            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp),
                shape = RoundedCornerShape(17.dp),
                color = Color(0xFFF2F6FA)
            ) {
                Text(
                    text.ifBlank { if (mode == DialpadMode.NEW_CALL) "Enter number" else "Ready for IVR" },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFF1E2A36),
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    row.forEach { digit ->
                        androidx.compose.material3.Surface(
                            onClick = { onDigit(digit) },
                            modifier = Modifier.weight(1f).height(56.dp),
                            shape = RoundedCornerShape(17.dp),
                            color = Color(0xFFF5F8FC),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE0E7EF))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    digit.toString(),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF1F2C39)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (mode == DialpadMode.NEW_CALL) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedButton(
                        onClick = onBackspace,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text("⌫", style = MaterialTheme.typography.titleMedium) }
                    Button(
                        onClick = onCall,
                        modifier = Modifier.weight(2f).height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Green)
                    ) { Text("Call", fontWeight = FontWeight.ExtraBold) }
                }
            } else {
                Text(
                    "Tap a key to send DTMF tones to the connected call",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF687684),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

