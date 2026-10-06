package com.limradigitals.realcaller

import android.content.Intent
import android.telecom.Call
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val MultiBlue = Color(0xFF0757A6)
private val MultiGreen = Color(0xFF159B5B)
private val MultiRed = Color(0xFFD93636)
private val MultiBg = Color(0xFFF0F5FA)

@Composable
fun MultiCallControls(modifier: Modifier = Modifier) {
    var calls by remember { mutableStateOf<List<Call>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (true) {
            calls = InCallServiceImpl.instance?.getManagedCalls()?.filter { it.state != Call.STATE_DISCONNECTED } ?: emptyList()
            delay(450)
        }
    }

    if (calls.size < 2) return

    val ringing = calls.firstOrNull { it.state == Call.STATE_RINGING }
    val active = calls.firstOrNull { it.state == Call.STATE_ACTIVE }
    val held = calls.firstOrNull { it.state == Call.STATE_HOLDING }
    val conferenceable = active?.let { current ->
        calls.firstOrNull { other -> other !== current && current.conferenceableCalls.contains(other) }
    }
    val primary = active ?: held ?: ringing ?: calls.first()

    Surface(
        modifier = modifier.padding(top = 12.dp),
        shape = RoundedCornerShape(22.dp),
        color = MultiBg
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Multiple calls", color = MultiBlue, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            calls.forEachIndexed { index, call ->
                val number = call.details.handle?.schemeSpecificPart.orEmpty().ifBlank { "Unknown caller" }
                val name = call.details.contactDisplayName?.takeIf { it.isNotBlank() } ?: number
                val label = when (call.state) {
                    Call.STATE_RINGING -> "Incoming"
                    Call.STATE_ACTIVE -> "Active"
                    Call.STATE_HOLDING -> "On hold"
                    Call.STATE_DIALING -> "Calling…"
                    Call.STATE_CONNECTING -> "Connecting…"
                    else -> "Call"
                }
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${index + 1}. $name", modifier = Modifier.weight(1f), maxLines = 1)
                    Text(label, color = when (call.state) {
                        Call.STATE_ACTIVE -> MultiGreen
                        Call.STATE_RINGING -> MultiRed
                        else -> MultiBlue
                    }, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ringing != null) {
                    Button(
                        onClick = { InCallServiceImpl.instance?.answerCall(ringing) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MultiGreen)
                    ) { Text("Answer") }
                }
                if (active != null && active.details.canHold()) {
                    OutlinedButton(
                        onClick = { InCallServiceImpl.instance?.holdCall(active) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Hold") }
                }
                if (held != null) {
                    Button(
                        onClick = { InCallServiceImpl.instance?.unholdCall(held) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MultiBlue)
                    ) { Text("Resume") }
                }
            }

            if (active != null && held != null) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { InCallServiceImpl.instance?.swapCalls(active, held) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Swap") }
                    if (conferenceable != null) {
                        Button(
                            onClick = { InCallServiceImpl.instance?.conferenceCalls(active, conferenceable) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MultiGreen)
                        ) { Text("Merge") }
                    }
                }
            }

            if (primary.details.hasProperty(Call.Details.PROPERTY_CONFERENCE)) {
                Spacer(Modifier.height(8.dp))
                Text("Conference call", color = MultiBlue, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        // Open SmartCaller so the user can place another call. Telecom handles hold/concurrency.
                        val context = androidx.compose.ui.platform.LocalContext.current
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Add call") }
                if (active != null) {
                    OutlinedButton(
                        onClick = { InCallServiceImpl.instance?.disconnectCall(active) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("End active", color = MultiRed) }
                }
            }
        }
    }
}
