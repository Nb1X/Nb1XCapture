package io.github.nb1x.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.nio.ByteBuffer

class ScreenRecordService : Service() {

    companion object {
        const val ACTION_SHOW_OVERLAY = "io.github.nb1x.capture.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "io.github.nb1x.capture.HIDE_OVERLAY"
        const val ACTION_START = "io.github.nb1x.capture.START"
        const val ACTION_STOP  = "io.github.nb1x.capture.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val NOTIF_ID = 42
        private const val NOTIF_FINISHED_ID = 43
        private const val CHANNEL_ID = "nb1x_capture"
        private const val CHANNEL_FINISHED_ID = "nb1x_capture_finished"

        // Audio
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_COUNT = 2
        private const val BIT_RATE = 128_000
        private const val AUDIO_MIME = "audio/mp4a-latm"
    }

    private var overlay: OverlayView? = null

    // Vidéo
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: MediaCodec? = null
    private var videoTrackIndex = -1
    private var videoDrainThread: Thread? = null

    // Audio
    private var audioRecord: AudioRecord? = null
    private var audioEncoder: MediaCodec? = null
    private var audioTrackIndex = -1
    private var audioThread: Thread? = null

    // Muxer partagé
    private var muxer: MediaMuxer? = null
    private val muxerLock = Object()
    private var muxerStarted = false
    private var pendingTracks = 0

    @Volatile private var running = false
    private var currentFile: File? = null
    private var isProjectionForeground = false

    private fun log(msg: String) = android.util.Log.d("NB1XCAPTURE", msg)
    private fun logErr(msg: String, e: Throwable? = null) =
        android.util.Log.e("NB1XCAPTURE", msg, e)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("[NB1XCAPTURE] onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> {
                log("[NB1XCAPTURE] Showing overlay...")
                startForegroundSimple()
                showOverlay()
            }
            ACTION_HIDE_OVERLAY -> {
                log("[NB1XCAPTURE] Hiding overlay...")
                hideOverlay()
                stopSelf()
            }
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                log("[NB1XCAPTURE] ACTION_START received: code=$code, data=${data != null}")
                if (code == Activity.RESULT_OK && data != null) {
                    startForegroundMediaProjection()
                    startRecording(code, data)
                } else {
                    logErr("[NB1XCAPTURE] Invalid projection data (code=$code)")
                }
            }
            ACTION_STOP -> {
                log("[NB1XCAPTURE] ACTION_STOP received")
                stopRecording()
            }
        }
        return START_STICKY
    }

    // Foreground service

    private fun startForegroundSimple() {
        log("[NB1XCAPTURE] startForegroundSimple")
        val notif = buildNotification(getString(R.string.notif_text))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun startForegroundMediaProjection() {
        if (isProjectionForeground) return
        log("[NB1XCAPTURE] startForegroundMediaProjection")
        val notif = buildNotification(getString(R.string.notif_text))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        isProjectionForeground = true
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_record)
            .setOngoing(true)
            .build()

    // Overlay

    private fun showOverlay() {
        if (overlay?.isShown() == true) return
        RecorderState.setCount(RecordingStorage.count(this))
        overlay = OverlayView(
            context = this,
            onStart = { requestProjection() },
            onStop = { stopRecording() },
            onList = { showRecordingsDialog() },
            onDeleteLast = {
                if (RecordingStorage.deleteLast(this)) {
                    RecorderState.setCount(RecordingStorage.count(this))
                }
            },
            onClose = {
                if (RecorderState.state.value.isRecording) {
                    Handler(Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            this,
                            getString(R.string.toast_stop_before_close),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    hideOverlay()
                    stopSelf()
                }
            }
        ).also { it.show() }
    }

    private fun requestProjection() {
        val i = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_REQUEST_PROJECTION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(i)
    }

    private fun hideOverlay() {
        overlay?.hide()
        overlay = null
    }

    private var popup: PopupRecordings? = null
    private var lastDialogToggle = 0L

    private fun showRecordingsDialog() {
        val now = System.currentTimeMillis()
        if (now - lastDialogToggle < 300) return
        lastDialogToggle = now

        val files = RecordingStorage.list(this)
        log("[NB1XCAPTURE] Opening list: ${files.size} file(s)")
        if (popup == null) popup = PopupRecordings(this)
        if (popup?.isShown() == true) { popup?.hide(); return }
        popup?.show(files)
    }

    // Recording

    private fun startRecording(resultCode: Int, data: Intent) {
        log("[NB1XCAPTURE] Started recording (init)")
        if (running) { log("[NB1XCAPTURE] Already running"); return }

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
        projection = mgr.getMediaProjection(resultCode, data)
        if (projection == null) {
            logErr("[NB1XCAPTURE] MediaProjection null")
            return
        }
        log("[NB1XCAPTURE] MediaProjection obtained")

        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopRecording() }
        }, Handler(Looper.getMainLooper()))

        val metrics = DisplayMetrics().also {
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.getRealMetrics(it)
        }
        var width = metrics.widthPixels
        var height = metrics.heightPixels
        val dpi = metrics.densityDpi
        log("[NB1XCAPTURE] Screen: ${width}x${height} @ ${dpi}dpi")

        width = (width / 16) * 16
        height = (height / 16) * 16

        // File + muxer
        val file = RecordingStorage.newFile(this)
        currentFile = file
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        log("[NB1XCAPTURE] Muxer: ${file.name}")

        // Video: try each H.264 codec until one works
        val preferred = listOf(
            "c2.android.avc.encoder",
            "OMX.google.h264.encoder"
        )
        val available = android.media.MediaCodecList(
            android.media.MediaCodecList.REGULAR_CODECS
        ).codecInfos.filter {
            it.isEncoder && it.supportedTypes.any { t -> t.equals("video/avc", true) }
        }.map { it.name }

        val ordered = preferred.filter { it in available } +
                      available.filter { it !in preferred }

        log("[NB1XCAPTURE] Available codecs: ${available.joinToString()}")
        log("[NB1XCAPTURE] Trying order: ${ordered.joinToString()}")

        var videoSurface: android.view.Surface? = null
        var lastErr: Throwable? = null

        for (codecName in ordered) {
            log("[NB1XCAPTURE] Trying video codec: $codecName")
            var enc: MediaCodec? = null
            try {
                val fmt = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                }
                enc = MediaCodec.createByCodecName(codecName)
                log("[NB1XCAPTURE] $codecName created")
                enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                log("[NB1XCAPTURE] $codecName configured")
                val s = enc.createInputSurface()
                log("[NB1XCAPTURE] $codecName surface OK")
                enc.start()
                log("[NB1XCAPTURE] $codecName STARTED")

                videoEncoder = enc
                videoSurface = s
                break
            } catch (e: Throwable) {
                logErr("[NB1XCAPTURE] $codecName FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
                lastErr = e
                try { enc?.stop() } catch (_: Exception) {}
                try { enc?.release() } catch (_: Exception) {}
            }
        }

        if (videoSurface == null) {
            logErr("[NB1XCAPTURE] No working video codec. Last: ${lastErr?.message}", lastErr)
            // Nettoyage et sortie
            try { muxer?.release() } catch (_: Exception) {}
            muxer = null
            try { projection?.stop() } catch (_: Exception) {}
            projection = null
            RecorderState.setRecording(false)
            startForegroundSimple()
            return
        }

        virtualDisplay = projection!!.createVirtualDisplay(
            "Nb1XCapture", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            videoSurface, null, null
        )
        log("[NB1XCAPTURE] VirtualDisplay created")

        // Audio (Android 10+)
        var audioStarted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startAudioCapture()
                audioStarted = true
                log("[NB1XCAPTURE] Audio capture started")
            } catch (e: Exception) {
                logErr("[NB1XCAPTURE] Audio init failed: ${e.message}", e)
            }
        } else {
            log("[NB1XCAPTURE] Audio capture requires Android 10+")
        }

        // Expect 2 tracks if audio is on, 1 otherwise
        pendingTracks = if (audioStarted) 2 else 1

        // Start worker threads
        running = true
        RecorderState.setRecording(true)
        videoDrainThread = Thread { videoDrainLoop() }.also {
            it.name = "video-drain"; it.start()
        }

        log("[NB1XCAPTURE] Started recording")
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                this,
                getString(R.string.toast_recording_started),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    // --------- Vidéo : drain ---------

    private fun videoDrainLoop() {
        val bufInfo = MediaCodec.BufferInfo()
        while (running) {
            val enc = videoEncoder ?: break
            val idx = try { enc.dequeueOutputBuffer(bufInfo, 10_000) } catch (_: Exception) { -1 }
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        videoTrackIndex = muxer?.addTrack(enc.outputFormat) ?: -1
                        log("[NB1XCAPTURE] Video track added: $videoTrackIndex")
                        maybeStartMuxer()
                    }
                }
                idx >= 0 -> {
                    val buf = enc.getOutputBuffer(idx)
                    if (buf != null && bufInfo.size > 0 &&
                        bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        buf.position(bufInfo.offset)
                        buf.limit(bufInfo.offset + bufInfo.size)
                        writeSampleData(videoTrackIndex, buf, bufInfo)
                    }
                    enc.releaseOutputBuffer(idx, false)
                }
            }
        }
    }

    // --------- Audio : setup + drain ---------

    private fun startAudioCapture() {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf * 2, 8192)

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufSize)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord not initialized")
        }

        val audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME, SAMPLE_RATE, CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME).apply {
            configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        audioRecord?.startRecording()
        audioThread = Thread { audioDrainLoop() }.also {
            it.name = "audio-drain"; it.start()
        }
    }

    private fun audioDrainLoop() {
        val bufInfo = MediaCodec.BufferInfo()
        val pcmBuffer = ByteBuffer.allocateDirect(16384)
        val record = audioRecord ?: return

        while (running) {
            // Read PCM from AudioRecord
            pcmBuffer.clear()
            val read = record.read(pcmBuffer, pcmBuffer.capacity())
            if (read <= 0) continue

            // Feed AAC encoder
            val enc = audioEncoder ?: break
            val inIdx = try { enc.dequeueInputBuffer(10_000) } catch (_: Exception) { -1 }
            if (inIdx >= 0) {
                val inBuf = enc.getInputBuffer(inIdx) ?: continue
                inBuf.clear()
                pcmBuffer.position(0)
                pcmBuffer.limit(read)
                inBuf.put(pcmBuffer)
                enc.queueInputBuffer(inIdx, 0, read, System.nanoTime() / 1000, 0)
            }

            // Pull encoded buffers
            var outIdx = try { enc.dequeueOutputBuffer(bufInfo, 0) } catch (_: Exception) { -1 }
            while (outIdx >= 0) {
                val outBuf = enc.getOutputBuffer(outIdx)
                if (outBuf != null && bufInfo.size > 0 &&
                    bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    outBuf.position(bufInfo.offset)
                    outBuf.limit(bufInfo.offset + bufInfo.size)
                    writeSampleData(audioTrackIndex, outBuf, bufInfo)
                }
                enc.releaseOutputBuffer(outIdx, false)
                outIdx = try { enc.dequeueOutputBuffer(bufInfo, 0) } catch (_: Exception) { -1 }
            }

            // Special case: format changed
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(muxerLock) {
                    audioTrackIndex = muxer?.addTrack(enc.outputFormat) ?: -1
                    log("[NB1XCAPTURE] Audio track added: $audioTrackIndex")
                    maybeStartMuxer()
                }
            }
        }
    }

    // --------- Muxer partagé ---------

    private fun maybeStartMuxer() {
        // Called under muxerLock, once per track
        if (muxerStarted) return
        // Need as many valid tracks as expected
        val have = listOf(videoTrackIndex, audioTrackIndex).count { it >= 0 }
        val needed = if (pendingTracks == 2) 2 else 1
        if (have >= needed) {
            try {
                muxer?.start()
                muxerStarted = true
                log("[NB1XCAPTURE] Muxer started (video=$videoTrackIndex audio=$audioTrackIndex)")
            } catch (e: Exception) {
                logErr("[NB1XCAPTURE] Muxer start failed: ${e.message}", e)
            }
        }
    }

    private fun writeSampleData(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(muxerLock) {
            if (!muxerStarted || track < 0) return
            try { muxer?.writeSampleData(track, buf, info) } catch (_: Exception) {}
        }
    }

    // --------- Stop ---------

    private fun stopRecording() {
        log("[NB1XCAPTURE] Stopping recording...")
        if (!running) {
            RecorderState.setRecording(false)
            return
        }
        running = false

        // Stop threads
        try { videoDrainThread?.join(500) } catch (_: Exception) {}
        try { audioThread?.join(500) } catch (_: Exception) {}

        // Stop audio
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        try { audioEncoder?.stop() } catch (_: Exception) {}
        try { audioEncoder?.release() } catch (_: Exception) {}
        audioEncoder = null

        // Stop video
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null

        try { videoEncoder?.stop() } catch (_: Exception) {}
        try { videoEncoder?.release() } catch (_: Exception) {}
        videoEncoder = null

        // Stop projection
        try { projection?.stop() } catch (_: Exception) {}
        projection = null

        // Close muxer
        synchronized(muxerLock) {
            if (muxerStarted) {
                try { muxer?.stop() } catch (_: Exception) {}
            }
            try { muxer?.release() } catch (_: Exception) {}
            muxer = null
            muxerStarted = false
        }

        videoTrackIndex = -1
        audioTrackIndex = -1
        pendingTracks = 0
        isProjectionForeground = false

        RecorderState.setRecording(false)
        RecorderState.setCount(RecordingStorage.count(this))
        log("[NB1XCAPTURE] Recording stopped")
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                this,
                getString(R.string.toast_recording_stopped),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        val finishedName = currentFile?.name
        currentFile = null
        if (finishedName != null) {
            showFinishedToast(finishedName)
        }

        startForegroundSimple()
    }

    private fun showFinishedToast(fileName: String) {
        log("[NB1XCAPTURE] Finished: $fileName")
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                this,
                getString(R.string.toast_recording_saved, fileName),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        stopRecording()
        hideOverlay()
        super.onDestroy()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_FINISHED_ID,
                    getString(R.string.notif_channel) + " (fini)",
                    NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
}
