package com.n44r.app

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.n44r.app.ui.DashboardScreen
import com.n44r.app.ui.ExportScreen
import com.n44r.app.ui.ProfileScreen
import com.n44r.app.ui.SetupScreen
import com.n44r.app.ui.StartScreen
import com.n44r.app.ui.SummaryScreen
import com.n44r.app.ui.WaitingScreen
import com.n44r.app.ui.theme.N44RTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestBlePermissions()

        setContent {
            N44RTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val screen by vm.screen.collectAsState()
                    when (screen) {
                        Screen.PROFILE -> ProfileScreen(vm)
                        Screen.START -> StartScreen(vm)
                        Screen.WAITING -> WaitingScreen(vm)
                        Screen.SETUP -> SetupScreen(vm)
                        Screen.DASHBOARD -> DashboardScreen(vm)
                        Screen.SUMMARY -> SummaryScreen(vm)
                        Screen.EXPORT -> ExportScreen(vm)
                    }
                }
            }
        }
    }

    private fun requestBlePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(permissions)
    }
}
