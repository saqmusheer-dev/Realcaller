package com.limradigitals.realcaller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RealCallerApp() }
    }
}

@Composable
private fun RealCallerApp() {
    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("RealCaller") }) }
        ) { padding ->
            HomeScreen(padding)
        }
    }
}

@Composable
private fun HomeScreen(padding: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Smart caller protection", style = MaterialTheme.typography.headlineSmall)
        Text("Identify businesses, detect spam and build a trusted caller reputation network.")
        Button(onClick = { }) { Text("Set up Caller ID") }
    }
}
