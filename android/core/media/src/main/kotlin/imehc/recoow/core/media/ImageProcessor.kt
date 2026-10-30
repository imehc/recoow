package imehc.recoow.core.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import imehc.recoow.core.domain.media.PreparedMedia
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

data class CropTransform(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f, val rotation: Float = 0f) {
    /** Shared by the preview and the exporter; translations are fractions of the crop square. */
    fun matrix(width: Int, height: Int, side: Float): Matrix = Matrix().apply {
        postTranslate(-width / 2f, -height / 2f)
        val scale = max(side / width, side / height) * zoom.coerceIn(1f, 4f)
        postScale(scale, scale)
        postRotate(rotation)
        postTranslate(side * (0.5f + panX), side * (0.5f + panY))
    }
}

@Singleton
class ImageProcessor @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val directory get() = File(context.cacheDir, "media-drafts").apply { mkdirs() }
    fun cameraFile(): File = File(directory, "camera").apply { mkdirs() }.let { File.createTempFile("photo-", ".jpg", it) }
    fun cameraUri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.media-files", file)

    suspend fun prepare(uri: Uri): PreparedMedia = withContext(Dispatchers.IO) {
        val source = File.createTempFile("source-", ".tmp", directory)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                source.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024); var size = 0L
                    while (true) {
                        ensureActive(); val count = input.read(buffer); if (count < 0) break
                        size += count; require(size <= 200L * 1024 * 1024) { "Image is too large" }; output.write(buffer, 0, count)
                    }
                }
            }
            val bitmap = decode(source)
            try { encode(bitmap) } finally { bitmap.recycle() }
        } finally { source.delete() }
    }

    suspend fun bitmap(file: File): Bitmap = withContext(Dispatchers.IO) { decode(file) }

    private fun decode(file: File): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        require(options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= 1_000_000_000L)
        var sample = 1
        // Bound the original allocation as well as the output, including on low-memory API 26 devices.
        while (max(options.outWidth, options.outHeight) / sample > 2048) sample *= 2
        options.inJustDecodeBounds = false; options.inSampleSize = sample; options.inPreferredConfig = Bitmap.Config.ARGB_8888
        val decoded = requireNotNull(BitmapFactory.decodeFile(file.path, options)) { "Unsupported image" }
        try {
            val exif = runCatching { ExifInterface(file) }.getOrNull()
            val transform = Matrix().apply {
                if (exif?.isFlipped == true) postScale(-1f, 1f)
                postRotate((exif?.rotationDegrees ?: 0).toFloat())
            }
            val oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true)
            try {
                val ratio = minOf(1f, 2048f / max(oriented.width, oriented.height))
                val result = createBitmap(max(1, (oriented.width * ratio).roundToInt()), max(1, (oriented.height * ratio).roundToInt()))
                Canvas(result).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(oriented, null, android.graphics.Rect(0, 0, result.width, result.height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                }
                return result
            } finally { if (oriented !== decoded) oriented.recycle() }
        } finally { decoded.recycle() }
    }

    suspend fun crop(source: File, transform: CropTransform): PreparedMedia = withContext(Dispatchers.Default) {
        val bitmap = decode(source)
        val side = minOf(1600, maxOf(800, minOf(bitmap.width, bitmap.height)))
        val output = createBitmap(side, side)
        try {
            Canvas(output).apply {
                drawColor(Color.WHITE)
                drawBitmap(bitmap, transform.matrix(bitmap.width, bitmap.height, side.toFloat()), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
            withContext(Dispatchers.IO) { encode(output) }
        } finally { bitmap.recycle(); output.recycle() }
    }

    private fun encode(bitmap: Bitmap): PreparedMedia {
        val output = File.createTempFile("image-", ".jpg", directory)
        try {
            output.outputStream().use { stream -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 78, stream)); stream.fd.sync() }
            return PreparedMedia(output, "image/jpeg", bitmap.width, bitmap.height)
        } catch (error: Throwable) { output.delete(); throw error }
    }

    /** A failure here is independent of saving the private draft. No broad gallery read permission is used. */
    suspend fun saveToGallery(content: PreparedMedia) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Recoow-${UUID.randomUUID()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, content.mimeType)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Recoow")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { output -> content.file.inputStream().use { it.copyTo(output) } }
            if (Build.VERSION.SDK_INT >= 29) resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (error: Throwable) { resolver.delete(uri, null, null); throw error }
    }

    suspend fun cleanExpiredDrafts() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        directory.walkBottomUp().filter { it.isFile && it.lastModified() < cutoff }.forEach { it.delete() }
        File(context.cacheDir, "resolved-media").listFiles().orEmpty().filter { it.lastModified() < cutoff }.forEach { it.delete() }
    }
}
