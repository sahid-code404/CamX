package com.sahidcode404.camx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.sahidcode404.camx.feature.camera.CameraScreen
import com.sahidcode404.camx.ui.theme.CamXTheme

class MainActivity : ComponentActivity() {
    private var cameraPermissionGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraPermissionGranted = hasCameraPermission()
        enableEdgeToEdge()
        setContent {
            CamXTheme(darkTheme = true) {
                var requestCompleted by remember { mutableStateOf(cameraPermissionGranted) }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    cameraPermissionGranted = granted
                    requestCompleted = true
                }

                LaunchedEffect(Unit) {
                    if (!cameraPermissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
                }

                CameraScreen(
                    permissionGranted = cameraPermissionGranted,
                    showSettingsAction = requestCompleted && !cameraPermissionGranted,
                    onOpenAppSettings = ::openAppSettings,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        cameraPermissionGranted = hasCameraPermission()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED
}
