package com.limradigitals.realcaller

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val SB = Color(0xFF1769D1)
private val SBG = Color(0xFFF4F6FA)
private val ST = Color(0xFF172033)
private val SM = Color(0xFF718096)

class SettingsActivity : ComponentActivity() {
    private var ringtoneTitle by mutableStateOf("Default ringtone")
    private var info by mutableStateOf<String?>(null)

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = if (Build.VERSION.SDK_INT >= 33) r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) else @Suppress("DEPRECATION") r.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (uri != null) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_RINGTONE, uri.toString()).apply()
            ringtoneTitle = RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: "Selected ringtone"
            if (Build.VERSION.SDK_INT >= 26) {
                getSystemService(android.app.NotificationManager::class.java).deleteNotificationChannel(InCallServiceImpl.INCOMING_CHANNEL_ID)
                InCallServiceImpl.instance?.createChannelsForSettings()
            }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        ringtoneTitle = savedName()
        setContent {
            MaterialTheme {
                SettingsPage(
                    ringtoneTitle,
                    onBack = { finish() },
                    onRingtone = { pickRingtone() },
                    onSystem = { openSystem(it) },
                    onInfo = { info = it }
                )
                info?.let { title -> AlertDialog(onDismissRequest = { info = null }, title = { Text(title) }, text = { Text("This SmartCaller feature is prepared in the permanent settings structure and will be expanded as the caller-intelligence layer is added.") }, confirmButton = { TextButton({ info = null }) { Text("OK") } }) }
            }
        }
    }

    private fun savedUri(): Uri? = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_RINGTONE, null)?.let(Uri::parse)
    private fun savedName(): String = savedUri()?.let { try { RingtoneManager.getRingtone(this, it)?.getTitle(this) ?: "Selected ringtone" } catch (_: Exception) { "Selected ringtone" } } ?: "Default ringtone"
    private fun pickRingtone() {
        picker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "SmartCaller incoming call ringtone")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, savedUri() ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
        })
    }
    private fun openSystem(action: String) {
        try { when (action) {
            "Call diagnostics" -> startActivity(Intent(this, CallDiagnosticsActivity::class.java))
            "calling" -> startActivity(Intent(TelecomManager.ACTION_SHOW_CALL_SETTINGS))
            "sound" -> startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
            "accessibility" -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            "blocked" -> startActivity(Intent("android.settings.BLOCKED_NUMBER_SETTINGS"))
            else -> info = action
        } } catch (_: Exception) { info = action }
    }

    companion object { const val PREFS = "smartcaller_settings"; const val KEY_RINGTONE = "incoming_ringtone_uri" }
}

private data class SI(val icon: String, val title: String, val summary: String? = null, val action: String)

@Composable
private fun SettingsPage(ringtone: String, onBack: () -> Unit, onRingtone: () -> Unit, onSystem: (String) -> Unit, onInfo: (String) -> Unit) {
    val general = listOf(
        SI("♿", "Accessibility", action = "accessibility"), SI("⌕", "Assisted dialling", "Country and number assistance", "Assisted dialling"), SI("⊘", "Blocked numbers", "Manage blocked callers", "blocked"), SI("▣", "Calling accounts", "SIM and call account settings", "calling"), SI("◉", "Call recording", "Recording preferences", "Call recording"), SI("☷", "Display options", "Caller screen appearance", "Display options"), SI("☝", "Incoming call gesture", "Answer and decline gestures", "Incoming call gesture"), SI("□", "Quick responses", "Messages when you cannot answer", "Quick responses"), SI("◖", "Sounds and vibration", "Ringtone, volume and vibration", "sound"), SI("◌", "Voicemail", "Voicemail settings", "Voicemail"), SI("♪", "Contact ringtones", "Personalize contacts", "Contact ringtones"), SI("♫", "Caller tune", ringtone, "ringtone")
    )
    val advanced = listOf(SI("✦", "Smart Caller Intelligence", "Caller profiles, reputation and business data", "Smart Caller Intelligence"), SI("⌕", "Smart search", "Search calls, contacts and caller information", "Smart search"), SI("⌁", "Privacy and data", "Local-first data controls", "Privacy and data"), SI("⚙", "Call diagnostics", "Telecom and SIM call troubleshooting", "Call diagnostics"), SI("ⓘ", "About SmartCaller", "Version 1.0", "About SmartCaller"))
    Scaffold(containerColor = SBG) { p ->
        Column(Modifier.fillMaxSize().background(SBG).padding(p)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.displaySmall, color = ST) }; Text("Settings", style = MaterialTheme.typography.headlineMedium, color = ST) }
            LazyColumn(contentPadding = PaddingValues(horizontal = 21.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                item { Section("Call Assist") }
                item { CardRow(SI("◉", "Caller ID and spam", "SmartCaller caller intelligence", "Caller ID and spam"), onSystem, onInfo) }
                item { Section("General") }
                items(general) { x -> CardRow(x, onSystem, onInfo, if (x.action == "ringtone") onRingtone else null) }
                item { Section("Advanced") }
                items(advanced) { x -> CardRow(x, onSystem, onInfo) }
            }
        }
    }
}

@Composable private fun Section(t: String) { Text(t, color = SB, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 35.dp, top = 16.dp, bottom = 8.dp)) }
@Composable private fun CardRow(x: SI, onSystem: (String) -> Unit, onInfo: (String) -> Unit, special: (() -> Unit)? = null) {
    Surface(color = Color(0xFFFCFBFF), shape = RoundedCornerShape(5.dp), modifier = Modifier.fillMaxWidth().clickable { when { special != null -> special(); x.action in setOf("calling", "sound", "accessibility", "blocked", "Call diagnostics") -> onSystem(x.action); else -> onInfo(x.title) } }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(x.icon, modifier = Modifier.width(40.dp), style = MaterialTheme.typography.titleLarge, color = Color(0xFF4F555D))
            Column(Modifier.weight(1f)) { Text(x.title, style = MaterialTheme.typography.titleMedium, color = ST); x.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SM) } }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = Color(0xFF9AA0A8))
        }
    }
}
