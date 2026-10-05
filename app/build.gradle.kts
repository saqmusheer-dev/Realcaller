plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.limradigitals.realcaller"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.limradigitals.realcaller"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.01.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Keep the current V5 visual UI frozen while hardening Telecom outgoing-call routing.
tasks.register("patchOutgoingCallRouting") {
    doLast {
        val source = file("src/main/java/com/limradigitals/realcaller/SmartCallerActivityV5.kt")
        val text = source.readText()
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
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            status = "Phone permission required"
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 4403)
            return
        }
        try {
            val telecom = getSystemService(TelecomManager::class.java)
            var account = telecom.getDefaultOutgoingPhoneAccount("tel")
            if (account == null) {
                val accounts = telecom.getCallCapablePhoneAccounts()
                if (accounts.size == 1) account = accounts.first()
                else if (accounts.isEmpty()) {
                    status = "No SIM / calling account available"
                    return
                }
            }
            if (Build.VERSION.SDK_INT >= 26 && account != null && !telecom.isOutgoingCallPermitted(account)) {
                status = "Outgoing calls are blocked by phone settings"
                return
            }
            val extras = Bundle()
            if (account != null) extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
            telecom.placeCall(Uri.fromParts("tel", target, null), extras)
            status = "Calling…"
        } catch (_: SecurityException) {
            status = "Phone permission denied"
        } catch (_: Exception) {
            status = "Unable to start call"
        }
    }

"""
        val patched = text.substring(0, start) + replacement + text.substring(end)
        if (patched != text) source.writeText(patched)
    }
}

tasks.named("preBuild") {
    dependsOn("patchOutgoingCallRouting")
}
