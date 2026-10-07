package com.limradigitals.realcaller

import android.os.Bundle
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class CallDiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val telecom = getSystemService(TelecomManager::class.java)
            val accounts = try { telecom.getCallCapablePhoneAccounts() } catch (_: Exception) { emptyList() }
            val event = CallDiagnostics.lastEvent(this)
            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    TextButton(onClick = { finish() }) { Text("Back") }
                    Text("Call diagnostics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(14.dp))
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(18.dp)) {
                            Text("Last Telecom event", color = Color(0xFF718096))
                            Spacer(Modifier.height(8.dp))
                            Text(event, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Available call accounts: ${accounts.size}", fontWeight = FontWeight.Bold)
                    accounts.forEachIndexed { index, handle ->
                        Text("SIM ${index + 1}: ${handle.componentName.flattenToShortString()} / ${handle.id}", color = Color(0xFF718096), modifier = Modifier.padding(top = 6.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("This diagnostic data stays on the device. It is intended to tell us whether the request reached Telecom, which SIM was selected, and what disconnect cause Android reported.", color = Color(0xFF718096))
                }
            }
        }
    }
}