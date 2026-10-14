package io.github.nb1x.capture

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File

/**
 * Popup flottant via WindowManager (TYPE_APPLICATION_OVERLAY).
 * Chaque ligne : clic = ouvrir, bouton poubelle = supprimer.
 */
class PopupRecordings(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var dimView: View? = null
    private var popupView: View? = null

    /** Called after a file is deleted (to refresh state). */
    var onFileDeleted: ((File) -> Unit)? = null

    fun show(files: List<File>) {
        android.util.Log.d("NB1XCAPTURE", "[NB1XCAPTURE] Popup opened with ${files.size} file(s)")
        if (popupView != null) return

        val inflater = LayoutInflater.from(context)

        // Dim background
        val dim = View(context).apply { setBackgroundColor(0xAA000000.toInt()) }
        val dimParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try { wm.addView(dim, dimParams) } catch (_: Exception) {}
        dimView = dim

        // Square size
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val side = (minOf(dm.widthPixels, dm.heightPixels) * 0.85f).toInt()

        // Popup
        val v = inflater.inflate(R.layout.popup_recordings, null)
        val container = v.findViewById<LinearLayout>(R.id.listContainer)
        val emptyText = v.findViewById<TextView>(R.id.emptyText)
        val btnClose = v.findViewById<Button>(R.id.btnClosePopup)

        buildList(container, emptyText, files)

        btnClose.setOnClickListener { hide() }

        val popupParams = WindowManager.LayoutParams(
            side, side,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        try {
            wm.addView(v, popupParams)
            popupView = v
            android.util.Log.d("NB1XCAPTURE", "[NB1XCAPTURE] Popup added (side=${side}px)")
        } catch (e: Exception) {
            android.util.Log.e("NB1XCAPTURE", "[NB1XCAPTURE] addView failed", e)
        }
    }

    /** Build the list rows: click to open, button to delete. */
    private fun buildList(container: LinearLayout, emptyText: TextView, files: List<File>) {
        container.removeAllViews()
        if (files.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            return
        }
        emptyText.visibility = View.GONE

        val inflater = LayoutInflater.from(context)
        for (f in files) {
            val row = inflater.inflate(R.layout.item_recording, container, false)
            val name = row.findViewById<TextView>(R.id.itemName)
            val del = row.findViewById<ImageButton>(R.id.itemDelete)

            name.text = f.name

            // Click row = open file
            row.setOnClickListener { openFile(f) }

            // Click delete = remove file
            del.setOnClickListener {
                val ok = try { f.delete() } catch (_: Exception) { false }
                if (ok) {
                    android.util.Log.d("NB1XCAPTURE", "[NB1XCAPTURE] Deleted: ${f.name}")
                    onFileDeleted?.invoke(f)
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.toast_file_deleted, f.name),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    // Refresh popup
                    val remaining = RecordingStorage.list(context)
                    buildList(container, emptyText, remaining)
                } else {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.toast_file_delete_failed),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }

            container.addView(row)
        }
    }

    /** Open with system chooser (ACTION_VIEW). */
    private fun openFile(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension) ?: "video/mp4"

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.list_open_with)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            android.util.Log.e("NB1XCAPTURE", "[NB1XCAPTURE] open failed", e)
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.toast_no_app_for_file),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun hide() {
        popupView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        popupView = null
        dimView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        dimView = null
    }

    fun isShown(): Boolean = popupView != null
}
