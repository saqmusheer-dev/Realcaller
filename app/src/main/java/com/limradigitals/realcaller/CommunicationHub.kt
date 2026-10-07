package com.limradigitals.realcaller

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val HubBlue = Color(0xFF1769D1)
private val HubGreen = Color(0xFF12A56A)
private val HubMuted = Color(0xFF718096)

@Composable
fun SmartHubSwitcher(selected: Int, onSelected: (Int) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        shadowElevation = 1.dp
    ) {
        Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Phone", "Messages", "Status").forEachIndexed { index, label ->
                val active = selected == index
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    color = if (active) HubBlue else Color.Transparent,
                    onClick = { onSelected(index) }
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(vertical = 10.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = if (active) Color.White else HubMuted,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun SmartMessagesHome(onProfile: () -> Unit) {
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Messages", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text("SmartCaller-to-SmartCaller", color = HubMuted)
            }
            TextButton(onClick = onProfile) { Text("Profile") }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search messages or people") },
            shape = RoundedCornerShape(18.dp)
        )
        Spacer(Modifier.height(18.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp)) {
                Text("Start a conversation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Send text, emoji, photos, videos, voice messages and files. Conversations will be end-to-end ready when the messaging backend is connected.", color = HubMuted)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = HubBlue)) { Text("New message") }
                    OutlinedButton(onClick = {}) { Text("Voice call") }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Recent conversations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(50), color = Color(0xFFEAF2FF)) { Text("SC", modifier = Modifier.padding(13.dp), color = HubBlue, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("No conversations yet", fontWeight = FontWeight.Bold)
                    Text("Your chats will appear here", color = HubMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun SmartStatusHome(onProfile: () -> Unit) {
    var status by remember { mutableStateOf("Available") }
    var custom by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Status", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text("Tell people when you're available", color = HubMuted)
            }
            TextButton(onClick = onProfile) { Text("Profile") }
        }
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp)) {
                Text("Your current status", color = HubMuted)
                Spacer(Modifier.height(4.dp))
                Text(status, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = HubGreen)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("Available", "Busy", "DND").forEach { value ->
                        FilterChip(selected = status == value, onClick = { status = value }, label = { Text(value) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Custom status, e.g. In a meeting") },
                    shape = RoundedCornerShape(16.dp)
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = { if (custom.isNotBlank()) status = custom.trim() }, modifier = Modifier.fillMaxWidth()) { Text("Set status") }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("24-hour status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Photo, video or text updates with captions, viewers and replies will be added to this timeline.", color = HubMuted)
    }
}
