package io.github.nb1x.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_REQUEST_PROJECTION = "io.github.nb1x.capture.REQUEST_PROJECTION"
    }

    private lateinit var projectionManager: MediaProjectionManager

    private val overlayPermLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (canDrawOverlay()) {
                toast(getString(R.string.toast_permission_overlay_granted))
                startOverlayService()
            } else {
                toast(getString(R.string.toast_permission_overlay_denied))
            }
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK && res.data != null) {
                android.util.Log.d("NB1XCAPTURE", "[NB1XCAPTURE] User accepted projection, sending to service")
                val svc = Intent(this, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_START
                    putExtra(ScreenRecordService.EXTRA_RESULT_CODE, res.resultCode)
                    putExtra(ScreenRecordService.EXTRA_RESULT_DATA, res.data)
                }
                startForegroundService(svc)
            } else {
                android.util.Log.d("NB1XCAPTURE", "[NB1XCAPTURE] User denied projection")
                toast(getString(R.string.toast_projection_denied))
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.d("NB1XCAPTURE", "[NB1XCAPTURE] MainActivity created, action=${intent?.action}")
        projectionManager = getSystemService(MediaProjectionManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        if (intent?.action == ACTION_REQUEST_PROJECTION) {
            android.util.Log.d("NB1XCAPTURE", "[NB1XCAPTURE] Projection requested by overlay")
            handleProjectionRequest()
            return
        }

        if (!canDrawOverlay()) {
            requestOverlayPermission()
        } else {
            startOverlayService()
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_REQUEST_PROJECTION) {
            android.util.Log.d("NB1XCAPTURE", "[NB1XCAPTURE] onNewIntent: projection requested")
            handleProjectionRequest()
        }
    }

    private fun handleProjectionRequest() {
        if (!canDrawOverlay()) {
            toast(getString(R.string.toast_permission_overlay_required))
            requestOverlayPermission()
            return
        }
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        val i = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermLauncher.launch(i)
    }

    private fun startOverlayService() {
        val svc = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_SHOW_OVERLAY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(svc) else startService(svc)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
