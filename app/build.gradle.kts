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
        val phoneNumbersGranted = Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
        if (!callPhoneGranted || !phoneStateGranted || !phoneNumbersGranted) {
            status = "Phone permissions required"
            val missing = buildList {
                if (!callPhoneGranted) add(Manifest.permission.CALL_PHONE)
                if (!phoneStateGranted) add(Manifest.permission.READ_PHONE_STATE)
                if (!phoneNumbersGranted && Build.VERSION.SDK_INT >= 31) add(Manifest.permission.READ_PHONE_NUMBERS)
            }
            requestPermissions(missing.toTypedArray(), 4403)
            return
        }
        try {
            val telecom = getSystemService(TelecomManager::class.java)
            val simAccounts = telecom.getCallCapablePhoneAccounts().filter { handle ->
                telecom.getPhoneAccount(handle)?.hasCapabilities(android.telecom.PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) == true
            }
            if (simAccounts.isEmpty()) {
                status = "No SIM / calling account available"
                return
            }

            val prefs = getSharedPreferences("smartcaller_calling", MODE_PRIVATE)
            val savedKey = prefs.getString("default_sim_key", null)
            val savedAccount = simAccounts.firstOrNull { simKey(it) == savedKey }
            val alwaysAsk = prefs.getBoolean("always_ask_sim", savedKey == null)

            if (!alwaysAsk && savedAccount != null) {
                makeTelecomCall(target, savedAccount, telecom)
                return
            }

            showSimChooser(target, simAccounts, telecom, prefs)
        } catch (_: SecurityException) {
            status = "Phone permissions denied"
        } catch (_: Exception) {
            status = "Unable to start call"
        }
    }

    private fun simKey(handle: android.telecom.PhoneAccountHandle): String = handle.componentName.flattenToString() + "|" + handle.id

    private fun showSimChooser(target: String, accounts: List<android.telecom.PhoneAccountHandle>, telecom: TelecomManager, prefs: android.content.SharedPreferences) {
        val labels = accounts.mapIndexed { index, handle ->
            val account = telecom.getPhoneAccount(handle)
            val carrier = account?.label?.toString()?.trim().orEmpty()
            if (carrier.isBlank()) "SIM ${'$'}{index + 1}" else "SIM ${'$'}{index + 1}  •  ${'$'}carrier"
        }.toTypedArray()
        val selected = intArrayOf(0)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(52, 0, 52, 8)
        }
        val remember = android.widget.CheckBox(this).apply {
            text = "Use selected SIM as default"
            isChecked = false
        }
        container.addView(remember)
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Choose SIM for this call")
            .setSingleChoiceItems(labels, 0) { _, which -> selected[0] = which }
            .setView(container)
            .setNegativeButton("Always ask") { _, _ ->
                prefs.edit().putBoolean("always_ask_sim", true).remove("default_sim_key").apply()
                status = "SIM selection: Ask every time"
            }
            .setPositiveButton("Call") { _, _ ->
                val handle = accounts[selected[0]]
                if (remember.isChecked) {
                    prefs.edit().putBoolean("always_ask_sim", false).putString("default_sim_key", simKey(handle)).apply()
                } else {
                    prefs.edit().putBoolean("always_ask_sim", true).apply()
                }
                makeTelecomCall(target, handle, telecom)
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(android.graphics.Color.rgb(18, 165, 106))
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(android.graphics.Color.rgb(23, 105, 209))
        }
        dialog.show()
    }

    private fun makeTelecomCall(target: String, account: android.telecom.PhoneAccountHandle, telecom: TelecomManager) {
        try {
            status = "Starting call…"
            val accountInfo = telecom.getPhoneAccount(account)
            val accountLabel = accountInfo?.label?.toString()?.trim().orEmpty().ifBlank { account.id }
            val uri = Uri.fromParts("tel", target, null)
            val extras = Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account) }
            CallDiagnostics.markOutgoingRequest(this, target, accountLabel)
            telecom.placeCall(uri, extras)
            status = "Calling…"
            verifyOutgoingCall(target, account, telecom)
        } catch (_: SecurityException) {
            CallDiagnostics.markFailure(this, "SecurityException: phone permission")
            status = "Phone permission denied"
        } catch (_: IllegalArgumentException) {
            CallDiagnostics.markFailure(this, "IllegalArgumentException: Telecom rejected selected SIM")
            status = "SIM cannot place this call"
        } catch (_: IllegalStateException) {
            CallDiagnostics.markFailure(this, "IllegalStateException: Telecom service not ready")
            status = "Phone service is not ready"
        } catch (e: Exception) {
            CallDiagnostics.markFailure(this, e.javaClass.simpleName + ": " + (e.message ?: "unknown error"))
            status = "Unable to start call"
        }
    }

    private fun deleteCallerHistory(number: String): Int {
        val deleted = CallLogManager.deleteCallerHistory(this, number)
        loadCalls()
        return deleted
    }

    private fun verifyOutgoingCall(target: String, selected: android.telecom.PhoneAccountHandle, telecom: TelecomManager) {
        android.os.Handler(mainLooper).postDelayed({
            try {
                if (telecom.isInCall || InCallServiceImpl.instance?.getManagedCalls()?.isNotEmpty() == true) {
                    CallDiagnostics.markTelecomAccepted(this, target)
                    return@postDelayed
                }
                val defaultAccount = telecom.getDefaultOutgoingPhoneAccount("tel")
                if (defaultAccount != null && defaultAccount != selected) {
                    status = "Retrying with system SIM routing…"
                    CallDiagnostics.markRetry(this, target, "defaultOutgoingPhoneAccount")
                    val retryExtras = Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, defaultAccount) }
                    telecom.placeCall(Uri.fromParts("tel", target, null), retryExtras)
                    android.os.Handler(mainLooper).postDelayed({
                        if (telecom.isInCall || InCallServiceImpl.instance?.getManagedCalls()?.isNotEmpty() == true) {
                            CallDiagnostics.markTelecomAccepted(this, target)
                            status = "Calling…"
                        } else {
                            CallDiagnostics.markFailure(this, "No live call after system SIM retry")
                            status = "Call did not start — open Call diagnostics"
                        }
                    }, 1600L)
                } else {
                    CallDiagnostics.markFailure(this, "No live Telecom call after placeCall")
                    status = "Call did not reach Telecom — open Call diagnostics"
                }
            } catch (e: Exception) {
                CallDiagnostics.markFailure(this, "Verification " + e.javaClass.simpleName + ": " + (e.message ?: "unknown"))
                status = "Call failed — open Call diagnostics"
            }
        }, 1600L)
    }

"""
        text = text.substring(0, start) + replacement + text.substring(end)

        if (!text.contains("androidx.compose.material.icons.filled.Call")) {
            val importMarker = "import androidx.compose.material3.*\n"
            if (text.contains(importMarker)) text = text.replace(importMarker, importMarker + "import androidx.compose.material.icons.Icons\nimport androidx.compose.material.icons.filled.Call\n")
        }
        text = text.replace(Regex("""Text\("(?:☎️|☎|📞)"[^)]*\)"""), "Icon(Icons.Default.Call, contentDescription = \"Call\", tint = Color.White)")
        text = text.replace("Surface(shape = CircleShape, color = Color(0xFFE4F7EF))", "Surface(shape = CircleShape, color = SCGreen)")

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

tasks.register("patchMultiCallUi") {
    doLast {
        val source = file("src/main/java/com/limradigitals/realcaller/CallActivity.kt")
        var text = source.readText()
        if (!text.contains("MultiCallControls(Modifier.fillMaxWidth())")) {
            val marker = "                Text(status, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (isSpam) SpamRed else if (isActive) Green else Blue)\n"
            if (!text.contains(marker)) throw GradleException("Call status UI marker not found")
            text = text.replace(marker, marker + "                MultiCallControls(Modifier.fillMaxWidth())\n")
            source.writeText(text)
        }
    }
}

tasks.named("preBuild") { dependsOn("patchSmartCallerSource", "patchMultiCallUi") }
