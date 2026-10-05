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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.limradigitals.realcaller.data.CallerRepository
import java.text.DateFormat
import java.util.Date

private val Blue = Color(0xFF1976D2)
private val BlueDark = Color(0xFF0D47A1)
private val Green = Color(0xFF16A05D)
private val Red = Color(0xFFE53935)
private val Bg = Color(0xFFF5F8FC)
private val TextDark = Color(0xFF17202A)
private val Muted = Color(0xFF6B7785)

private data class CallItem(
    val number: String,
    val name: String?,
    val type: String,
    val date: String,
    val timestamp: Long,
    val verified: Boolean
)

private data class CallGroup(
    val key: String,
    val number: String,
    val name: String?,
    val calls: List<CallItem>,
    val verified: Boolean
) {
    val latest: CallItem get() = calls.maxByOrNull { it.timestamp } ?: calls.first()
}

private data class ContactItem(val id: String, val name: String, val number: String)

class SmartCallerActivityV3 : ComponentActivity() {
    private lateinit var repository: CallerRepository
    private var phone by mutableStateOf("")
    private var status by mutableStateOf("Ready to call")
    private var isDefault by mutableStateOf(false)
    private val callItems = mutableStateListOf<CallItem>()
    private val contacts = mutableStateListOf<ContactItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = CallerRepository(applicationContext)
        try { repository.seedDemoData() } catch (_: Exception) { }
        phone = intent?.data?.schemeSpecificPart.orEmpty()
        isDefault = defaultDialer()
        requestPermissionsIfNeeded()
        loadCalls()
        loadContacts()
        render()
    }

    override fun onResume() {
        super.onResume()
        isDefault = defaultDialer()
        loadCalls()
        loadContacts()
    }

    private fun render() {
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Blue,
                    secondary = Green,
                    background = Bg,
                    surface = Color.White,
                    onBackground = TextDark,
                    onSurface = TextDark
                )
            ) {
                SmartCallerHome(
                    phone = phone,
                    onPhoneChange = { phone = cleanNumber(it) },
                    onDigit = { phone += it },
                    onBackspace = { if (phone.isNotEmpty()) phone = phone.dropLast(1) },
                    onCall = ::placeCall,
                    onMakeDefault = ::makeDefault,
                    isDefault = isDefault,
                    status = status,
                    calls = callItems,
                    contacts = contacts
                )
            }
        }
    }

    private fun cleanNumber(value: String): String =
        value.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }

    private fun defaultDialer(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val roleManager = getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) &&
            roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
    }

    private fun makeDefault() {
        if (defaultDialer()) {
            status = "SmartCaller is already your default phone"
            isDefault = true
            return
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager?.isRoleAvailable(RoleManager.ROLE_DIALER) == true) {
                try {
                    startActivityForResult(
                        roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER),
                        4401
                    )
                    return
                } catch (_: Exception) { }
            }
        }
        startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = buildList {
            if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.CALL_PHONE)
            if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.READ_CALL_LOG)
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.READ_CONTACTS)
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), 4402)
    }

    private fun placeCall() {
        if (phone.isBlank()) {
            status = "Enter a phone number"
            return
        }
        if (!defaultDialer()) {
            status = "Set SmartCaller as default phone first"
            makeDefault()
            return
        }
        try {
            getSystemService(TelecomManager::class.java).placeCall(
                Uri.fromParts("tel", phone, null),
                Bundle()
            )
            status = "Calling $phone…"
        } catch (_: Exception) {
            status = "Unable to start the call"
        }
    }

    private fun loadContacts() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        val result = linkedMapOf<String, ContactItem>()
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex).orEmpty().trim()
                val number = cursor.getString(numberIndex).orEmpty().trim()
                if (name.isNotBlank() && number.isNotBlank()) {
                    val id = cursor.getString(idIndex).orEmpty()
                    result.putIfAbsent("$id|${cleanNumber(number)}", ContactItem(id, name, number))
                }
            }
        }
        contacts.clear()
        contacts.addAll(result.values)
    }

    private fun loadCalls() {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return
        val result = mutableListOf<CallItem>()
        contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.CACHED_NAME
            ),
            null,
            null,
            CallLog.Calls.DATE + " DESC"
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            val typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE)
            val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)
            val cachedNameIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
            while (cursor.moveToNext() && result.size < 200) {
                val number = cursor.getString(numberIndex).orEmpty()
                val cachedName = if (cachedNameIndex >= 0) {
                    cursor.getString(cachedNameIndex)?.takeIf { it.isNotBlank() }
                } else null
                val type = when (cursor.getInt(typeIndex)) {
                    CallLog.Calls.INCOMING_TYPE -> "Received"
                    CallLog.Calls.OUTGOING_TYPE -> "Dialled"
                    CallLog.Calls.MISSED_TYPE -> "Missed"
                    CallLog.Calls.REJECTED_TYPE -> "Rejected"
                    else -> "Call"
                }
                val timestamp = cursor.getLong(dateIndex)
                val verified = try {
                    repository.lookup(number)?.isVerifiedBusiness == true
                } catch (_: Exception) { false }
                result.add(
                    CallItem(
                        number = number,
                        name = cachedName ?: findContactName(number),
                        type = type,
                        date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp)),
                        timestamp = timestamp,
                        verified = verified
                    )
                )
            }
        }
        callItems.clear()
        callItems.addAll(result)
    }

    private fun findContactName(number: String): String? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (_: Exception) { null }
    }
}

private fun normalizedKey(number: String): String {
    val digits = number.filter { it.isDigit() }
    return if (digits.length > 10) digits.takeLast(10) else digits
}

private fun initials(name: String?): String {
    if (name.isNullOrBlank()) return "?"
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return if (parts.size > 1) {
        "${parts.first().first()}${parts.last().first()}".uppercase()
    } else parts.first().take(1).uppercase()
}

@Composable
private fun SmartCallerHome(
    phone: String,
    onPhoneChange: (String) -> Unit,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onCall: () -> Unit,
    onMakeDefault: () -> Unit,
    isDefault: Boolean,
    status: String,
    calls: List<CallItem>,
    contacts: List<ContactItem>
) {
    var tab by remember { mutableStateOf("All") }
    var selectedGroup by remember { mutableStateOf<CallGroup?>(null) }
    var showContacts by remember { mutableStateOf(false) }
    var contactSearch by remember { mutableStateOf("") }

    val filteredCalls = calls.filter {
        when (tab) {
            "Missed" -> it.type == "Missed"
            "Received" -> it.type == "Received"
            "Dialled" -> it.type == "Dialled"
            else -> true
        }
    }
    val groups = filteredCalls.groupBy { normalizedKey(it.number) }
        .map { (key, list) ->
            CallGroup(
                key = key,
                number = list.first().number,
                name = list.firstOrNull { !it.name.isNullOrBlank() }?.name,
                calls = list.sortedByDescending { it.timestamp },
                verified = list.any { it.verified }
            )
        }
        .sortedByDescending { it.latest.timestamp }
    val filteredContacts = contacts.filter {
        contactSearch.isBlank() || it.name.contains(contactSearch, true) || it.number.contains(contactSearch)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Bg),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SmartCaller", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = BlueDark)
                    Text("Your phone, smarter", color = Muted)
                }
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(if (isDefault) Green else Color(0xFFE8EEF5)),
                    contentAlignment = Alignment.Center
                ) { Text(if (isDefault) "✓" else "☎", color = if (isDefault) Color.White else Blue, fontWeight = FontWeight.Bold) }
            }
        }

        item {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(if (isDefault) Color(0xFFE8F7EF) else Color(0xFFEAF3FF))
            ) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (isDefault) "SmartCaller is your default phone" else "Complete your SmartCaller setup",
                            fontWeight = FontWeight.Bold,
                            color = if (isDefault) Color(0xFF087443) else BlueDark
                        )
                        Text(
                            if (isDefault) "Calls, contacts and call history are ready." else "Set SmartCaller as your default phone app.",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(
                        onClick = onMakeDefault,
                        shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.buttonColors(if (isDefault) Green else Blue)
                    ) { Text(if (isDefault) "Default ✓" else "Set Default") }
                }
            }
        }

        item {
            OutlinedTextField(
                value = phone,
                onValueChange = onPhoneChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold),
                label = { Text("Phone number") },
                placeholder = { Text("Enter number") }
            )
        }

        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Dial pad", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(status, color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                    listOf("1","2","3","4","5","6","7","8","9","*","0","#").chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { digit ->
                                Button(
                                    onClick = { onDigit(digit) },
                                    modifier = Modifier.size(54.dp),
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF2F5F8), contentColor = TextDark)
                                ) { Text(digit, style = MaterialTheme.typography.titleLarge) }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        OutlinedButton(onClick = onBackspace, modifier = Modifier.size(46.dp), shape = CircleShape) { Text("⌫") }
                        Spacer(Modifier.width(22.dp))
                        Button(onClick = onCall, modifier = Modifier.size(64.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(Green)) {
                            Text("☎", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Contacts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { showContacts = true }) { Text("View all") }
            }
        }
        items(contacts.take(4), key = { it.id + it.number }) { contact ->
            ContactCard(contact) { onPhoneChange(contact.number) }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Recent calls", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                Text("${groups.size} people", color = Muted, style = MaterialTheme.typography.labelMedium)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFEAF0F6)).padding(4.dp)) {
                listOf("All", "Missed", "Received", "Dialled").forEach { value ->
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (tab == value) Blue else Color.Transparent).clickable { tab = value }.padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(value, color = if (tab == value) Color.White else Muted, fontWeight = if (tab == value) FontWeight.Bold else FontWeight.Medium, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        items(groups, key = { it.key }) { group ->
            CallCard(group) { selectedGroup = group }
        }
    }

    selectedGroup?.let { group -> HistoryDialog(group) { selectedGroup = null } }

    if (showContacts) {
        AlertDialog(
            onDismissRequest = { showContacts = false },
            title = { Text("Contacts", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = contactSearch,
                        onValueChange = { contactSearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        placeholder = { Text("Search contacts") }
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(filteredContacts, key = { it.id + it.number }) { contact ->
                            ContactCard(contact) {
                                onPhoneChange(contact.number)
                                showContacts = false
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showContacts = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun Avatar(name: String?, color: Color = Blue) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.10f)).border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) { Text(initials(name), color = color, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun Badge(text: String, color: Color = Blue) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.10f)).padding(horizontal = 6.dp, vertical = 3.dp)) {
        Text(text, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ContactCard(contact: ContactItem, onSelect: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().border(1.dp, Color(0xFFDCE4EC), RoundedCornerShape(15.dp)).clickable(onClick = onSelect),
        shape = RoundedCornerShape(15.dp)
    ) {
        Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(contact.name)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(contact.name, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(5.dp))
                    Badge("✓ Contact", Green)
                }
                Text(contact.number, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Text("☎", color = Green, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun CallCard(group: CallGroup, onOpen: () -> Unit) {
    val color = when (group.latest.type) {
        "Missed" -> Red
        "Dialled" -> Blue
        else -> Green
    }
    Card(
        Modifier.fillMaxWidth().border(1.dp, Color(0xFFDCE4EC), RoundedCornerShape(17.dp)).clickable(onClick = onOpen),
        shape = RoundedCornerShape(17.dp)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(group.name, color)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.border(1.dp, Color(0xFFD8E1EA), RoundedCornerShape(7.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) {
                        Text(group.name ?: "Unknown caller", fontWeight = FontWeight.Bold)
                    }
                    if (group.verified) {
                        Spacer(Modifier.width(5.dp))
                        Badge("✓ Verified", Green)
                    }
                    if (group.calls.size > 1) {
                        Spacer(Modifier.width(5.dp))
                        Badge("${group.calls.size}")
                    }
                }
                Text(group.number, color = Muted, style = MaterialTheme.typography.bodySmall)
                Text("${group.latest.type} • ${group.latest.date}", color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall)
            }
            Text("›", color = Muted, style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun HistoryDialog(group: CallGroup, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(group.name)
                Spacer(Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(group.name ?: "Unknown caller", fontWeight = FontWeight.ExtraBold)
                        if (group.verified) {
                            Spacer(Modifier.width(5.dp))
                            Badge("✓ Verified", Green)
                        }
                    }
                    Text(group.number, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        text = {
            Column {
                Text("${group.calls.size} calls • ${group.calls.count { it.type == "Missed" }} missed", color = Muted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
                group.calls.forEach { call ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).border(1.dp, Color(0xFFE0E6EC), RoundedCornerShape(9.dp)).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(call.type, fontWeight = FontWeight.Bold, color = if (call.type == "Missed") Red else if (call.type == "Dialled") Blue else Green, modifier = Modifier.weight(1f))
                        Text(call.date, color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}
