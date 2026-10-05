package com.limradigitals.realcaller

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.limradigitals.realcaller.data.CallerRecord
import com.limradigitals.realcaller.data.CallerRepository

private val SCBlue = Color(0xFF1976D2)
private val SCBlueDark = Color(0xFF0D47A1)
private val SCGreen = Color(0xFF16A05D)
private val SCGreenDark = Color(0xFF087443)
private val SCBackground = Color(0xFFF5F8FC)
private val SCSurface = Color.White
private val SCText = Color(0xFF17202A)
private val SCMuted = Color(0xFF6B7785)
private val SCRed = Color(0xFFE53935)
private val SCOrange = Color(0xFFF39C12)

private val SmartCallerV2Colors = lightColorScheme(
    primary = SCBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5F1FF),
    onPrimaryContainer = SCBlueDark,
    secondary = SCGreen,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3F7EC),
    onSecondaryContainer = SCGreenDark,
    background = SCBackground,
    surface = SCSurface,
    onBackground = SCText,
    onSurface = SCText,
    outline = Color(0xFFD5DEE8)
)

class SmartCallerActivity : ComponentActivity() {
    private lateinit var repository: CallerRepository
    private var number by mutableStateOf("")
    private var searchNumber by mutableStateOf("")
    private var searchResult by mutableStateOf<CallerRecord?>(null)
    private var status by mutableStateOf("Ready to call")
    private var defaultDialer by mutableStateOf(false)
    private val logs = mutableStateListOf<CallLogItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = CallerRepository(applicationContext)
        repository.seedDemoData()
        number = intent?.data?.schemeSpecificPart.orEmpty()
        defaultDialer = isDefaultDialer()
        requestBasicPermissions()
        refreshCallLogs()

        setContent {
            MaterialTheme(colorScheme = SmartCallerV2Colors) {
                Surface(modifier = Modifier.fillMaxSize(), color = SCBackground) {
                    SmartCallerV2Home(
                        number = number,
                        onNumberChange = { number = sanitizeNumber(it) },
                        onDigit = { number += it },
                        onBackspace = { if (number.isNotEmpty()) number = number.dropLast(1) },
                        onCall = ::placeCall,
                        onMakeDefault = ::requestDefaultDialer,
                        isDefault = defaultDialer,
                        status = status,
                        logs = logs,
                        searchNumber = searchNumber,
                        onSearchNumberChange = { searchNumber = sanitizeNumber(it) },
                        searchResult = searchResult,
                        onSearch = {
                            searchResult = if (searchNumber.isBlank()) null else repository.lookup(searchNumber)
                        }
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

    private fun sanitizeNumber(value: String): String =
        value.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }

    private fun isDefaultDialer(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) &&
            roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
    }

    private fun requestDefaultDialer() {
        if (isDefaultDialer()) {
            status = "SmartCaller is already your default phone app"
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                try {
                    status = "Choose SmartCaller in the system dialog"
                    startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQUEST_DIALER_ROLE)
                    return
                } catch (_: Exception) { }
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            status = "Open Default phone app and select SmartCaller"
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    @Deprecated("Android calls this callback for the role request result")
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
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.READ_PHONE_STATE)
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
        if (!isDefaultDialer()) {
            status = "Set SmartCaller as the default phone app first"
            requestDefaultDialer()
            return
        }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQUEST_CALL_PERMISSION)
            return
        }
        try {
            getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", clean, null), Bundle())
            status = "Calling $clean…"
        } catch (_: SecurityException) {
            status = "Phone permission is required to place calls"
        } catch (_: Exception) {
            status = "Unable to start the call"
        }
    }

    private fun refreshCallLogs() {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return
        val fresh = mutableListOf<CallLogItem>()
        contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.CACHED_NAME),
            null,
            null,
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            val typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE)
            val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)
            val cachedNameIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
            while (cursor.moveToNext() && fresh.size < 100) {
                val phone = cursor.getString(numberIndex).orEmpty()
                val cachedName = if (cachedNameIndex >= 0) cursor.getString(cachedNameIndex)?.takeIf { it.isNotBlank() } else null
                val contactName = cachedName ?: lookupContactName(phone)
                val type = when (cursor.getInt(typeIndex)) {
                    CallLog.Calls.INCOMING_TYPE -> "Received"
                    CallLog.Calls.OUTGOING_TYPE -> "Dialled"
                    CallLog.Calls.MISSED_TYPE -> "Missed"
                    CallLog.Calls.REJECTED_TYPE -> "Rejected"
                    else -> "Call"
                }
                val timestamp = cursor.getLong(dateIndex)
                val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                    .format(java.util.Date(timestamp))
                fresh.add(CallLogItem(phone, contactName, type, date, timestamp))
            }
        }
        logs.clear()
        logs.addAll(fresh)
    }

    private fun lookupContactName(phone: String): String? {
        if (phone.isBlank() || checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    data class CallLogItem(
        val number: String,
        val name: String?,
        val type: String,
        val date: String,
        val timestamp: Long
    )

    companion object {
        private const val REQUEST_DIALER_ROLE = 3101
        private const val REQUEST_PERMISSIONS = 3102
        private const val REQUEST_CALL_PERMISSION = 3103
    }
}

private data class CallerGroup(
    val key: String,
    val number: String,
    val name: String?,
    val calls: List<SmartCallerActivity.CallLogItem>
) {
    val latest: SmartCallerActivity.CallLogItem get() = calls.maxByOrNull { it.timestamp } ?: calls.first()
    val missedCount: Int get() = calls.count { it.type == "Missed" }
}

private fun normalizeForGrouping(number: String): String {
    val digits = number.filter { it.isDigit() }
    return if (digits.length > 10) digits.takeLast(10) else digits
}

private fun initialsFor(name: String?): String {
    if (name.isNullOrBlank()) return "?"
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return if (parts.size >= 2) "${parts.first().first()}${parts.last().first()}".uppercase() else parts.first().take(1).uppercase()
}

@Composable
private fun SmartCallerV2Home(
    number: String,
    onNumberChange: (String) -> Unit,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onCall: () -> Unit,
    onMakeDefault: () -> Unit,
    isDefault: Boolean,
    status: String,
    logs: List<SmartCallerActivity.CallLogItem>,
    searchNumber: String,
    onSearchNumberChange: (String) -> Unit,
    searchResult: CallerRecord?,
    onSearch: () -> Unit
) {
    var selectedTab by remember { mutableStateOf("All") }
    var selectedCaller by remember { mutableStateOf<CallerGroup?>(null) }
    val tabs = listOf("All", "Missed", "Received", "Dialled")
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")

    val filteredLogs = logs.filter {
        when (selectedTab) {
            "Missed" -> it.type == "Missed"
            "Received" -> it.type == "Received"
            "Dialled" -> it.type == "Dialled"
            else -> true
        }
    }
    val groups = filteredLogs.groupBy { normalizeForGrouping(it.number) }
        .map { (key, calls) ->
            val latest = calls.maxByOrNull { it.timestamp } ?: calls.first()
            CallerGroup(key, latest.number, calls.firstOrNull { !it.name.isNullOrBlank() }?.name, calls.sortedByDescending { it.timestamp })
        }
        .sortedByDescending { it.latest.timestamp }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SCBackground),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SmartCaller", color = SCBlueDark, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                    Text("Your phone, smarter", color = SCMuted, style = MaterialTheme.typography.bodyMedium)
                }
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(if (isDefault) SCGreen else Color(0xFFE8EEF5)),
                    contentAlignment = Alignment.Center
                ) { Text(if (isDefault) "✓" else "☎", color = if (isDefault) Color.White else SCBlue, fontWeight = FontWeight.Bold) }
            }
        }

        item {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = if (isDefault) Color(0xFFE8F7EF) else Color(0xFFEAF3FF))
            ) {
                Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (isDefault) "SmartCaller is your default phone" else "Complete your SmartCaller setup", fontWeight = FontWeight.Bold, color = if (isDefault) SCGreenDark else SCBlueDark)
                        Text(if (isDefault) "Calls, contacts and call history are ready." else "Set SmartCaller as your default phone app.", color = SCMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onMakeDefault, shape = RoundedCornerShape(11.dp), colors = ButtonDefaults.buttonColors(containerColor = if (isDefault) SCGreen else SCBlue)) {
                        Text(if (isDefault) "Default ✓" else "Set Default")
                    }
                }
            }
        }

        item {
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
        }

        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = SCSurface)) {
                Column(Modifier.padding(vertical = 12.dp, horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Dial pad", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = SCText, modifier = Modifier.weight(1f))
                        Text(status, color = SCMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    keys.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { key ->
                                Button(onClick = { onDigit(key) }, modifier = Modifier.size(54.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF4F7FA), contentColor = SCText)) {
                                    Text(key, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onBackspace, modifier = Modifier.size(46.dp), shape = CircleShape) { Text("⌫") }
                        Spacer(Modifier.width(22.dp))
                        Button(onClick = onCall, modifier = Modifier.size(64.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = SCGreen)) {
                            Icon(painter = painterResource(id = R.drawable.ic_call_green), contentDescription = "Call", modifier = Modifier.size(28.dp), tint = Color.Unspecified)
                        }
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Recent calls", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = SCText, modifier = Modifier.weight(1f))
                Text("${groups.size} people", color = SCMuted, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFEAF0F6)).padding(4.dp)) {
                tabs.forEach { tab ->
                    val active = selectedTab == tab
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (active) SCBlue else Color.Transparent).clickable { selectedTab = tab }.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                        Text(tab, color = if (active) Color.White else SCMuted, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        if (groups.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = SCSurface)) {
                    Text("No ${selectedTab.lowercase()} calls yet", Modifier.fillMaxWidth().padding(22.dp), textAlign = TextAlign.Center, color = SCMuted)
                }
            }
        } else {
            items(groups, key = { it.key }) { group ->
                val latest = group.latest
                val isMissed = latest.type == "Missed"
                val isDialled = latest.type == "Dialled"
                val accent = when {
                    isMissed -> SCRed
                    isDialled -> SCBlue
                    else -> SCGreen
                }
                val name = group.name ?: "Unknown caller"
                Card(
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFDCE4EC), RoundedCornerShape(17.dp)).clickable { selectedCaller = group },
                    shape = RoundedCornerShape(17.dp),
                    colors = CardDefaults.cardColors(containerColor = SCSurface)
                ) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.12f)).border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(initialsFor(group.name), color = accent, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.border(1.dp, Color(0xFFD8E1EA), RoundedCornerShape(7.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) {
                                    Text(name, color = SCText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                }
                                if (group.calls.size > 1) {
                                    Spacer(Modifier.width(6.dp))
                                    Box(Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0xFFF0F4F8)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                                        Text("${group.calls.size}", color = SCMuted, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(group.number, color = SCMuted, style = MaterialTheme.typography.bodySmall)
                            Text("${latest.type} • ${latest.date}", color = accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        }
                        Text("›", color = SCMuted, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF3FF))) {
                Column(Modifier.padding(13.dp)) {
                    Text("Caller search", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = SCBlueDark)
                    Spacer(Modifier.height(7.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = searchNumber, onValueChange = onSearchNumberChange, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(13.dp), placeholder = { Text("Phone number") })
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onSearch, shape = RoundedCornerShape(12.dp)) { Text("Search") }
                    }
                    searchResult?.let { result ->
                        Spacer(Modifier.height(8.dp))
                        Text(result.displayName ?: "Unknown caller", fontWeight = FontWeight.Bold, color = SCBlueDark)
                        Text(result.number, color = SCMuted)
                        result.category?.let { Text(it, color = SCMuted, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }

    selectedCaller?.let { group ->
        AlertDialog(
            onDismissRequest = { selectedCaller = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(50.dp).clip(RoundedCornerShape(14.dp)).background(SCBlue.copy(alpha = 0.12f)).border(1.dp, SCBlue.copy(alpha = 0.25f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        Text(initialsFor(group.name), color = SCBlue, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(group.name ?: "Unknown caller", fontWeight = FontWeight.ExtraBold, color = SCText)
                        Text(group.number, color = SCMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        SummaryChip("${group.calls.size} calls", SCBlue)
                        if (group.missedCount > 0) SummaryChip("${group.missedCount} missed", SCRed)
                    }
                    Text("Call history", fontWeight = FontWeight.Bold, color = SCText)
                    group.calls.forEach { call ->
                        val color = when (call.type) { "Missed" -> SCRed; "Dialled" -> SCBlue; else -> SCGreen }
                        Row(Modifier.fillMaxWidth().border(1.dp, Color(0xFFE0E6ED), RoundedCornerShape(10.dp)).padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(30.dp).clip(CircleShape).background(color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                                Text(when (call.type) { "Missed" -> "↙"; "Dialled" -> "↗"; else -> "↘" }, color = color, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(call.type, fontWeight = FontWeight.SemiBold, color = color)
                                Text(call.date, color = SCMuted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { Text("Close", color = SCBlue, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { selectedCaller = null }.padding(8.dp)) }
        )
    }
}

@Composable
private fun SummaryChip(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.10f)).padding(horizontal = 8.dp, vertical = 5.dp)) {
        Text(text, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
    }
}
