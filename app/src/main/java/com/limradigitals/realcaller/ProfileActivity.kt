package com.limradigitals.realcaller

import android.os.Bundle
import android.net.Uri
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class ProfileActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("smartcaller_profile", MODE_PRIVATE) }
    private val pickPhoto = registerForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) { prefs.edit().putString("photo_uri", uri.toString()).apply(); render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }

    private fun render() {
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF1769D1))) {
                ProfileScreen(
                    initialName = prefs.getString("name", "").orEmpty(),
                    initialPhone = prefs.getString("phone", "").orEmpty(),
                    initialUsername = prefs.getString("username", "").orEmpty(),
                    initialBio = prefs.getString("bio", "").orEmpty(),
                    photoUri = prefs.getString("photo_uri", null),
                    onPickPhoto = { pickPhoto.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                    onSave = { name, phone, username, bio ->
                        prefs.edit().putString("name", name).putString("phone", phone).putString("username", username).putString("bio", bio).apply()
                        finish()
                    },
                    onBack = { finish() }
                )
            }
        }
    }
}

@Composable
private fun ProfileScreen(initialName: String, initialPhone: String, initialUsername: String, initialBio: String, photoUri: String?, onPickPhoto: () -> Unit, onSave: (String,String,String,String) -> Unit, onBack: () -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var phone by remember { mutableStateOf(initialPhone) }
    var username by remember { mutableStateOf(initialUsername) }
    var bio by remember { mutableStateOf(initialBio) }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Profile", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val bitmap = remember(photoUri) {
                photoUri?.let { uri ->
                    try { context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
                }
            }
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), contentDescription = "Profile photo", modifier = Modifier.size(104.dp).clip(CircleShape))
            } else {
                Surface(Modifier.size(104.dp).clip(CircleShape), shape = CircleShape, color = Color(0xFFEAF2FF)) {
                    Box(contentAlignment = Alignment.Center) { Text(if (name.isBlank()) "ME" else name.trim().take(2).uppercase(), color = Color(0xFF1769D1), fontWeight = FontWeight.ExtraBold) }
                }
            }
            TextButton(onClick = onPickPhoto) { Text("Add / change photo") }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(phone, { phone = it }, Modifier.fillMaxWidth(), label = { Text("Phone number") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true, prefix = { Text("@") }, shape = RoundedCornerShape(16.dp))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(bio, { bio = it }, Modifier.fillMaxWidth(), label = { Text("About") }, minLines = 3, shape = RoundedCornerShape(16.dp))
        Spacer(Modifier.height(18.dp))
        Button(onClick = { onSave(name.trim(), phone.trim(), username.trim().removePrefix("@"), bio.trim()) }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(17.dp)) { Text("Save profile", fontWeight = FontWeight.Bold) }
    }
}