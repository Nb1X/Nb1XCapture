package io.github.nb1x.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
                toast("Permission overlay accordée")
                startOverlayService()
            } else {
                toast("Permission overlay refusée")
            }
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK && res.data != null) {
                Log.d("NB1XCAPTURE", "[NB1XCAPTURE] User accepted projection, sending to service")
                val svc = Intent(this, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_START
                    putExtra(ScreenRecordService.EXTRA_RESULT_CODE, res.resultCode)
                    putExtra(ScreenRecordService.EXTRA_RESULT_DATA, res.data)
                }
                startForegroundService(svc)
            } else {
                Log.d("NB1XCAPTURE", "[NB1XCAPTURE] User denied projection")
                toast("Capture refusée")
            }
            // Fermer l'Activity pour revenir à l'écran d'origine
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.d("NB1XCAPTURE", "[NB1XCAPTURE] MainActivity created, action=${intent?.action}")
        projectionManager = getSystemService(MediaProjectionManager::class.java)

        // Permission notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        // Vérifier si on a été ouverte pour une demande de projection
        if (intent?.action == ACTION_REQUEST_PROJECTION) {
            Log.d("NB1XCAPTURE", "[NB1XCAPTURE] Projection requested by overlay")
            handleProjectionRequest()
            return
        }

        // Sinon, flux normal : démarrer l'overlay
        if (!canDrawOverlay()) {
            requestOverlayPermission()
        } else {
            startOverlayService()
            finish() // Pas besoin de garder l'Activity affichée
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_REQUEST_PROJECTION) {
            Log.d("NB1XCAPTURE", "onNewIntent : projection demandée")
            handleProjectionRequest()
        }
    }

    private fun handleProjectionRequest() {
        if (!canDrawOverlay()) {
            toast("Permission overlay requise")
            requestOverlayPermission()
            return
        }
        // Lancer le dialogue de projection système
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
