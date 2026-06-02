package com.noslop.app.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.noslop.app.debug.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Handles real photo, video, and audio capture.
 */
class MediaCaptureManager(private val context: Context) {

    private val TAG = "MEDIA_CAPTURE"
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null

    /**
     * Start the camera for preview and preparation.
     */
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: androidx.camera.view.PreviewView,
        onReady: () -> Unit
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder().build()
            
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, cameraSelector, preview, imageCapture, videoCapture
                )
                onReady()
            } catch (exc: Exception) {
                Logger.error(TAG, "Use case binding failed", exc.message)
            }

        }, ContextCompat.getMainExecutor(context))
    }
    
    var isFrontCamera = false
        private set

    fun flipCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: androidx.camera.view.PreviewView,
        onReady: () -> Unit
    ) {
        isFrontCamera = !isFrontCamera
        startCamera(lifecycleOwner, previewView, onReady)
    }

    /**
     * Capture a real photo.
     */
    fun takePhoto(onPhotoCaptured: (File) -> Unit) {
        val imageCapture = imageCapture ?: return

        val photoFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Logger.error(TAG, "Photo capture failed: ${exc.message}")
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Logger.info(TAG, "Photo saved: ${photoFile.absolutePath}")
                    onPhotoCaptured(photoFile)
                }
            }
        )
    }

    /**
     * Start recording a real video.
     */
    @SuppressLint("MissingPermission")
    fun startVideoRecording(onVideoCaptured: (File) -> Unit) {
        val videoCapture = videoCapture ?: return

        val videoFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis()) + ".mp4"
        )

        val outputOptions = FileOutputOptions.Builder(videoFile).build()

        var pendingRecording = videoCapture.output.prepareRecording(context, outputOptions)
        
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingRecording = pendingRecording.withAudioEnabled()
        }

        recording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            Logger.info(TAG, "Video saved: ${videoFile.absolutePath}")
                            onVideoCaptured(videoFile)
                        } else {
                            recording?.close()
                            recording = null
                            Logger.error(TAG, "Video recording error: ${recordEvent.error}")
                        }
                    }
                }
            }
    }

    /**
     * Stop video recording.
     */
    fun stopVideoRecording() {
        recording?.stop()
        recording = null
    }

    /**
     * Start recording real audio.
     */
    fun startAudioRecording() {
        try {
            audioFile = File(
                context.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis()) + ".m4a"
            )
            
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(audioFile!!.absolutePath)
                prepare()
                start()
            }
            Logger.info(TAG, "Audio recording started")
        } catch (e: Exception) {
            Logger.error(TAG, "Audio recording failed: ${e.message}")
        }
    }

    /**
     * Stop audio recording and return the file.
     */
    fun stopAudioRecording(): File? {
        return try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            Logger.info(TAG, "Audio recording stopped: ${audioFile?.absolutePath}")
            audioFile
        } catch (e: Exception) {
            Logger.error(TAG, "Stop audio recording failed: ${e.message}")
            null
        }
    }

    fun release() {
        cameraExecutor.shutdown()
    }
}
