package com.noslop.app.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object VideoCompressor {

    sealed class CompressState {
        data class Progress(val percentage: Int) : CompressState()
        data class Success(val file: File) : CompressState()
        data class Error(val exception: Exception) : CompressState()
    }

    fun compressVideo(context: Context, inputUri: Uri, outputFile: File, quality: String = "medium"): Flow<CompressState> = callbackFlow {
        var transformer: Transformer? = null

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        trySend(CompressState.Success(outputFile))
                        close()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        trySend(CompressState.Error(exportException))
                        close()
                    }
                })
                .build()

            val targetHeight = when(quality) {
                "low" -> 480
                "medium" -> 720
                else -> 1080
            }
            val presentation = Presentation.createForHeight(targetHeight)

            val effects = Effects(
                emptyList(),
                listOf(presentation)
            )

            val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
                .setEffects(effects)
                .build()

            try {
                transformer?.start(editedMediaItem, outputFile.absolutePath)
            } catch (e: Exception) {
                trySend(CompressState.Error(e))
                close()
            }
        }

        val progressJob = launch {
            val progressHolder = androidx.media3.transformer.ProgressHolder()
            while (isActive) {
                var progressState = Transformer.PROGRESS_STATE_NOT_STARTED
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    transformer?.let {
                        progressState = it.getProgress(progressHolder)
                    }
                }
                
                if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                    trySend(CompressState.Progress(progressHolder.progress))
                } else if (progressState == Transformer.PROGRESS_STATE_NOT_STARTED) {
                    trySend(CompressState.Progress(0))
                }
                delay(500)
            }
        }

        awaitClose {
            progressJob.cancel()
            launch(kotlinx.coroutines.Dispatchers.Main) {
                transformer?.cancel()
            }
        }
    }
}
