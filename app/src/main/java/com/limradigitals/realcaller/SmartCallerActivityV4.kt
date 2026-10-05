package com.limradigitals.realcaller

import android.Manifest
import android.app.role.RoleManager
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Backspace
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

private val SCBlue = Color(0xFF1769D1)
private val SCBlueDark = Color(0xFF0B3E82)
private val SCGreen = Color(0xFF12A56A)
private val SCRed = Color(0xFFE04747)
private val SCBg = Color(0xFFF6F8FC)
private val SCTint = Color(0xFFEAF2FF)
private val SCText = Color(0xFF172033)
private val SCMuted = Color(0xFF718096)

private data class CallItem(val number: String, val name: String?, val type: String, val date: String, val timestamp: Long, val verified: Boolean)
private data class CallGroup(val key: String, val number: String, val name: String?, val calls: List<CallItem>, val verified: Boolean) {
    val latest: CallItem get() = calls.maxByOrNull { it.timestamp } ?: calls.first()
}
private data class ContactItem(val id: String, val name: String, val number: String)

class SmartCallerActivityV4 : ComponentActivity() {
    private lateinit var repository: CallerRepository
    private var phone by mutableStateOf("")
    private var status by mutableStateOf("Ready")
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
            MaterialTheme(colorScheme = lightColorScheme(primary = SCBlue, secondary = SCGreen, background = SCBg, surface = Color.White, onBackground = SCText, onSurface = SCText)) {
                SmartCallerHome(
                    phone = phone,
                    onPhoneChange = { phone = cleanNumber(it) },
                    onDigit = { phone += it },
                    onBackspace = { if (phone.isNotEmpty()) phone = phone.dropLast(1) },
                    onCall = { placeCall(phone) },
                    onCallNumber = { placeCall(it) },
                    status = status,
                    isDefault = isDefault,
                    calls = callItems,
                    contacts = contacts
                )
            }
        }
    }

    private fun cleanNumber(value: String) = value.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }

    private fun defaultDialer(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val roleManager = getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) && roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = buildList {
            if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CALL_PHONE)
            if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.READ_CALL_LOG)
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.READ_CONTACTS)
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), 4402)
    }

    private fun placeCall(number: String) {
        val target = cleanNumber(number)
        if (target.isBlank()) { status = "Enter a number"; return }
        if (!defaultDialer()) {
            status = "Set SmartCaller as default phone first"
            if (Build.VERSION.SDK_INT >= 29) {
                val role = getSystemService(RoleManager::class.java)
                if (role?.isRoleAvailable(RoleManager.ROLE_DIALER) == true) {
                    try { startActivityForResult(role.createRequestRoleIntent(RoleManager.ROLE_DIALER), 4401); return } catch (_: Exception) { }
                }
            }
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            return
        }
        try {
            getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", target, null), Bundle())
            status = "Calling…"
        } catch (_: Exception) { status = "Unable to start call" }
    }

    private fun loadContacts() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        val result = linkedMapOf<String, ContactItem>()
        contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { cursor ->
            val idIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex).orEmpty().trim()
                val number = cursor.getString(numberIndex).orEmpty().trim()
                if (name.isNotBlank() && number.isNotBlank()) {
                    val id = cursor.getString(idIndex).orEmpty()
                    result.putIfAbsent("$id|${normalizedKey(number)}", ContactItem(id, name, number))
                }
            }
        }
        contacts.clear(); contacts.addAll(result.values)
    }

    private fun loadCalls() {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return
        val result = mutableListOf<CallItem>()
        contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.CACHED_NAME), null, null, CallLog.Calls.DATE + " DESC")?.use { cursor ->
            val ni = cursor.getColumnIndex(CallLog.Calls.NUMBER); val ti = cursor.getColumnIndex(CallLog.Calls.TYPE); val di = cursor.getColumnIndex(CallLog.Calls.DATE); val ci = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
            while (cursor.moveToNext() && result.size < 300) {
                val number = cursor.getString(ni).orEmpty(); val cached = if (ci >= 0) cursor.getString(ci)?.takeIf { it.isNotBlank() } else null
                val type = when (cursor.getInt(ti)) { CallLog.Calls.INCOMING_TYPE -> "Received"; CallLog.Calls.OUTGOING_TYPE -> "Dialled"; CallLog.Calls.MISSED_TYPE -> "Missed"; CallLog.Calls.REJECTED_TYPE -> "Rejected"; else -> "Call" }
                val timestamp = cursor.getLong(di)
                val verified = try { repository.lookup(number)?.isVerifiedBusiness == true } catch (_: Exception) { false }
                result.add(CallItem(number, cached ?: findContactName(number), type, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp)), timestamp, verified))
            }
        }
        callItems.clear(); callItems.addAll(result)
    }

    private fun findContactName(number: String): String? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
        } catch (_: Exception) { null }
    }
}

private fun normalizedKey(number: String): String { val d = number.filter { it.isDigit() }; return if (d.length > 10) d.takeLast(10) else d }
private fun initials(name: String?): String { if (name.isNullOrBlank()) return "?"; val p = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }; return if (p.size > 1) "${p.first().first()}${p.last().first()}".uppercase() else p.first().take(1).uppercase() }

@Composable
private fun SmartCallerHome(phone: String, onPhoneChange: (String) -> Unit, onDigit: (String) -> Unit, onBackspace: () -> Unit, onCall: () -> Unit, onCallNumber: (String) -> Unit, status: String, isDefault: Boolean, calls: List<CallItem>, contacts: List<ContactItem>) {
    var tab by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf("All") }
    var search by remember { mutableStateOf("") }
    var selectedGroup by remember { mutableStateOf<CallGroup?>(null) }

    val groups = calls.filter { filter == "All" || it.type == filter }.groupBy { normalizedKey(it.number) }.map { (key, list) -> CallGroup(key, list.first().number, list.firstOrNull { !it.name.isNullOrBlank() }?.name, list.sortedByDescending { it.timestamp }, list.any { it.verified }) }.sortedByDescending { it.latest.timestamp }
    val filteredGroups = groups.filter { search.isBlank() || (it.name?.contains(search, true) == true) || it.number.contains(search) }
    val filteredContacts = contacts.filter { search.isBlank() || it.name.contains(search, true) || it.number.contains(search) }

    Scaffold(bottomBar = {
        NavigationBar(containerColor = Color.White) {
            NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.History, null) }, label = { Text("Recents") })
            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.Contacts, null) }, label = { Text("Contacts") })
            NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Default.Dialpad, null) }, label = { Text("Keypad") })
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().background(SCBg).padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SmartCaller", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = SCBlueDark)
                    Text(if (isDefault) "Your phone, smarter" else "Set as your default phone", color = SCMuted, style = MaterialTheme.typography.bodyMedium)
                }
                Surface(shape = RoundedCornerShape(50), color = if (isDefault) Color(0xFFE4F7EF) else Color(0xFFFFF0E5)) {
                    Text(if (isDefault) "● Ready" else "Setup", color = if (isDefault) SCGreen else Color(0xFFB7651B), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
            when (tab) {
                0 -> RecentsView(filteredGroups, search, { search = it }, filter, { filter = it }, { selectedGroup = it }, onCallNumber)
                1 -> ContactsView(filteredContacts, search, { search = it }, onCallNumber, onPhoneChange)
                else -> KeypadView(phone, onPhoneChange, onDigit, onBackspace, onCall, status)
            }
        }
    }
    selectedGroup?.let { HistoryDialog(it, onCallNumber, { selectedGroup = null }) }
}

@Composable
private fun SearchBox(value: String, onValueChange: (String) -> Unit, hint: String) {
    OutlinedTextField(value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text(hint) }, shape = RoundedCornerShape(18.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = SCBlue, unfocusedBorderColor = Color(0xFFD9E1EC), focusedContainerColor = Color.White, unfocusedContainerColor = Color.White))
}

@Composable
private fun RecentsView(groups: List<CallGroup>, search: String, onSearch: (String) -> Unit, filter: String, onFilter: (String) -> Unit, onOpen: (CallGroup) -> Unit, onCall: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SearchBox(search, onSearch, "Search people or numbers")
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Missed", "Received", "Dialled").forEach { value ->
                FilterChip(selected = filter == value, onClick = { onFilter(value) }, label = { Text(value) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = SCBlue, selectedLabelColor = Color.White))
            }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item { Text("Recent calls", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(bottom = 2.dp)) }
            items(groups, key = { it.key }) { group -> CallCard(group, { onOpen(group) }, { onCall(group.number) }) }
            if (groups.isEmpty()) item { EmptyState("No calls found") }
        }
    }
}

@Composable
private fun ContactsView(contacts: List<ContactItem>, search: String, onSearch: (String) -> Unit, onCall: (String) -> Unit, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SearchBox(search, onSearch, "Search contacts")
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item { Text("Contacts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(bottom = 2.dp)) }
            items(contacts, key = { it.id + normalizedKey(it.number) }) { contact -> ContactCard(contact, { onSelect(contact.number) }, { onCall(contact.number) }) }
            if (contacts.isEmpty()) item { EmptyState("No contacts found") }
        }
    }
}

@Composable
private fun KeypadView(phone: String, onPhoneChange: (String) -> Unit, onDigit: (String) -> Unit, onBackspace: () -> Unit, onCall: () -> Unit, status: String) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedTextField(value = phone, onValueChange = onPhoneChange, modifier = Modifier.fillMaxWidth(), singleLine = true, textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center, fontWeight = FontWeight.Bold), placeholder = { Text("Enter number", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }, leadingIcon = null, trailingIcon = { IconButton(onClick = onBackspace) { Icon(Icons.Default.Backspace, "Delete") } }, shape = RoundedCornerShape(20.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = SCBlue, unfocusedBorderColor = Color(0xFFD9E1EC), focusedContainerColor = Color.White, unfocusedContainerColor = Color.White))
        Text(status, color = SCMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 7.dp))
        Spacer(Modifier.height(4.dp))
        val rows = listOf(listOf("1","2","3"), listOf("4","5","6"), listOf("7","8","9"), listOf("*","0","#"))
        rows.forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { row.forEach { d -> Button(onClick = { onDigit(d) }, modifier = Modifier.size(70.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = SCText), elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)) { Text(d, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) } } } ; Spacer(Modifier.height(10.dp)) }
        Button(onClick = onCall, modifier = Modifier.size(68.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = SCGreen), elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)) { Icon(Icons.Default.Call, "Call", tint = Color.White) }
    }
}

@Composable
private fun Avatar(name: String?, color: Color = SCBlue) { Box(Modifier.size(52.dp).clip(CircleShape).background(color.copy(alpha = .10f)).border(1.5.dp, color.copy(alpha = .22f), CircleShape), contentAlignment = Alignment.Center) { Text(initials(name), color = color, fontWeight = FontWeight.ExtraBold) } }

@Composable
private fun CallCard(group: CallGroup, onOpen: () -> Unit, onCall: () -> Unit) {
    val color = if (group.latest.type == "Missed") SCRed else if (group.latest.type == "Dialled") SCBlue else SCGreen
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(Color.White), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(group.name, color); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(group.name ?: "Unknown caller", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    if (group.calls.size > 1) { Spacer(Modifier.width(7.dp)); Surface(shape = CircleShape, color = SCTint) { Text("${group.calls.size}", color = SCBlue, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)) } }
                }
                Text(group.number, color = SCMuted, style = MaterialTheme.typography.bodySmall)
                Text("${group.latest.type} · ${group.latest.date}", color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall)
            }
            FilledIconButton(onClick = onCall, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE8F7EF), contentColor = SCGreen)) { Icon(Icons.Default.Call, "Call") }
        }
    }
}

@Composable
private fun ContactCard(contact: ContactItem, onSelect: () -> Unit, onCall: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onSelect), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(Color.White), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(contact.name); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text(contact.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium); Text(contact.number, color = SCMuted, style = MaterialTheme.typography.bodySmall); Text("Saved contact", color = SCGreen, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall) }
            FilledIconButton(onClick = onCall, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE8F7EF), contentColor = SCGreen)) { Icon(Icons.Default.Call, "Call") }
        }
    }
}

@Composable
private fun EmptyState(text: String) { Box(Modifier.fillMaxWidth().padding(top = 50.dp), contentAlignment = Alignment.Center) { Text(text, color = SCMuted) } }

@Composable
private fun HistoryDialog(group: CallGroup, onCall: (String) -> Unit, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Row(verticalAlignment = Alignment.CenterVertically) { Avatar(group.name); Spacer(Modifier.width(10.dp)); Column { Text(group.name ?: "Unknown caller", fontWeight = FontWeight.ExtraBold); Text(group.number, color = SCMuted, style = MaterialTheme.typography.bodySmall) } } }, text = { Column { Text("${group.calls.size} calls · ${group.calls.count { it.type == "Missed" }} missed", color = SCMuted); Spacer(Modifier.height(8.dp)); group.calls.take(20).forEach { call -> Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) { Text(call.type, color = if (call.type == "Missed") SCRed else if (call.type == "Dialled") SCBlue else SCGreen, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(call.date, color = SCMuted, style = MaterialTheme.typography.labelSmall) } } } }, confirmButton = { Button(onClick = { onCall(group.number) }, colors = ButtonDefaults.buttonColors(containerColor = SCGreen)) { Icon(Icons.Default.Call, null); Spacer(Modifier.width(6.dp)); Text("Call") } }, dismissButton = { TextButton(onClick = onClose) { Text("Close") } })
}
