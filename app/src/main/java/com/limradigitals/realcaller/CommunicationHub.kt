package com.limradigitals.realcaller

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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


@Composable
fun SmartDashboardHome(
    onPhone: () -> Unit,
    onMessages: () -> Unit,
    onStatus: () -> Unit,
    onSettings: () -> Unit,
    onProfile: () -> Unit
) {
    var section by remember { mutableStateOf(0) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Smart", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = HubBlue)
                Text("Your private communication hub", color = HubMuted)
            }
            TextButton(onClick = onProfile) { Text("Profile", color = HubBlue) }
        }

        Spacer(Modifier.height(14.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = Color.White,
            shadowElevation = 1.dp
        ) {
            Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Chats", "Calls", "Status").forEachIndexed { index, label ->
                    val active = section == index
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = if (active) HubBlue else Color.Transparent,
                        onClick = { section = index }
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

        Spacer(Modifier.height(14.dp))

        when (section) {
            0 -> {
                Text("Messages", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text("Private chats with your SmartCaller contacts", color = HubMuted)
                Spacer(Modifier.height(10.dp))
                SmartDashboardTile("✉", "New message", "Start a SmartCaller-to-SmartCaller chat", HubGreen, onMessages)
                SmartDashboardTile("💬", "Recent conversations", "Your chats, photos, files and voice messages", HubBlue, onMessages)
                Spacer(Modifier.height(10.dp))
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("No conversations yet", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("Your SmartCaller chats will appear here.", color = HubMuted)
                    }
                }
            }
            1 -> {
                Text("SmartCaller calls", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text("App-to-app audio and video calls — separate from SIM calls", color = HubMuted)
                Spacer(Modifier.height(10.dp))
                SmartDashboardTile("☎", "New app call", "Call another SmartCaller user over the internet", HubBlue, onMessages)
                SmartDashboardTile("◷", "Call history", "SmartCaller app calls will be listed here", HubGreen, onMessages)
                Spacer(Modifier.height(10.dp))
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("SmartCaller network", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("These calls are part of the Smart communication network. SIM/cellular calls stay in the Phone section.", color = HubMuted)
                    }
                }
            }
            else -> {
                Text("Your status", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text("Let your SmartCaller contacts know when you're available", color = HubMuted)
                Spacer(Modifier.height(10.dp))
                SmartDashboardTile("◉", "Availability", "Available, Busy, Do not disturb or custom", Color(0xFF7A5AF8), onStatus)
                SmartDashboardTile("◌", "24-hour updates", "Photo, video or text status updates", Color(0xFF7A5AF8), onStatus)
            }
        }

        Spacer(Modifier.height(16.dp))
        SmartDashboardTile("⚙", "Smart settings", "Privacy, notifications, profile and communication settings", Color(0xFF52606D), onSettings)
    }
}
@Composable
private fun SmartDashboardTile(
    icon: String,
    title: String,
    subtitle: String,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFF7F9FC),
        onClick = onClick
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = color.copy(alpha = .10f)) {
                Text(icon, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(subtitle, color = HubMuted, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", color = HubMuted, style = MaterialTheme.typography.titleLarge)
        }
    }
}
