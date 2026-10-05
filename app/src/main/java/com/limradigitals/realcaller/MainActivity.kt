package com.limradigitals.realcaller

import android.Manifest
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.limradigitals.realcaller.data.CallerRecord
import com.limradigitals.realcaller.data.CallerRepository
import com.limradigitals.realcaller.data.ReputationEngine
import com.limradigitals.realcaller.screening.RealCallerScreeningService

class MainActivity : ComponentActivity() {
    private lateinit var repository: CallerRepository
    private var searchResult by mutableStateOf<CallerRecord?>(null)
    private var searchNumber by mutableStateOf("")
    private var message by mutableStateOf("SmartCaller is ready")
    private var diagnostics by mutableStateOf(DiagnosticsState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = CallerRepository(applicationContext)
        repository.seedDemoData()
        requestNotificationPermission()
        refreshDiagnostics()

        setContent {
            Surface(color = MaterialTheme.colorScheme.background) {
                SmartCallerHome(
                    searchNumber = searchNumber,
                    onNumberChange = { searchNumber = it },
                    result = searchResult,
                    message = message,
                    overlayEnabled = diagnostics.overlayEnabled,
                    diagnostics = diagnostics,
                    onSearch = {
                        searchResult = repository.lookup(searchNumber)
                        message = if (searchResult == null) "No local match — cloud lookup will be added in V1.1" else "Local match found instantly"
                    },
                    onSetup = ::requestCallerIdRole,
                    onOverlaySetup = ::requestOverlayPermission,
                    onTestOverlay = { RealCallerScreeningService.testOverlay(this) },
                    onRefreshDiagnostics = ::refreshDiagnostics
                )
            }
        }

        handleUpdateIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUpdateIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::repository.isInitialized) refreshDiagnostics()
    }

    private fun handleUpdateIntent(intent: Intent?) {
        val number = intent?.getStringExtra("update_number") ?: return
        intent.removeExtra("update_number")
        showUpdateCallInfoDialog(number)
    }

    private fun showUpdateCallInfoDialog(number: String) {
        val current = repository.lookup(number)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 8, 40, 0)
        }
        val nameInput = EditText(this).apply {
            hint = "Caller / business name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(current?.displayName.orEmpty())
        }
        val categoryInput = EditText(this).apply {
            hint = "Category (Business, Delivery, Bank, Spam...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(current?.category.orEmpty())
        }
        container.addView(nameInput)
        container.addView(categoryInput)

        AlertDialog.Builder(this)
            .setTitle("Update call information")
            .setMessage("$number\nHelp SmartCaller improve this caller record.")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                repository.updateCallerInfo(number, nameInput.text.toString(), categoryInput.text.toString())
                searchNumber = number
                searchResult = repository.lookup(number)
                message = "Caller information updated locally"
            }
            .show()
    }

    private fun refreshDiagnostics() {
        val prefs = getSharedPreferences("smartcaller_diagnostics", MODE_PRIVATE)
        val lastNumber = prefs.getString("last_number", null)
        val lastAt = prefs.getLong("last_callback_at", 0L)
        val callbackSeen = lastAt > 0L
        diagnostics = DiagnosticsState(
            roleEnabled = isCallScreeningRoleHeld(),
            overlayEnabled = canDrawOverlays(),
            notificationEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            callbackSeen = callbackSeen,
            lastNumber = lastNumber,
            lastCallbackAt = if (lastAt > 0L) java.text.DateFormat.getTimeInstance().format(java.util.Date(lastAt)) else null
        )
        message = when {
            !diagnostics.roleEnabled -> "Enable SmartCaller as the caller ID & spam protection app first"
            !diagnostics.overlayEnabled -> "Allow display over other apps"
            else -> "SmartCaller is ready for a real call"
        }
    }

    private fun isCallScreeningRoleHeld(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = getSystemService(RoleManager::class.java)
        return roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
            roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        if (!canDrawOverlays() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        } else {
            message = "SmartCaller overlay is enabled"
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
    }

    private fun requestCallerIdRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
            ) {
                startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 1001)
                message = "Choose SmartCaller as your caller ID & spam protection app"
            } else {
                message = "SmartCaller is already selected for caller screening"
            }
        } else {
            message = "Caller screening requires Android 10 or newer"
        }
        refreshDiagnostics()
    }

    data class DiagnosticsState(
        val roleEnabled: Boolean = false,
        val overlayEnabled: Boolean = false,
        val notificationEnabled: Boolean = false,
        val callbackSeen: Boolean = false,
        val lastNumber: String? = null,
        val lastCallbackAt: String? = null
    )
}

@androidx.compose.runtime.Composable
private fun SmartCallerHome(
    searchNumber: String,
    onNumberChange: (String) -> Unit,
    result: CallerRecord?,
    message: String,
    overlayEnabled: Boolean,
    diagnostics: MainActivity.DiagnosticsState,
    onSearch: () -> Unit,
    onSetup: () -> Unit,
    onOverlaySetup: () -> Unit,
    onTestOverlay: () -> Unit,
    onRefreshDiagnostics: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("SmartCaller", style = MaterialTheme.typography.headlineLarge)
        Text("Know who is calling. Block what matters.", style = MaterialTheme.typography.titleMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Caller ID & Spam Shield", style = MaterialTheme.typography.titleLarge)
                Text("Local-first protection works before cloud enrichment.")
                Button(onClick = onSetup, modifier = Modifier.fillMaxWidth()) { Text("Enable SmartCaller") }
                OutlinedButton(onClick = onOverlaySetup, modifier = Modifier.fillMaxWidth()) {
                    Text(if (overlayEnabled) "✓ Display over other apps enabled" else "Allow display over other apps")
                }
                Text(
                    if (overlayEnabled) "SmartCaller can show a caller card above the phone screen."
                    else "Required for the SmartCaller caller card to appear during an incoming call.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SmartCaller Diagnostic", style = MaterialTheme.typography.titleLarge)
                Text("Caller screening role: ${if (diagnostics.roleEnabled) "🟢 Enabled" else "🔴 Not enabled"}")
                Text("Overlay permission: ${if (diagnostics.overlayEnabled) "🟢 Enabled" else "🔴 Not enabled"}")
                Text("Notifications: ${if (diagnostics.notificationEnabled) "🟢 Enabled" else "🔴 Not enabled"}")
                Text("Screening callback: ${if (diagnostics.callbackSeen) "🟢 Received" else "⚪ Not received yet"}")
                diagnostics.lastNumber?.let { Text("Last number: $it") }
                diagnostics.lastCallbackAt?.let { Text("Last callback: $it") }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onRefreshDiagnostics) { Text("Refresh") }
                    Button(onClick = onTestOverlay) { Text("Test Caller Card") }
                }
            }
        }

        Text("Search a phone number", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = searchNumber,
            onValueChange = onNumberChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Phone number") },
            placeholder = { Text("e.g. 9999999999") }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onSearch) { Text("Search") }
            TextButton(onClick = { onNumberChange("9999999999") }) { Text("Try demo") }
        }

        result?.let { record -> CallerCard(record) }

        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Text("V1 modules: Caller ID • Spam Shield • Reputation • Business Intelligence • Community Reports", style = MaterialTheme.typography.bodySmall)
        Text("smartcaller.in", style = MaterialTheme.typography.bodySmall)
    }
}

@androidx.compose.runtime.Composable
private fun CallerCard(record: CallerRecord) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(record.displayName ?: record.number, style = MaterialTheme.typography.headlineSmall)
            record.category?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            Text("${ReputationEngine.label(record)} • ${record.reputationScore}/100")
            if (record.isVerifiedBusiness) Text("✓ Verified Business")
            record.rating?.let { Text("★ $it Google rating") }
            record.address?.let { Text("📍 $it") }
            record.openingHours?.let { Text("🕐 $it") }
            record.website?.let { Text("🌐 $it") }
            if (record.reportCount > 0) Text("${record.reportCount} community reports")
        }
    }
}
