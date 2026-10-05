package com.limradigitals.realcaller

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.limradigitals.realcaller.data.ReputationEngine

private val SmartBlue = Color(0xFF1976D2)
private val SmartBlueDark = Color(0xFF0D47A1)
private val SmartGreen = Color(0xFF16A05D)
private val SmartGreenDark = Color(0xFF087443)
private val SmartBackground = Color(0xFFF6F9FC)
private val SmartSurface = Color.White
private val SmartText = Color(0xFF17202A)
private val SmartMuted = Color(0xFF6B7785)
private val SmartRed = Color(0xFFE53935)

private val SmartCallerColors = lightColorScheme(
    primary = SmartBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F0FF),
    onPrimaryContainer = SmartBlueDark,
    secondary = SmartGreen,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1F6EA),
    onSecondaryContainer = SmartGreenDark,
    background = SmartBackground,
    surface = SmartSurface,
    onBackground = SmartText,
    onSurface = SmartText,
    outline = Color(0xFFD5DEE8)
)

class MainActivity : ComponentActivity() {
    private lateinit var repository: CallerRepository
    private var number by mutableStateOf("")
    private var searchNumber by mutableStateOf("")
    private var searchResult by mutableStateOf<CallerRecord?>(null)
    private var status by mutableStateOf("Ready to call")
    private var defaultDialer by mutableStateOf(false)
    private var logs = mutableStateListOf<CallLogItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = CallerRepository(applicationContext)
        repository.seedDemoData()
        number = intent?.data?.schemeSpecificPart.orEmpty()
        defaultDialer = isDefaultDialer()
        requestBasicPermissions()
        refreshCallLogs()

        setContent {
            MaterialTheme(colorScheme = SmartCallerColors) {
                Surface(color = SmartBackground) {
                    SmartCallerHome(
                        number = number,
                        onNumberChange = { number = it.filter { ch -> ch.isDigit() || ch == '+' || ch == '*' || ch == '#' } },
                        onDigit = { digit -> number += digit },
                        onBackspace = { if (number.isNotEmpty()) number = number.dropLast(1) },
                        onCall = ::placeCall,
                        onMakeDefault = ::requestDefaultDialer,
                        isDefault = defaultDialer,
                        status = status,
                        logs = logs,
                        searchNumber = searchNumber,
                        onSearchNumberChange = { searchNumber = it },
                        searchResult = searchResult,
                        onSearch = { searchResult = repository.lookup(searchNumber) }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        number = intent.data?.schemeSpecificPart.orEmpty()
    }

    override fun onResume() {
        super.onResume()
        defaultDialer = isDefaultDialer()
        refreshCallLogs()
    }

    private fun isDefaultDialer(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = getSystemService(RoleManager::class.java)
        return roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) && roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
    }

    private fun requestDefaultDialer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                try {
                    startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQUEST_DIALER_ROLE)
                    return
                } catch (_: Exception) { }
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            status = "Choose SmartCaller under Default phone app"
        } catch (_: Exception) {
            status = "Open Settings > Apps > Default apps > Phone and select SmartCaller"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DIALER_ROLE) {
            defaultDialer = isDefaultDialer()
            status = if (defaultDialer) "SmartCaller is now your default phone app" else "Default phone app was not changed"
        }
    }

    private fun requestBasicPermissions() {
        val needed = buildList {
            if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CALL_PHONE)
            if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.READ_CALL_LOG)
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
    }

    private fun placeCall() {
        val clean = number.trim()
        if (clean.isEmpty()) {
            status = "Enter a phone number"
            return
        }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQUEST_CALL_PERMISSION)
            return
        }
        try {
            getSystemService(TelecomManager::class.java).placeCall(Uri.parse("tel:${Uri.encode(clean)}"), Bundle())
            status = "Calling $clean…"
        } catch (_: SecurityException) {
            status = "Phone permission is required to place calls"
        }
    }

    private fun refreshCallLogs() {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return
        val fresh = mutableListOf<CallLogItem>()
        contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
            null, null, "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            val typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE)
            val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)
            while (cursor.moveToNext() && fresh.size < 30) {
                val phone = cursor.getString(numberIndex).orEmpty()
                val type = when (cursor.getInt(typeIndex)) {
                    CallLog.Calls.INCOMING_TYPE -> "Incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                    CallLog.Calls.MISSED_TYPE -> "Missed"
                    CallLog.Calls.REJECTED_TYPE -> "Rejected"
                    else -> "Call"
                }
                val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                    .format(java.util.Date(cursor.getLong(dateIndex)))
                fresh.add(CallLogItem(phone, type, date))
            }
        }
        logs.clear()
        logs.addAll(fresh)
    }

    data class CallLogItem(val number: String, val type: String, val date: String)

    companion object {
        private const val REQUEST_DIALER_ROLE = 3001
        private const val REQUEST_PERMISSIONS = 3002
        private const val REQUEST_CALL_PERMISSION = 3003
    }
}

@Composable
private fun SmartCallerHome(
    number: String,
    onNumberChange: (String) -> Unit,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onCall: () -> Unit,
    onMakeDefault: () -> Unit,
    isDefault: Boolean,
    status: String,
    logs: List<MainActivity.CallLogItem>,
    searchNumber: String,
    onSearchNumberChange: (String) -> Unit,
    searchResult: CallerRecord?,
    onSearch: () -> Unit
) {
    var selectedTab by androidx.compose.runtime.remember { mutableStateOf("All") }

    Column(
        Modifier.fillMaxSize().background(SmartBackground).padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SmartCaller", color = SmartBlueDark, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("Your phone, smarter", color = SmartMuted, style = MaterialTheme.typography.bodyMedium)
            }
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(if (isDefault) SmartGreen else Color(0xFFE9EEF4)),
                contentAlignment = Alignment.Center
            ) {
                Text(if (isDefault) "✓" else "☎", color = if (isDefault) Color.White else SmartBlue, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = if (isDefault) Color(0xFFE8F7EF) else Color(0xFFEAF3FF))
        ) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (isDefault) "SmartCaller is your default phone" else "Complete your SmartCaller setup", fontWeight = FontWeight.Bold, color = if (isDefault) SmartGreenDark else SmartBlueDark)
                    Text(if (isDefault) "Calls, contacts and call history are ready." else "Set SmartCaller as your default phone app for the full experience.", color = SmartMuted, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onMakeDefault,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (isDefault) SmartGreen else SmartBlue)
                ) { Text(if (isDefault) "Default ✓" else "Set Default") }
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = number,
            onValueChange = onNumberChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold),
            label = { Text("Phone number") },
            placeholder = { Text("Enter number") },
            trailingIcon = { if (number.isNotEmpty()) Text("⌫", modifier = Modifier.padding(12.dp).clickable { onBackspace() }) }
        )

        Spacer(Modifier.height(8.dp))
        val keys = listOf("1","2","3","4","5","6","7","8","9","*","0","#")
        keys.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { key ->
                    Button(
                        onClick = { onDigit(key) },
                        modifier = Modifier.size(58.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = SmartSurface, contentColor = SmartText),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp)
                    ) { Text(key, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium) }
                }
            }
            Spacer(Modifier.height(5.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBackspace, modifier = Modifier.size(48.dp), shape = CircleShape) { Text("⌫") }
            Spacer(Modifier.width(20.dp))
            Button(
                onClick = onCall,
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = SmartGreen)
            ) { Text("☎", color = Color.White, style = MaterialTheme.typography.titleLarge) }
        }
        Text(status, Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = TextAlign.Center, color = SmartMuted, style = MaterialTheme.typography.labelSmall)

        Spacer(Modifier.height(10.dp))
        Text("Call history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = SmartText)
        Spacer(Modifier.height(6.dp))

        val tabs = listOf("All", "Missed", "Received", "Dialled")
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFEAF0F6)).padding(4.dp)) {
            tabs.forEach { tab ->
                val active = selectedTab == tab
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (active) SmartBlue else Color.Transparent).clickable { selectedTab = tab }.padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(tab, color = if (active) Color.White else SmartMuted, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        val filteredLogs = logs.filter {
            when (selectedTab) {
                "Missed" -> it.type == "Missed"
                "Received" -> it.type == "Incoming"
                "Dialled" -> it.type == "Outgoing"
                else -> true
            }
        }

        Spacer(Modifier.height(7.dp))
        if (filteredLogs.isEmpty()) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = SmartSurface)) {
                Text("No ${selectedTab.lowercase()} calls yet", Modifier.fillMaxWidth().padding(18.dp), textAlign = TextAlign.Center, color = SmartMuted)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.weight(1f)) {
                items(filteredLogs) { item ->
                    val missed = item.type == "Missed"
                    val outgoing = item.type == "Outgoing"
                    val accent = if (missed) SmartRed else if (outgoing) SmartBlue else SmartGreen
                    val arrow = if (missed) "↙" else if (outgoing) "↗" else "↘"
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp), colors = CardDefaults.cardColors(containerColor = SmartSurface)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                                Text(arrow, color = accent, fontWeight = FontWeight.Bold)
                            }
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(item.number, fontWeight = FontWeight.SemiBold)
                                Text("${item.type} • ${item.date}", color = SmartMuted, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("☎", color = SmartGreen, modifier = Modifier.padding(6.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = searchNumber, onValueChange = onSearchNumberChange, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp), label = { Text("Search caller number") }, trailingIcon = { Text("⌕", modifier = Modifier.padding(12.dp).clickable { onSearch() }) })
        searchResult?.let { record ->
            Text("${record.displayName ?: record.number} • ${ReputationEngine.label(record)}", Modifier.padding(top = 5.dp), color = SmartBlueDark, style = MaterialTheme.typography.bodySmall)
        }
    }
}
