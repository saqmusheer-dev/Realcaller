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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.Delete
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

private data class V5CallItem(val number: String, val name: String?, val type: String, val date: String, val time: String, val duration: Long, val timestamp: Long, val verified: Boolean)
private data class V5CallGroup(val key: String, val number: String, val name: String?, val calls: List<V5CallItem>, val verified: Boolean) {
    val latest: V5CallItem get() = calls.maxByOrNull { it.timestamp } ?: calls.first()
}
private data class V5ContactItem(val id: String, val name: String, val number: String)

class SmartCallerActivityV5 : ComponentActivity() {
    private lateinit var repository: CallerRepository
    private var phone by mutableStateOf("")
    private var status by mutableStateOf("Ready")
    private var isDefault by mutableStateOf(false)
    private var hasActiveCall by mutableStateOf(false)
    private val callItems = mutableStateListOf<V5CallItem>()
    private val contacts = mutableStateListOf<V5ContactItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = CallerRepository(applicationContext)
        try { repository.seedDemoData() } catch (_: Exception) { }
        phone = intent?.data?.schemeSpecificPart.orEmpty()
        isDefault = defaultDialer()
        requestPermissionsIfNeeded(); loadCalls(); loadContacts(); render()
    }

    override fun onResume() { super.onResume(); isDefault = defaultDialer(); refreshActiveCall(); loadCalls(); loadContacts() }

    private fun refreshActiveCall() { hasActiveCall = try { InCallServiceImpl.instance?.getManagedCalls()?.isNotEmpty() == true } catch (_: Exception) { false } }

    private fun render() {
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = SCBlue, secondary = SCGreen, background = SCBg, surface = Color.White, onBackground = SCText, onSurface = SCText)) {
                SmartCallerHomeV5(phone, { phone = cleanNumber(it) }, { phone += it }, { if (phone.isNotEmpty()) phone = phone.dropLast(1) }, { placeCall(phone) }, { placeCall(it) }, { deleteCallerHistory(it) }, status, isDefault, hasActiveCall, { startActivity(Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }, callItems, contacts)
            }
        }
    }

    private fun cleanNumber(value: String) = value.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }

    private fun defaultDialer(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val role = getSystemService(RoleManager::class.java) ?: return false
        return role.isRoleAvailable(RoleManager.ROLE_DIALER) && role.isRoleHeld(RoleManager.ROLE_DIALER)
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = buildList {
            if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CALL_PHONE)
            if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.READ_CALL_LOG)
            if (checkSelfPermission(Manifest.permission.WRITE_CALL_LOG) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.WRITE_CALL_LOG)
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.READ_CONTACTS)
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.READ_PHONE_NUMBERS)
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
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)); return
        }
        try { getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", target, null), Bundle()); status = "Calling…" }
        catch (_: Exception) { status = "Unable to start call" }
    }

    private fun deleteCallerHistory(number: String): Int {
        val deleted = CallLogManager.deleteCallerHistory(this, number)
        loadCalls()
        android.os.Handler(mainLooper).postDelayed({ loadCalls() }, 350L)
        val message = when {
            deleted > 0 -> "Deleted $deleted call-log ${if (deleted == 1) "entry" else "entries"}"
            deleted == 0 -> "No call-log entries were deleted"
            else -> "Call-log permission is not available"
        }
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        return deleted
    }

    private fun loadContacts() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        val result = linkedMapOf<String, V5ContactItem>()
        contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { c ->
            val id = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID); val name = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME); val num = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val n = c.getString(name).orEmpty().trim(); val p = c.getString(num).orEmpty().trim()
                if (n.isNotBlank() && p.isNotBlank()) { val cid = c.getString(id).orEmpty(); result.putIfAbsent("$cid|${normalizedKey(p)}", V5ContactItem(cid, n, p)) }
            }
        }
        contacts.clear(); contacts.addAll(result.values)
    }

    private fun loadCalls() {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return
        val result = mutableListOf<V5CallItem>()
        contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.CACHED_NAME), null, null, CallLog.Calls.DATE + " DESC")?.use { c ->
            val ni = c.getColumnIndex(CallLog.Calls.NUMBER); val ti = c.getColumnIndex(CallLog.Calls.TYPE); val di = c.getColumnIndex(CallLog.Calls.DATE); val dui = c.getColumnIndex(CallLog.Calls.DURATION); val ci = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
            while (c.moveToNext() && result.size < 300) {
                val number = c.getString(ni).orEmpty(); val cached = if (ci >= 0) c.getString(ci)?.takeIf { it.isNotBlank() } else null
                val type = when (c.getInt(ti)) { CallLog.Calls.INCOMING_TYPE -> "Received"; CallLog.Calls.OUTGOING_TYPE -> "Dialled"; CallLog.Calls.MISSED_TYPE -> "Missed"; CallLog.Calls.REJECTED_TYPE -> "Rejected"; else -> "Call" }
                val ts = c.getLong(di); val verified = try { repository.lookup(number)?.isVerifiedBusiness == true } catch (_: Exception) { false }
                val duration = if (dui >= 0) c.getLong(dui) else 0L
                val date = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ts))
                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts))
                result.add(V5CallItem(number, cached ?: findContactName(number), type, date, time, duration, ts, verified))
            }
        }
        callItems.clear(); callItems.addAll(result)
    }

    private fun findContactName(number: String): String? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return try { contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)), arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null } } catch (_: Exception) { null }
    }
}

private fun normalizedKey(number: String): String { val d = number.filter { it.isDigit() }; return if (d.length > 10) d.takeLast(10) else d }
private fun initials(name: String?): String { if (name.isNullOrBlank()) return "?"; val p = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }; return if (p.size > 1) "${p.first().first()}${p.last().first()}".uppercase() else p.first().take(1).uppercase() }

@Composable
private fun SmartCallerHomeV5(phone: String, onPhoneChange: (String) -> Unit, onDigit: (String) -> Unit, onBackspace: () -> Unit, onCall: () -> Unit, onCallNumber: (String) -> Unit, onDeleteCaller: (String) -> Int, status: String, isDefault: Boolean, hasActiveCall: Boolean, onReturnToCall: () -> Unit, calls: List<V5CallItem>, contacts: List<V5ContactItem>) {
    var tab by remember { mutableStateOf(0) }; var hubTab by remember { mutableStateOf(0) }; var filter by remember { mutableStateOf("All") }; var search by remember { mutableStateOf("") }; var selectedGroup by remember { mutableStateOf<V5CallGroup?>(null) }; var deleteTarget by remember { mutableStateOf<V5CallGroup?>(null) }; var selectedCallerKeys by remember { mutableStateOf<Set<String>>(emptySet()) }; var bulkDeleteConfirm by remember { mutableStateOf(false) }; var actionTarget by remember { mutableStateOf<Pair<String, String>?>(null) }; var actionHistoryGroup by remember { mutableStateOf<V5CallGroup?>(null) }; var actionContact by remember { mutableStateOf<V5ContactItem?>(null) }
    val groups = calls.filter { filter == "All" || it.type == filter }.groupBy { normalizedKey(it.number) }.map { (key, list) -> V5CallGroup(key, list.first().number, list.firstOrNull { !it.name.isNullOrBlank() }?.name, list.sortedByDescending { it.timestamp }, list.any { it.verified }) }.sortedByDescending { it.latest.timestamp }
    val filteredGroups = groups.filter { search.isBlank() || (it.name?.contains(search, true) == true) || it.number.contains(search) }
    val filteredContacts = contacts.filter { search.isBlank() || it.name.contains(search, true) || it.number.contains(search) }

    val homeContext = androidx.compose.ui.platform.LocalContext.current
    Scaffold(bottomBar = { NavigationBar(containerColor = Color.White) {
        NavigationBarItem(selected = hubTab == 0 && tab == 0, onClick = { hubTab = 0; tab = 0 }, icon = { Text("◷") }, label = { Text("Recents") })
        NavigationBarItem(selected = hubTab == 0 && tab == 1, onClick = { hubTab = 0; tab = 1 }, icon = { Text("◎") }, label = { Text("Contacts") })
        NavigationBarItem(selected = hubTab == 0 && tab == 2, onClick = { hubTab = 0; tab = 2 }, icon = { Text("⌨") }, label = { Text("Keypad") })
        NavigationBarItem(selected = hubTab == 3, onClick = { hubTab = 3 }, icon = { Text("✦") }, label = { Text("Smart") })
    } }) { pad ->
        Column(Modifier.fillMaxSize().background(SCBg).padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("SmartCaller", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = SCBlueDark); Text("Your phone, smarter", color = SCMuted) }
                IconButton(onClick = { homeContext.startActivity(Intent(homeContext, ProfileActivity::class.java)) }) { Text("ME", color = SCBlueDark, fontWeight = FontWeight.ExtraBold) }
                Surface(shape = RoundedCornerShape(50), color = if (isDefault) Color(0xFFE4F7EF) else Color(0xFFFFF0E5)) { Text(if (isDefault) "● Ready" else "Setup", color = if (isDefault) SCGreen else Color(0xFFB7651B), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) }
            }
            if (hasActiveCall) {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = Color(0xFFE4F7EF)) {
                    Row(Modifier.fillMaxWidth().clickable(onClick = onReturnToCall).padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("●", color = SCGreen, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.width(9.dp))
                        Text("Call in progress", color = SCText, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("Return to call  ›", color = SCGreen, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
            if (hubTab <= 2) {
                SmartHubSwitcher(hubTab) { hubTab = it }
                Spacer(Modifier.height(2.dp))
            }
            when (hubTab) {
                0 -> when (tab) {
                    0 -> RecentsViewV5(filteredGroups, search, { search = it }, phone, onPhoneChange, { onCallNumber(phone) }, filter, { filter = it }, selectedCallerKeys, { key -> selectedCallerKeys = if (key in selectedCallerKeys) selectedCallerKeys - key else selectedCallerKeys + key }, { selectedGroup = it }, { deleteTarget = it }, { bulkDeleteConfirm = true }, { selectedCallerKeys = emptySet() }, onCallNumber, { group -> actionHistoryGroup = group; actionContact = null; actionTarget = (group.name ?: "Unknown caller") to group.number })
                    1 -> ContactsViewV5(filteredContacts, search, { search = it }, onCallNumber, { contact -> actionContact = contact; actionHistoryGroup = null; actionTarget = contact.name to contact.number })
                    else -> KeypadViewV5(phone, onPhoneChange, onDigit, onBackspace, onCall, status)
                }
                1 -> SmartMessagesHome { homeContext.startActivity(Intent(homeContext, ProfileActivity::class.java)) }
                2 -> SmartStatusHome { homeContext.startActivity(Intent(homeContext, ProfileActivity::class.java)) }
                3 -> SmartDashboardHome(
                    onPhone = { hubTab = 0; tab = 0 },
                    onMessages = { hubTab = 1 },
                    onStatus = { hubTab = 2 },
                    onSettings = { homeContext.startActivity(Intent(homeContext, SettingsActivity::class.java)) },
                    onProfile = { homeContext.startActivity(Intent(homeContext, ProfileActivity::class.java)) }
                )
            }
        }
    }
    selectedGroup?.let { HistoryDialogV5(it, onCallNumber, { onDeleteCaller(it.number); selectedGroup = null }, { selectedGroup = null }) }
    actionTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { actionTarget = null; actionHistoryGroup = null; actionContact = null },
            title = { Text(target.first, fontWeight = FontWeight.ExtraBold) },
            text = { Text(target.second) },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = homeContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Phone number", target.second))
                    android.widget.Toast.makeText(homeContext, "Number copied", android.widget.Toast.LENGTH_SHORT).show()
                    actionTarget = null; actionHistoryGroup = null; actionContact = null
                }) { Text("Copy number") }
            },
            dismissButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${target.first}: ${target.second}") }
                        homeContext.startActivity(Intent.createChooser(send, "Share number"))
                    }) { Text("Share") }
                    TextButton(onClick = {
                        val rawDigits = target.second.filter { it.isDigit() }
                        val digits = when {
                            rawDigits.length == 10 -> "91$rawDigits"
                            rawDigits.length == 11 && rawDigits.startsWith("0") -> "91${rawDigits.drop(1)}"
                            else -> rawDigits
                        }
                        if (digits.isNotBlank()) {
                            try { homeContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits"))) }
                            catch (_: Exception) { android.widget.Toast.makeText(homeContext, "Unable to open WhatsApp", android.widget.Toast.LENGTH_SHORT).show() }
                        }
                    }) { Text("WhatsApp") }
                    TextButton(onClick = { onCallNumber(target.second); actionTarget = null; actionHistoryGroup = null; actionContact = null }) { Text("Call", color = SCGreen, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = {
                        actionContact?.let { contact ->
                            try {
                                val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contact.id)
                                homeContext.startActivity(Intent(Intent.ACTION_DELETE, uri))
                            } catch (_: Exception) { android.widget.Toast.makeText(homeContext, "Open Contacts to delete this contact", android.widget.Toast.LENGTH_SHORT).show() }
                        } ?: actionHistoryGroup?.let { deleteTarget = it }
                        actionTarget = null; actionHistoryGroup = null; actionContact = null
                    }) { Text(if (actionContact != null) "Delete contact" else "Delete call history", color = SCRed) }
                    TextButton(onClick = { hubTab = 1; actionTarget = null; actionHistoryGroup = null; actionContact = null }) { Text("Smart Hub messages") }
                    TextButton(onClick = { actionTarget = null; actionHistoryGroup = null; actionContact = null }) { Text("Close") }
                }
            }
        )
    }
    deleteTarget?.let { group ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete caller history?") },
            text = { Text("Remove all ${group.calls.size} call-log entries for ${group.name ?: group.number}? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { onDeleteCaller(group.number); deleteTarget = null }) {
                    Text("Delete", color = SCRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = SCBlue) } }
        )
    }
    if (bulkDeleteConfirm) {
        val selectedGroups = filteredGroups.filter { it.key in selectedCallerKeys }
        AlertDialog(
            onDismissRequest = { bulkDeleteConfirm = false },
            title = { Text("Delete selected callers?") },
            text = { Text("Delete complete call history for ${selectedGroups.size} selected caller${if (selectedGroups.size == 1) "" else "s"}? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    selectedGroups.forEach { onDeleteCaller(it.number) }
                    selectedCallerKeys = emptySet()
                    bulkDeleteConfirm = false
                }) { Text("Delete", color = SCRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { bulkDeleteConfirm = false }) { Text("Cancel", color = SCBlue) } }
        )
    }
}

@Composable
private fun SearchBoxV5(value: String, onValueChange: (String) -> Unit, hint: String) { OutlinedTextField(value, onValueChange, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), singleLine = true, placeholder = { Text("⌕  $hint") }, shape = RoundedCornerShape(18.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = SCBlue, unfocusedBorderColor = Color(0xFFD9E1EC), focusedContainerColor = Color.White, unfocusedContainerColor = Color.White)) }

@Composable
private fun NumberDialRowV5(
    value: String,
    onValueChange: (String) -> Unit,
    onCall: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("Type a number to call") },
            leadingIcon = { Text("☎", color = SCBlue) },
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SCBlue,
                unfocusedBorderColor = Color(0xFFD9E1EC),
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White
            )
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = onCall,
            enabled = value.isNotBlank(),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = SCGreen,
                contentColor = Color.White,
                disabledContainerColor = Color(0xFFDDE3EA),
                disabledContentColor = Color.White
            )
        ) {
            Text("☎")
        }
    }
}

@Composable
private fun RecentsViewV5(
    groups: List<V5CallGroup>,
    search: String,
    onSearch: (String) -> Unit,
    phone: String,
    onPhoneChange: (String) -> Unit,
    onCall: () -> Unit,
    filter: String,
    onFilter: (String) -> Unit,
    selectedKeys: Set<String>,
    onToggleSelection: (String) -> Unit,
    onOpen: (V5CallGroup) -> Unit,
    onDelete: (V5CallGroup) -> Unit,
    onBulkDelete: () -> Unit,
    onClearSelection: () -> Unit,
    onCallNumber: (String) -> Unit,
    onSmartActions: (V5CallGroup) -> Unit
) {
    val selectionMode = selectedKeys.isNotEmpty()
    Column(Modifier.fillMaxSize()) {
        if (selectionMode) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${selectedKeys.size} selected", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = SCBlue, modifier = Modifier.weight(1f))
                TextButton(onClick = onClearSelection) { Text("Cancel", color = SCBlue) }
                FilledIconButton(
                    onClick = onBulkDelete,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = SCRed, contentColor = Color.White)
                ) { Icon(androidx.compose.material.icons.Icons.Default.Delete, contentDescription = "Delete selected callers") }
            }
        } else {
            SearchBoxV5(search, onSearch, "Search people or numbers")
            NumberDialRowV5(phone, onPhoneChange, onCall)
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("All", "Missed", "Received", "Dialled").forEach { value ->
                    FilterChip(selected = filter == value, onClick = { onFilter(value) }, label = { Text(value) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = SCBlue, selectedLabelColor = Color.White))
                }
            }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item {
                Text(
                    if (selectionMode) "Long-press callers to select" else "Recent calls",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            items(groups, key = { it.key }) { group ->
                CallCardV5(
                    group = group,
                    selected = group.key in selectedKeys,
                    selectionMode = selectionMode,
                    onOpen = { if (selectionMode) onToggleSelection(group.key) else onOpen(group) },
                    onLongPress = { onToggleSelection(group.key) },
                    onSmartActions = { onSmartActions(group) },
                    onCall = { onCallNumber(group.number) }
                )
            }
            if (groups.isEmpty()) item { EmptyStateV5("No calls found") }
        }
    }
}
@Composable
private fun ContactsViewV5(contacts: List<V5ContactItem>, search: String, onSearch: (String) -> Unit, onCall: (String) -> Unit, onActions: (V5ContactItem) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SearchBoxV5(search, onSearch, "Search contacts")
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item { Text("Contacts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold) }
            items(contacts, key = { it.id + normalizedKey(it.number) }) { contact -> ContactCardV5(contact, { onActions(contact) }, { onCall(contact.number) }) }
            if (contacts.isEmpty()) item { EmptyStateV5("No contacts found") }
        }
    }
}

@Composable
private fun KeypadViewV5(phone: String, onPhoneChange: (String) -> Unit, onDigit: (String) -> Unit, onBackspace: () -> Unit, onCall: () -> Unit, status: String) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedTextField(value = phone, onValueChange = onPhoneChange, modifier = Modifier.fillMaxWidth(), singleLine = true, textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center, fontWeight = FontWeight.Bold), placeholder = { Text("Enter number", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }, trailingIcon = { IconButton(onClick = onBackspace) { Text("⌫", style = MaterialTheme.typography.titleLarge) } }, shape = RoundedCornerShape(20.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = SCBlue, unfocusedBorderColor = Color(0xFFD9E1EC), focusedContainerColor = Color.White, unfocusedContainerColor = Color.White))
        Text(status, color = SCMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 7.dp))
        val rows = listOf(listOf("1","2","3"), listOf("4","5","6"), listOf("7","8","9"), listOf("*","0","#"))
        rows.forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { row.forEach { d -> Button(onClick = { onDigit(d) }, modifier = Modifier.size(70.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = SCText), elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)) { Text(d, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) } } }; Spacer(Modifier.height(10.dp)) }
        Button(onClick = onCall, modifier = Modifier.size(68.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = SCGreen), elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)) { Text("☎", color = Color.White, style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
private fun AvatarV5(name: String?, color: Color = SCBlue) { Box(Modifier.size(52.dp).clip(CircleShape).background(color.copy(alpha = .10f)).border(1.5.dp, color.copy(alpha = .22f), CircleShape), contentAlignment = Alignment.Center) { Text(initials(name), color = color, fontWeight = FontWeight.ExtraBold) } }

@Composable
private fun CallCardV5(
    group: V5CallGroup,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onSmartActions: () -> Unit,
    onCall: () -> Unit
) {
    val color = if (group.latest.type == "Missed") SCRed else if (group.latest.type == "Dialled") SCBlue else SCGreen
    Card(
        Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFFEAF2FF) else Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                AvatarV5(group.name, color)
                if (selected) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomEnd).size(22.dp),
                        shape = CircleShape,
                        color = SCBlue
                    ) { Text("✓", color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(group.name ?: "Unknown caller", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    if (group.calls.size > 1) {
                        Spacer(Modifier.width(7.dp))
                        Surface(shape = CircleShape, color = SCTint) {
                            Text("${group.calls.size}", color = SCBlue, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                        }
                    }
                }
                Text(group.number, color = SCMuted, style = MaterialTheme.typography.bodySmall)
                Text(
                    "${group.latest.type}  •  ${group.latest.time}  •  ${formatDuration(group.latest.duration)}",
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (!selectionMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onSmartActions) { Text("✦", color = SCBlue, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge) }
                    FilledIconButton(onClick = onCall, colors = IconButtonDefaults.filledIconButtonColors(containerColor = SCGreen, contentColor = Color.White)) { Text("☎") }
                }
            }
        }
    }
}
@Composable
private fun ContactCardV5(contact: V5ContactItem, onSelect: () -> Unit, onCall: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onSelect), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(Color.White), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AvatarV5(contact.name); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text(contact.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium); Text(contact.number, color = SCMuted, style = MaterialTheme.typography.bodySmall); Text("Saved contact", color = SCGreen, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall) }
            FilledIconButton(onClick = onCall, colors = IconButtonDefaults.filledIconButtonColors(containerColor = SCGreen, contentColor = Color.White)) { Text("☎") }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val secs = safe % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, secs) else String.format("%d:%02d", minutes, secs)
}

@Composable
private fun EmptyStateV5(text: String) { Box(Modifier.fillMaxWidth().padding(top = 50.dp), contentAlignment = Alignment.Center) { Text(text, color = SCMuted) } }

@Composable
private fun HistoryDialogV5(
    group: V5CallGroup,
    onCall: (String) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit
) {
    val missed = group.calls.count { it.type == "Missed" }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFFF9F7FC),
            shadowElevation = 8.dp
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarV5(group.name, SCBlue)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(group.name ?: "Unknown caller", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        Text(group.number, color = SCMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick = onClose) { Text("×", style = MaterialTheme.typography.titleLarge, color = SCMuted) }
                }

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(shape = RoundedCornerShape(14.dp), color = SCTint) {
                        Text("${group.calls.size} calls", color = SCBlue, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                    Surface(shape = RoundedCornerShape(14.dp), color = if (missed > 0) Color(0xFFFFE9E9) else Color(0xFFEAF7F1)) {
                        Text("${missed} missed", color = if (missed > 0) SCRed else SCGreen, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFFE2E0E7))

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(group.calls.take(30), key = { "${it.timestamp}_::${it.type}" }) { call ->
                        val color = when (call.type) {
                            "Missed" -> SCRed
                            "Received" -> SCGreen
                            else -> SCBlue
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).clip(CircleShape).background(color))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(call.type, color = color, fontWeight = FontWeight.Bold)
                                Text(call.date, color = SCMuted, style = MaterialTheme.typography.labelSmall)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(call.time, color = SCText, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                                Text(formatDuration(call.duration), color = SCMuted, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFFE2E0E7))
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = onClear,
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Clear history", color = SCRed, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { onCall(group.number) },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SCGreen)
                    ) {
                        Text("Call", fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Close", color = SCBlue)
                }
            }
        }
    }
}