package com.limradigitals.realcaller

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.limradigitals.realcaller.data.CallerRecord
import com.limradigitals.realcaller.data.CallerRepository
import com.limradigitals.realcaller.data.ReputationEngine

class MainActivity : ComponentActivity() {
    private lateinit var repository: CallerRepository
    private var number by mutableStateOf("")
    private var searchNumber by mutableStateOf("")
    private var searchResult by mutableStateOf<CallerRecord?>(null)
    private var status by mutableStateOf("SmartCaller is ready")
    private var logs = mutableStateListOf<CallLogItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = CallerRepository(applicationContext)
        repository.seedDemoData()
        number = intent?.data?.schemeSpecificPart.orEmpty()
        requestBasicPermissions()
        refreshCallLogs()

        setContent {
            Surface(color = MaterialTheme.colorScheme.background) {
                SmartCallerHome(
                    number = number,
                    onNumberChange = { number = it.filter { ch -> ch.isDigit() || ch == '+' || ch == '*' || ch == '#' } },
                    onCall = ::placeCall,
                    onMakeDefault = ::requestDefaultDialer,
                    isDefault = isDefaultDialer(),
                    status = status,
                    logs = logs,
                    searchNumber = searchNumber,
                    onSearchNumberChange = { searchNumber = it },
                    searchResult = searchResult,
                    onSearch = {
                        searchResult = repository.lookup(searchNumber)
                    }
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
        refreshCallLogs()
    }

    private fun isDefaultDialer(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = getSystemService(RoleManager::class.java)
        return roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) &&
            roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
    }

    private fun requestDefaultDialer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                startActivityForResult(
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER),
                    REQUEST_DIALER_ROLE
                )
            } else {
                status = "This device does not expose the Android default dialer role"
            }
        } else {
            status = "Default dialer role requires Android 10 or newer"
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
            val telecom = getSystemService(TelecomManager::class.java)
            telecom.placeCall(Uri.parse("tel:${Uri.encode(clean)}"), Bundle())
            status = "Calling $clean…"
        } catch (e: SecurityException) {
            status = "Phone permission is required to place calls"
        }
    }

    private fun refreshCallLogs() {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return
        val fresh = mutableListOf<CallLogItem>()
        contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
            null,
            null,
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            val typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE)
            val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)
            while (cursor.moveToNext() && fresh.size < 25) {
                val phone = cursor.getString(numberIndex).orEmpty()
                val type = when (cursor.getInt(typeIndex)) {
                    CallLog.Calls.INCOMING_TYPE -> "Incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                    CallLog.Calls.MISSED_TYPE -> "Missed"
                    CallLog.Calls.REJECTED_TYPE -> "Rejected"
                    else -> "Call"
                }
                val date = java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT,
                    java.text.DateFormat.SHORT
                ).format(java.util.Date(cursor.getLong(dateIndex)))
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

@androidx.compose.runtime.Composable
private fun SmartCallerHome(
    number: String,
    onNumberChange: (String) -> Unit,
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
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("SmartCaller", style = MaterialTheme.typography.headlineLarge)
        Text("Your phone, smarter.", style = MaterialTheme.typography.titleMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Phone App", style = MaterialTheme.typography.titleLarge)
                Text(if (isDefault) "🟢 SmartCaller is your default phone app" else "🔴 SmartCaller is not the default phone app")
                Button(onClick = onMakeDefault, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isDefault) "Default Phone App Enabled" else "Make SmartCaller Default Phone")
                }
                Text(
                    "Once enabled, SmartCaller controls incoming calls, ongoing calls, outgoing calls and the dialer experience.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Dial", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = number,
                    onValueChange = onNumberChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Phone number") }
                )
                Button(onClick = onCall, modifier = Modifier.fillMaxWidth()) { Text("📞 Call") }
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Caller Search", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = searchNumber,
                    onValueChange = onSearchNumberChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search number") }
                )
                OutlinedButton(onClick = onSearch) { Text("Search") }
                searchResult?.let { record ->
                    Text(record.displayName ?: record.number, style = MaterialTheme.typography.titleMedium)
                    record.category?.let { Text(it) }
                    Text("${ReputationEngine.label(record)} • ${record.reputationScore}/100")
                }
            }
        }

        Text("Recent Calls", style = MaterialTheme.typography.titleLarge)
        if (logs.isEmpty()) {
            Text("No call history available yet.")
        } else {
            logs.forEach { item ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(item.number, style = MaterialTheme.typography.titleMedium)
                        Text("${item.type} • ${item.date}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("SmartCaller V1 • Real dialer foundation • Caller ID • Spam Shield • Call History", style = MaterialTheme.typography.bodySmall)
        Text("smartcaller.in", style = MaterialTheme.typography.bodySmall)
    }
}
