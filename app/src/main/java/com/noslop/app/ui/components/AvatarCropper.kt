package com.noslop.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.noslop.app.ui.theme.AccentGreen
import com.noslop.app.ui.theme.PrimaryBlack
import com.noslop.app.ui.theme.SurfaceDark
import com.noslop.app.ui.theme.TextLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun AvatarCropper(
    uri: Uri,
    onCropSuccess: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use {
                    val original = BitmapFactory.decodeStream(it)
                    val maxDim = 1024
                    if (original.width > maxDim || original.height > maxDim) {
                        val ratio = maxDim.toFloat() / max(original.width, original.height)
                        bitmap = Bitmap.createScaledBitmap(original, (original.width * ratio).toInt(), (original.height * ratio).toInt(), true)
                    } else {
                        bitmap = original
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    if (bitmap == null) {
        Box(modifier = Modifier.fillMaxSize().background(PrimaryBlack), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AccentGreen)
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBlack)
            .onSizeChanged { containerSize = it }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = max(0.5f, scale * zoom)
                        offset += pan
                    }
                }
        ) {
            // 1. Image Layer
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height

                val imgAspect = bitmap!!.width.toFloat() / bitmap!!.height
                val canvasAspect = canvasWidth / canvasHeight

                val fitWidth: Float
                val fitHeight: Float

                if (imgAspect > canvasAspect) {
                    fitWidth = canvasWidth
                    fitHeight = canvasWidth / imgAspect
                } else {
                    fitHeight = canvasHeight
                    fitWidth = canvasHeight * imgAspect
                }

                val bWidth = fitWidth * scale
                val bHeight = fitHeight * scale

                val x = (canvasWidth - bWidth) / 2 + offset.x
                val y = (canvasHeight - bHeight) / 2 + offset.y

                drawImage(
                    image = bitmap!!.asImageBitmap(),
                    dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
                    dstSize = IntSize(bWidth.roundToInt(), bHeight.roundToInt())
                )
            }

            // 2. Punch-out Overlay Layer
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height

                // Dark overlay
                drawRect(color = Color.Black.copy(alpha = 0.6f))
                
                // Crop Square
                val cropSize = canvasWidth * 0.8f
                val cx = (canvasWidth - cropSize) / 2
                val cy = (canvasHeight - cropSize) / 2

                // Punch out the center
                drawRect(
                    color = Color.Transparent,
                    topLeft = Offset(cx, cy),
                    size = Size(cropSize, cropSize),
                    blendMode = BlendMode.Clear
                )

                // Border
                drawRect(
                    color = AccentGreen,
                    topLeft = Offset(cx, cy),
                    size = Size(cropSize, cropSize),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        // Action Buttons
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(SurfaceDark)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = TextLight)
            }
            IconButton(onClick = {
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val canvasWidth = containerSize.width.toFloat()
                        val canvasHeight = containerSize.height.toFloat()

                        val imgAspect = bitmap!!.width.toFloat() / bitmap!!.height
                        val canvasAspect = canvasWidth / canvasHeight

                        val fitWidth: Float
                        val fitHeight: Float
                        if (imgAspect > canvasAspect) {
                            fitWidth = canvasWidth
                            fitHeight = canvasWidth / imgAspect
                        } else {
                            fitHeight = canvasHeight
                            fitWidth = canvasHeight * imgAspect
                        }

                        val scaledWidth = fitWidth * scale
                        val scaledHeight = fitHeight * scale
                        val xOffset = (canvasWidth - scaledWidth) / 2 + offset.x
                        val yOffset = (canvasHeight - scaledHeight) / 2 + offset.y

                        val cropSize = canvasWidth * 0.8f
                        val cx = (canvasWidth - cropSize) / 2
                        val cy = (canvasHeight - cropSize) / 2

                        // Map crop window back to original bitmap coordinates
                        val rectLeft = ((cx - xOffset) / scaledWidth) * bitmap!!.width
                        val rectTop = ((cy - yOffset) / scaledHeight) * bitmap!!.height
                        val rectWidth = (cropSize / scaledWidth) * bitmap!!.width
                        val rectHeight = (cropSize / scaledHeight) * bitmap!!.height

                        val finalLeft = max(0, rectLeft.roundToInt())
                        val finalTop = max(0, rectTop.roundToInt())
                        var finalWidth = rectWidth.roundToInt()
                        var finalHeight = rectHeight.roundToInt()

                        if (finalLeft + finalWidth > bitmap!!.width) {
                            finalWidth = bitmap!!.width - finalLeft
                        }
                        if (finalTop + finalHeight > bitmap!!.height) {
                            finalHeight = bitmap!!.height - finalTop
                        }

                        if (finalWidth > 0 && finalHeight > 0) {
                            val cropped = Bitmap.createBitmap(bitmap!!, finalLeft, finalTop, finalWidth, finalHeight)
                            // Downscale to 150x150 for mesh payload efficiency
                            val resized = Bitmap.createScaledBitmap(cropped, 150, 150, true)
                            val out = ByteArrayOutputStream()
                            resized.compress(Bitmap.CompressFormat.JPEG, 70, out)
                            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                            withContext(Dispatchers.Main) {
                                onCropSuccess(b64)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) { onCancel() }
                    }
                }
            }) {
                Icon(Icons.Default.Check, contentDescription = "Crop", tint = AccentGreen)
            }
        }
    }
}
