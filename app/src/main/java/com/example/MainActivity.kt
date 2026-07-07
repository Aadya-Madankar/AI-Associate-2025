package com.example

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ui.screens.LiveScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodels.XenoViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.launch

/**
 * Thin entry-point shell. Sets up edge-to-edge drawing + the Obsidian Aurora theme, requests
 * the mic + camera permissions once, and brokers the MediaProjection ("screen share") grant —
 * the only thing that genuinely needs an Activity. All UI lives in [LiveScreen]; this activity
 * owns the [XenoViewModel] and the projection result launcher.
 */
class MainActivity : ComponentActivity() {
  private val viewModel: XenoViewModel by viewModels()
  private lateinit var projectionLauncher: ActivityResultLauncher<Intent>
  private lateinit var overlayLauncher: ActivityResultLauncher<Intent>

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Warm Light UI → dark status/nav icons so the bars stay readable over the cream canvas.
    enableEdgeToEdge(
      statusBarStyle = androidx.activity.SystemBarStyle.light(
        android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
      ),
      navigationBarStyle = androidx.activity.SystemBarStyle.light(
        android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
      )
    )

    // Screen-share: a MediaProjection token can only be obtained from an Activity result.
    projectionLauncher = registerForActivityResult(
      ActivityResultContracts.StartActivityForResult()
    ) { result ->
      val data = result.data
      if (result.resultCode == Activity.RESULT_OK && data != null) {
        viewModel.startScreenShare(result.resultCode, data)
      }
    }

    // Overlay ("Display over other apps") grant — lets XENO roam over every app.
    overlayLauncher = registerForActivityResult(
      ActivityResultContracts.StartActivityForResult()
    ) { viewModel.onOverlayPermissionResult() }

    // When the user toggles screen-share on, launch the system grant dialog.
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.screenShareRequests.collect {
          val mgr = getSystemService(MediaProjectionManager::class.java)
          mgr?.createScreenCaptureIntent()?.let { projectionLauncher.launch(it) }
        }
      }
    }

    // When the user turns on Roam without the overlay grant, open the system settings for it.
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.overlayPermissionRequests.collect {
          overlayLauncher.launch(
            Intent(
              Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
              Uri.parse("package:$packageName")
            )
          )
        }
      }
    }

    setContent {
      MyApplicationTheme {
        XenoApp(viewModel)
      }
    }
  }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun XenoApp(viewModel: XenoViewModel) {
  // Mic is needed to talk; camera is needed for camera-vision (denial is fine — it just
  // disables that one feature). Accessibility + screen-share are granted later, in-flow.
  val permissions = rememberMultiplePermissionsState(
    listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
  )

  LaunchedEffect(Unit) {
    if (!permissions.allPermissionsGranted) {
      permissions.launchMultiplePermissionRequest()
    }
  }

  LiveScreen(viewModel = viewModel)
}
