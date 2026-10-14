package io.github.nb1x.capture

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class OverlayView(
    private val context: Context,
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
    private val onList: () -> Unit,
    private val onDeleteLast: () -> Unit,
    private val onClose: () -> Unit
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var scope: CoroutineScope? = null

    private var expanded = false
    private var btnMain: ImageButton? = null
    private var menu: LinearLayout? = null
    private var rootView: View? = null

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 40
        y = 200
    }

    fun show() {
        if (view != null) return

        val v = LayoutInflater.from(context).inflate(R.layout.overlay, null)
        rootView = v

        btnMain = v.findViewById(R.id.btnMain)
        menu = v.findViewById(R.id.expandedMenu)

        val bStart  = v.findViewById<ImageButton>(R.id.btnStart)
        val bStop   = v.findViewById<ImageButton>(R.id.btnStop)
        val bList   = v.findViewById<ImageButton>(R.id.btnList)
        val bDelete = v.findViewById<ImageButton>(R.id.btnDeleteLast)
        val bClose  = v.findViewById<ImageButton>(R.id.btnClose)

        // Main button: toggle menu
        btnMain?.setOnClickListener {
            toggleExpand()
        }

        // Action buttons
        bStart.setOnClickListener  { onStart();  collapse() }
        bStop.setOnClickListener   { onStop();   collapse() }
        bList.setOnClickListener   { onList();   collapse() }
        bDelete.setOnClickListener { onDeleteLast(); collapse() }
        bClose.setOnClickListener  { onClose() }  // pas de collapse auto

        // Drag from main button
        btnMain?.setOnTouchListener(DragTouchListener(params, wm) { rootView })

        try {
            wm.addView(v, params)
            view = v
        } catch (e: Exception) {
            android.util.Log.e("NB1XCAPTURE", "[NB1XCAPTURE] addView failed", e)
            return
        }

        // Observe shared state
        val s = CoroutineScope(Dispatchers.Main + Job())
        scope = s
        s.launch {
            RecorderState.state.collect { st ->
                bStop.isEnabled = st.isRecording
                bStop.alpha = if (st.isRecording) 1f else 0.3f
                bStart.isEnabled = !st.isRecording
                bStart.alpha = if (st.isRecording) 0.3f else 1f
                bDelete.isEnabled = st.hasRecordings
                bDelete.alpha = if (st.hasRecordings) 1f else 0.3f

                // Update main button icon based on state
                val newIcon = if (st.isRecording) R.drawable.ic_stop else R.drawable.ic_hamburger
                if (btnMain?.tag != newIcon) {
                    btnMain?.setImageResource(newIcon)
                    btnMain?.tag = newIcon
                }
            }
        }
    }

    private fun toggleExpand() {
        if (expanded) collapse() else expand()
    }

    private fun expand() {
        if (expanded) return
        expanded = true
        val m = menu ?: return
        m.visibility = View.VISIBLE
        m.alpha = 0f
        m.animate().alpha(1f).setDuration(150)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        val m = menu ?: return
        m.animate().alpha(0f).setDuration(120).withEndAction {
            m.visibility = View.GONE
            // Redimensionner la fenêtre
            rootView?.let {
                try { wm.updateViewLayout(it, params) } catch (_: Exception) {}
            }
        }.start()
    }

    fun hide() {
        view?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        view = null
        scope = null
        rootView = null
        btnMain = null
        menu = null
        expanded = false
    }

    fun isShown(): Boolean = view != null

    /** Drag handler: distinguishes click from drag (>15px). */
    private class DragTouchListener(
        private val params: WindowManager.LayoutParams,
        private val wm: WindowManager,
        private val getRoot: () -> View?
    ) : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!dragging && (dx * dx + dy * dy) > 225) dragging = true
                    if (dragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        getRoot()?.let {
                            try { wm.updateViewLayout(it, params) } catch (_: Exception) {}
                        }
                        return true
                    }
                    return false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDragging = dragging
                    dragging = false
                    return wasDragging
                }
            }
            return false
        }
    }
}
