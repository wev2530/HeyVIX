package com.vix.heyvix

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Display
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Takes screenshots and records the screen.
 * Android requires this to run as a foreground service of type
 * "mediaProjection", with a visible notification.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_SCREENSHOT = "com.vix.heyvix.action.SCREENSHOT"
        const val ACTION_START_RECORDING = "com.vix.heyvix.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.vix.heyvix.action.STOP_RECORDING"
        const val EXTRA_RESULT_CODE = "vix_result_code"
        const val EXTRA_RESULT_DATA = "vix_result_data"

        private const val CHANNEL_ID = "vix_screen_capture"
        private const val NOTIFICATION_ID = 4201

        @Volatile
        var isRecording = false
            private set

        // Message for MainActivity to show when the user comes back to VIX.
        private var pendingReport: String? = null

        @Synchronized
        private fun setReport(text: String) {
            pendingReport = text
        }

        @Synchronized
        fun takePendingReport(): String? {
            val text = pendingReport
            pendingReport = null
            return text
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var recordingPfd: ParcelFileDescriptor? = null
    private var recordingUri: Uri? = null
    private var recordingFile: File? = null
    private var recordingStartedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_SCREENSHOT -> beginCapture(intent, screenshot = true)
            ACTION_START_RECORDING -> beginCapture(intent, screenshot = false)
            ACTION_STOP_RECORDING -> stopRecording()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------
    // Start
    // ------------------------------------------------------------

    private fun beginCapture(intent: Intent, screenshot: Boolean) {
        // Android requires the foreground notification BEFORE
        // the projection is created.
        showForeground(
            if (screenshot) "Taking a screenshot…"
            else "Preparing screen recording…",
            withStop = false
        )

        val resultCode = intent.getIntExtra(
            EXTRA_RESULT_CODE,
            Activity.RESULT_CANCELED
        )

        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            fail("Screen capture permission was missing, so nothing was captured.")
            return
        }

        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager

            val newProjection = manager.getMediaProjection(resultCode, data)
            projection = newProjection

            // Required on Android 14+ before creating a virtual display.
            newProjection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        mainHandler.post {
                            if (isRecording) {
                                stopRecording()
                            }
                        }
                    }
                },
                mainHandler
            )

            if (screenshot) {
                startScreenshot(newProjection)
            } else {
                startRecording(newProjection)
            }
        } catch (e: Exception) {
            fail("I couldn't start screen capture: ${e.message ?: "unknown error"}")
        }
    }

    private data class Size(val width: Int, val height: Int, val dpi: Int)

    @Suppress("DEPRECATION")
    private fun screenSize(): Size {
        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return Size(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    // ------------------------------------------------------------
    // Screenshot
    // ------------------------------------------------------------

    private fun startScreenshot(mp: MediaProjection) {
        val size = screenSize()

        val reader = ImageReader.newInstance(
            size.width,
            size.height,
            PixelFormat.RGBA_8888,
            2
        )
        imageReader = reader

        virtualDisplay = mp.createVirtualDisplay(
            "vix-screenshot",
            size.width,
            size.height,
            size.dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            mainHandler
        )

        // Give VIX time to move to the background so the screenshot
        // shows the real screen, not the VIX chat.
        mainHandler.postDelayed({ grabFrame(size, attempt = 1) }, 1500)
    }

    private fun grabFrame(size: Size, attempt: Int) {
        val reader = imageReader ?: return

        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        }

        if (image == null) {
            if (attempt < 6) {
                mainHandler.postDelayed(
                    { grabFrame(size, attempt + 1) },
                    300
                )
            } else {
                fail("The screen didn't give me a picture. Please try again.")
            }
            return
        }

        val bitmap: Bitmap
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * size.width

            val wide = Bitmap.createBitmap(
                size.width + rowPadding / pixelStride,
                size.height,
                Bitmap.Config.ARGB_8888
            )
            wide.copyPixelsFromBuffer(plane.buffer)

            bitmap = Bitmap.createBitmap(wide, 0, 0, size.width, size.height)
            if (bitmap !== wide) wide.recycle()
        } finally {
            image.close()
        }

        // Stop capturing now; saving happens on a background thread.
        releaseDisplayAndProjection()

        Thread {
            val where = saveScreenshot(bitmap)
            bitmap.recycle()

            mainHandler.post {
                if (where != null) {
                    finishWith("Screenshot saved to $where")
                } else {
                    finishWith("I couldn't save the screenshot.")
                }
            }
        }.start()
    }

    /** Returns a readable location, or null on failure. */
    private fun saveScreenshot(bitmap: Bitmap): String? {
        val name = "VIX_${timestamp()}.png"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/VIX"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val uri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                ) ?: return null

                contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                } ?: return null

                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)

                "Pictures/VIX/$name"
            } else {
                // Older Android: save in the app's own folder.
                val dir = File(
                    getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                    "VIX"
                ).apply { mkdirs() }
                val file = File(dir, name)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                file.absolutePath
            }
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------
    // Screen recording
    // ------------------------------------------------------------

    private fun startRecording(mp: MediaProjection) {
        val size = screenSize()

        // Video encoders like even numbers and sensible sizes.
        val scale = minOf(1f, 1920f / maxOf(size.width, size.height))
        val videoWidth = (size.width * scale).toInt() / 2 * 2
        val videoHeight = (size.height * scale).toInt() / 2 * 2

        val name = "VIX_${timestamp()}.mp4"

        try {
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(videoWidth, videoHeight)
            rec.setVideoFrameRate(30)
            rec.setVideoEncodingBitRate(8_000_000)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/VIX"
                    )
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }

                val uri = contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    values
                ) ?: throw IllegalStateException("Could not create video file")

                recordingUri = uri
                val pfd = contentResolver.openFileDescriptor(uri, "w")
                    ?: throw IllegalStateException("Could not open video file")
                recordingPfd = pfd
                rec.setOutputFile(pfd.fileDescriptor)
            } else {
                val dir = File(
                    getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                    "VIX"
                ).apply { mkdirs() }
                val file = File(dir, name)
                recordingFile = file
                rec.setOutputFile(file.absolutePath)
            }

            rec.prepare()
            recorder = rec

            virtualDisplay = mp.createVirtualDisplay(
                "vix-recording",
                videoWidth,
                videoHeight,
                size.dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface,
                null,
                mainHandler
            )

            rec.start()
            recordingStartedAt = System.currentTimeMillis()
            isRecording = true

            showForeground(
                "Recording your screen. Tap Stop to finish.",
                withStop = true
            )
        } catch (e: Exception) {
            cleanupRecorderFiles(delete = true)
            fail("I couldn't start recording: ${e.message ?: "unknown error"}")
        }
    }

    private fun stopRecording() {
        if (!isRecording) {
            stopForegroundAndSelf()
            return
        }
        isRecording = false

        var saved = true
        val tooShort = System.currentTimeMillis() - recordingStartedAt < 1000

        try {
            recorder?.stop()
        } catch (_: RuntimeException) {
            // Happens if the recording was stopped almost immediately.
            saved = false
        }

        releaseDisplayAndProjection()
        cleanupRecorderFiles(delete = !saved)

        val message = when {
            saved && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                "Screen recording saved to Movies/VIX"
            saved ->
                "Screen recording saved."
            tooShort ->
                "The recording was too short to save. Try again."
            else ->
                "I couldn't save the screen recording."
        }

        finishWith(message)
    }

    /** Closes the recorder and publishes or deletes the output file. */
    private fun cleanupRecorderFiles(delete: Boolean) {
        try {
            recorder?.reset()
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null

        try {
            recordingPfd?.close()
        } catch (_: Exception) {
        }
        recordingPfd = null

        val uri = recordingUri
        if (uri != null) {
            try {
                if (delete) {
                    contentResolver.delete(uri, null, null)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    contentResolver.update(uri, values, null, null)
                }
            } catch (_: Exception) {
            }
        }
        recordingUri = null

        if (delete) {
            recordingFile?.delete()
        }
        recordingFile = null
    }

    // ------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------

    private fun releaseDisplayAndProjection() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
    }

    private fun fail(message: String) {
        isRecording = false
        releaseDisplayAndProjection()
        cleanupRecorderFiles(delete = true)
        finishWith(message)
    }

    private fun finishWith(message: String) {
        setReport(message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        stopForegroundAndSelf()
    }

    private fun stopForegroundAndSelf() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showForeground(text: String, withStop: Boolean) {
        val manager = getSystemService(NOTIFICATION_SERVICE)
            as NotificationManager

        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "VIX screen capture",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("VIX")
            .setContentText(text)
            .setOngoing(true)

        if (withStop) {
            val stopIntent = Intent(this, ScreenCaptureService::class.java)
                .setAction(ACTION_STOP_RECORDING)

            val pending = PendingIntent.getService(
                this,
                0,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            @Suppress("DEPRECATION")
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                pending
            )
        }

        val notification = builder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    override fun onDestroy() {
        if (isRecording) {
            isRecording = false
            try {
                recorder?.stop()
            } catch (_: Exception) {
            }
        }
        releaseDisplayAndProjection()
        cleanupRecorderFiles(delete = false)
        super.onDestroy()
    }
}
