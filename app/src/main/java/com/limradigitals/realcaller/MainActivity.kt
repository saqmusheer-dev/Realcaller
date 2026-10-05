package com.limradigitals.realcaller

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
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

class MainActivity : ComponentActivity() {
    private lateinit var repository: CallerRepository
    private var searchResult by mutableStateOf<CallerRecord?>(null)
    private var searchNumber by mutableStateOf("")
    private var message by mutableStateOf("RealCaller is ready")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = CallerRepository(applicationContext)
        repository.seedDemoData()

        setContent {
            Surface(color = MaterialTheme.colorScheme.background) {
                RealCallerHome(
                    searchNumber = searchNumber,
                    onNumberChange = { searchNumber = it },
                    result = searchResult,
                    message = message,
                    onSearch = {
                        searchResult = repository.lookup(searchNumber)
                        message = if (searchResult == null) "No local match — cloud lookup will be added in V1.1" else "Local match found instantly"
                    },
                    onSetup = ::requestCallerIdRole
                )
            }
        }
    }

    private fun requestCallerIdRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
            ) {
                startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 1001)
                message = "Choose RealCaller as your caller ID & spam protection app"
            } else {
                message = "RealCaller is already selected for caller screening"
            }
        } else {
            message = "Caller screening requires Android 10 or newer"
        }
    }
}

@androidx.compose.runtime.Composable
private fun RealCallerHome(
    searchNumber: String,
    onNumberChange: (String) -> Unit,
    result: CallerRecord?,
    message: String,
    onSearch: () -> Unit,
    onSetup: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("RealCaller", style = MaterialTheme.typography.headlineLarge)
        Text("Know who is calling. Block what matters.", style = MaterialTheme.typography.titleMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Caller ID & Spam Shield", style = MaterialTheme.typography.titleLarge)
                Text("Local-first protection works before cloud enrichment.")
                Button(onClick = onSetup, modifier = Modifier.fillMaxWidth()) {
                    Text("Enable RealCaller")
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

        result?.let { record ->
            CallerCard(record)
        }

        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Text("V1 modules: Caller ID • Spam Shield • Reputation • Business Intelligence • Community Reports", style = MaterialTheme.typography.bodySmall)
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
