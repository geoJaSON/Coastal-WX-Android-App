package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.data.PreferencesHelper
import com.example.service.StatusBarTempService
import com.example.ui.MainAppNavigation
import com.example.ui.MainViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var pendingAlertId by mutableStateOf<String?>(null)
    private var pendingDestination by mutableStateOf<String?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingAlertId = intent.getStringExtra(EXTRA_ALERT_ID)
        pendingDestination = intent.getStringExtra(EXTRA_DESTINATION)
        enableEdgeToEdge()
        // Android 13+ drops every notification (severe weather alerts, storm proximity
        // warnings, the status-bar temperature) until this is granted.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // The service doesn't survive process death; restart it here (not from the
        // Application class — a background WorkManager wake-up may not start an FGS).
        if (PreferencesHelper.getStatusBarTempToggle(this)) {
            StatusBarTempService.startService(this)
        }
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainAppNavigation(
                        viewModel = viewModel,
                        pendingAlertId = pendingAlertId,
                        onAlertNavigationConsumed = { pendingAlertId = null },
                        pendingDestination = pendingDestination,
                        onDestinationNavigationConsumed = { pendingDestination = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingAlertId = intent.getStringExtra(EXTRA_ALERT_ID)
        pendingDestination = intent.getStringExtra(EXTRA_DESTINATION)
    }

    companion object {
        const val EXTRA_ALERT_ID = "com.example.extra.ALERT_ID"
        const val EXTRA_DESTINATION = "com.example.extra.DESTINATION"
        const val DESTINATION_TROPICS = "tropics"
    }
}
