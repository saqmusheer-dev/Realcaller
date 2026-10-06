plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.limradigitals.realcaller"
    compileSdk = 36
    defaultConfig { applicationId = "com.limradigitals.realcaller"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "1.0.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.01.00")
    implementation(composeBom); androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

tasks.register("patchSmartCallerSource") {
    doLast {
        val source = file("src/main/java/com/limradigitals/realcaller/SmartCallerActivityV5.kt")
        var text = source.readText()
        val start = text.indexOf("    private fun placeCall(number: String) {")
        val end = text.indexOf("    private fun loadContacts()", start)
        if (start < 0 || end < 0) throw GradleException("Outgoing call method boundary not found")
        val replacement = """    private fun placeCall(number: String) {
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
        val callPhoneGranted = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val phoneStateGranted = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        if (!callPhoneGranted || !phoneStateGranted) {
            status = "Phone permission required"
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE), 4403)
            return
        }
        try {
            val telecom = getSystemService(TelecomManager::class.java)
            var account = if (Build.VERSION.SDK_INT >= 29) telecom.getUserSelectedOutgoingPhoneAccount() else null
            if (account == null) account = telecom.getDefaultOutgoingPhoneAccount("tel")
            val simAccounts = telecom.getCallCapablePhoneAccounts().filter { handle ->
                telecom.getPhoneAccount(handle)?.hasCapabilities(android.telecom.PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) == true
            }
            if (account == null || telecom.getPhoneAccount(account)?.hasCapabilities(android.telecom.PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) != true) {
                account = simAccounts.firstOrNull()
            }
            if (account == null) {
                status = "No SIM / calling account available"
                return
            }
            if (Build.VERSION.SDK_INT >= 26 && !telecom.isOutgoingCallPermitted(account)) {
                status = "Outgoing calls are blocked by phone settings"
                return
            }
            val extras = Bundle()
            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
            telecom.placeCall(Uri.fromParts("tel", target, null), extras)
            status = "Calling…"
        } catch (_: SecurityException) {
            status = "Phone permission denied"
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                startActivity(intent)
            } catch (_: Exception) { }
        } catch (_: Exception) { status = "Unable to start call" }
    }

"""
        text = text.substring(0, start) + replacement + text.substring(end)

        // Replace the old red emoji handset with the monochrome phone glyph.
        text = text.replace("📞", "☎")
        text = text.replace("☎️", "☎")

        // Add a real Material phone icon for call buttons where the source already uses a Text handset.
        if (!text.contains("androidx.compose.material.icons.filled.Call")) {
            val importMarker = "import androidx.compose.material3.*\n"
            if (text.contains(importMarker)) text = text.replace(importMarker, importMarker + "import androidx.compose.material.icons.Icons\nimport androidx.compose.material.icons.filled.Call\n")
        }

        if (!text.contains("SettingsActivity::class.java")) {
            val marker = "            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {"
            val replacementHeader = """            val settingsContext = androidx.compose.ui.platform.LocalContext.current
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {"""
            if (text.contains(marker)) text = text.replace(marker, replacementHeader)
            val surfaceMarker = "                Surface(shape = RoundedCornerShape(50), color = if (isDefault) Color(0xFFE4F7EF) else Color(0xFFFFF0E5)) {"
            val settingsButton = """                IconButton(onClick = { settingsContext.startActivity(Intent(settingsContext, SettingsActivity::class.java)) }) { Text("⚙", style = MaterialTheme.typography.titleLarge, color = SCBlueDark) }
                Surface(shape = RoundedCornerShape(50), color = if (isDefault) Color(0xFFE4F7EF) else Color(0xFFFFF0E5)) {"""
            if (text.contains(surfaceMarker)) text = text.replace(surfaceMarker, settingsButton)
        }
        source.writeText(text)
    }
}

tasks.named("preBuild") { dependsOn("patchSmartCallerSource") }
