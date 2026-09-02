// FILE: app/src/main/java/com/noslop/app/ui/ExifUtils.kt
package com.noslop.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.noslop.app.debug.Logger
import java.io.File

/**
 * EXIF-aware bitmap decoding.
 *
 * --- NOSLOP_EXIF_ORIENTATION_V1 ---
 *
 * THE BUG THIS FIXES
 *
 * Photos taken in the app arrived rotated 90 degrees, on both the sender's and
 * the receiver's screen, and the same happened to profile pictures.
 *
 * CameraX's ImageCapture is built here with no setTargetRotation(), so it writes
 * the JPEG in the sensor's native orientation and records the correction in the
 * EXIF Orientation tag rather than rotating the pixels. That is normal and fine
 * on its own — every well-behaved viewer reads the tag.
 *
 * BitmapFactory is not a well-behaved viewer. decodeFile() and decodeStream()
 * ignore EXIF entirely and hand back the sensor-orientation pixels. The app then
 * scales that bitmap and re-encodes it with Bitmap.compress(), which writes a
 * fresh JPEG carrying NO EXIF at all. At that point the rotation information is
 * gone for good: the image is now genuinely sideways, and no receiver can
 * recover it.
 *
 * Four call sites did exactly this:
 *   - ChatThreadScreen        (DM photo attachments)
 *   - GroupChatThreadScreen   (group chat photo attachments)
 *   - AvatarCropper           (profile picture)
 *   - GroupSettingsModal      (group avatar)
 *
 * WHY BAKE IT IN RATHER THAN PRESERVE THE TAG
 *
 * Copying the EXIF tag across to the re-encoded file would also work, but the
 * app renders received media through a mix of Coil (which respects EXIF) and
 * raw BitmapFactory (which does not), so a tag-based fix would still show
 * sideways images in some views and not others. Rotating the pixels once, at
 * send time, makes the bytes correct for every consumer including other
 * clients — which matters for a mesh where the receiver may not be running the
 * same build.
 *
 * Note the compression path only ran for files over 500KB, so small photos kept
 * their EXIF and displayed correctly while large ones did not. That is why the
 * bug looked intermittent.
 */
object ExifUtils {

    private const val TAG = "EXIF"

    /**
     * True when the file carries an EXIF orientation that actually changes the
     * pixels. Used to decide whether an otherwise-untouched image still needs to
     * be re-encoded.
     */
    fun needsRotation(file: File): Boolean {
        return try {
            val orientation = ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            orientation != ExifInterface.ORIENTATION_NORMAL &&
                orientation != ExifInterface.ORIENTATION_UNDEFINED
        } catch (e: Exception) {
            false
        }
    }

    /** Decode a file, applying its EXIF orientation to the pixels. */
    fun decodeOriented(file: File): Bitmap? {
        val bitmap = try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Logger.warn(TAG, "Could not decode ${file.name}: ${e.message}")
            null
        } ?: return null

        val orientation = try {
            ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        return applyOrientation(bitmap, orientation)
    }

    /**
     * Decode a content Uri, applying its EXIF orientation. The stream is opened
     * twice on purpose — once for the pixels and once for the tag — because an
     * InputStream from the content resolver is not reliably re-readable.
     */
    fun decodeOriented(context: Context, uri: Uri): Bitmap? {
        val bitmap = try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            Logger.warn(TAG, "Could not decode uri: ${e.message}")
            null
        } ?: return null

        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        return applyOrientation(bitmap, orientation)
    }

    /**
     * Rotates and/or mirrors the bitmap to match its recorded orientation.
     * Returns the original instance unchanged when no transform is needed, so
     * the common case allocates nothing extra.
     */
    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }

        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (e: OutOfMemoryError) {
            // A very large photo can fail here. A sideways image beats a crash.
            Logger.warn(TAG, "Out of memory rotating bitmap; returning as captured")
            bitmap
        } catch (e: Exception) {
            Logger.warn(TAG, "Could not rotate bitmap: ${e.message}")
            bitmap
        }
    }
}
