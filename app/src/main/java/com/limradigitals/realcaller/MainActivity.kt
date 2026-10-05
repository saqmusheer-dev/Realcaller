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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.limradigitals.realcaller.data.CallerRecord
import com.limradigitals.realcaller.data.CallerRepository
import com.limradigitals.realcaller.data.ReputationEngine

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
            Surface(color = MaterialTheme.colorScheme.background) {
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
        // OEM fallback: open the system Default Apps screen so the user can select SmartCaller manually.
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
            while (cursor.moveToNext() && fresh.size < 20) {
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
        logs.clear(); logs.addAll(fresh)
    }

    data class CallLogItem(val number: String, val type: String, val date: String)

    companion object {
        private const val REQUEST_DIALER_ROLE = 3001
        private const val REQUEST_PERMISSIONS = 3002
        private const val REQUEST_CALL_PERMISSION = 3003
    }
}

@androidx.compose.runtime.Composable
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
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SmartCaller", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Your phone, smarter", style = MaterialTheme.typography.bodyMedium)
            }
            Text(if (isDefault) "●" else "○", color = if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        }

        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = if (isDefault) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (isDefault) "SmartCaller is your phone app" else "Make SmartCaller your phone app", fontWeight = FontWeight.Bold)
                Text(if (isDefault) "Incoming, outgoing and ongoing calls are handled by SmartCaller." else "Unlock the full SmartCaller calling experience: incoming calls, outgoing calls and call history.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = onMakeDefault, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isDefault) "Default Phone App ✓" else "Set as Default Phone App")
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = number,
            onValueChange = onNumberChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
            label = { Text("Phone number") },
            trailingIcon = { if (number.isNotEmpty()) Text("⌫", modifier = Modifier.padding(12.dp)) }
        )

        Spacer(Modifier.height(10.dp))
        val keys = listOf("1","2","3","4","5","6","7","8","9","*","0","#")
        keys.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { key ->
                    Button(onClick = { onDigit(key) }, modifier = Modifier.size(68.dp).clip(CircleShape)) { Text(key, style = MaterialTheme.typography.titleLarge) }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(58.dp))
            Button(onClick = onCall, modifier = Modifier.size(68.dp).clip(CircleShape)) { Text("☎") }
            Spacer(Modifier.size(16.dp))
            OutlinedButton(onClick = onBackspace, modifier = Modifier.size(58.dp).clip(CircleShape)) { Text("⌫") }
        }
        Text(status, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(14.dp))
        Text("Recent Calls", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (logs.isEmpty()) {
            Text("Your recent calls will appear here.", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.weight(1f)) {
                items(logs) { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (item.type == "Missed") "↙" else if (item.type == "Outgoing") "↗" else "↘", style = MaterialTheme.typography.titleLarge)
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(item.number, fontWeight = FontWeight.SemiBold)
                                Text("${item.type} • ${item.date}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("Caller Search", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = searchNumber, onValueChange = onSearchNumberChange, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Number") })
            OutlinedButton(onClick = onSearch, modifier = Modifier.padding(start = 8.dp)) { Text("Search") }
        }
        searchResult?.let { record ->
            Text("${record.displayName ?: record.number} • ${ReputationEngine.label(record)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
