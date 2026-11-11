package imehc.recoow.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ImageProcessorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val processor = ImageProcessor(context)
    private val files = mutableListOf<File>()
    @After fun cleanup() { files.forEach { it.delete() } }
    private fun quadrants(width: Int = 1200, height: Int = 800): File {
        val file = File(context.cacheDir, "quadrants-${UUID.randomUUID()}.jpg").also { files += it }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // 部分厂商的深色模式会重染 Canvas 绘制的基本图形，离屏也不例外。
        // 直接写入已知的源像素，内存中只保留两条扫描线。
        val top = IntArray(width) { if (it < width / 2) Color.RED else Color.GREEN }
        val bottom = IntArray(width) { if (it < width / 2) Color.BLUE else Color.YELLOW }
        for (y in 0 until height) bitmap.setPixels(if (y < height / 2) top else bottom, 0, width, 0, y, width, 1)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }; bitmap.recycle()
        return file
    }
    private fun near(actual: Int, expected: Int) {
        assertTrue("actual=$actual expected=$expected", kotlin.math.abs(Color.red(actual) - Color.red(expected)) < 20 &&
            kotlin.math.abs(Color.green(actual) - Color.green(expected)) < 20 && kotlin.math.abs(Color.blue(actual) - Color.blue(expected)) < 20)
    }
    @Test fun allExifOrientationsNormalizeBeforeCropping() = runBlocking {
        val topLeft = listOf(Color.RED, Color.GREEN, Color.YELLOW, Color.BLUE, Color.RED, Color.BLUE, Color.YELLOW, Color.GREEN)
        for (orientation in 1..8) {
            val source = quadrants()
            ExifInterface(source).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
            val result = processor.prepare(Uri.fromFile(source)).also { files += it.file }
            assertEquals(if (orientation >= 5) 800 else 1200, result.width)
            assertEquals(if (orientation >= 5) 1200 else 800, result.height)
            val bitmap = BitmapFactory.decodeFile(result.file.path)
            near(bitmap.getPixel(50, 50), topLeft[orientation - 1]); bitmap.recycle()
        }
    }
    @Test fun largeImageIsBoundedAndMetadataMatchesCompressedBytes() = runBlocking {
        val source = quadrants(6000, 3000)
        val content = processor.prepare(Uri.fromFile(source)).also { files += it.file }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(content.file.path, bounds)
        assertEquals("image/jpeg", bounds.outMimeType)
        assertEquals(bounds.outWidth, content.width); assertEquals(bounds.outHeight, content.height)
        assertTrue(bounds.outWidth <= 2048 && bounds.outHeight <= 2048)
        assertEquals(2, bounds.outWidth / bounds.outHeight)
    }
    @Test fun cropRotationPanZoomAndResetAgreeWithKnownPixelsAndKeepOriginal() = runBlocking {
        val source = quadrants()
        val original = source.readBytes()
        val normal = processor.crop(source, CropTransform()).also { files += it.file }
        val rotation = processor.crop(source, CropTransform(rotation = 90f)).also { files += it.file }
        val pan = processor.crop(source, CropTransform(panX = 1f)).also { files += it.file }
        val zoom = processor.crop(source, CropTransform(zoom = 4f)).also { files += it.file }
        val reset = processor.crop(source, CropTransform()).also { files += it.file }
        val first = BitmapFactory.decodeFile(normal.file.path)
        val rotated = BitmapFactory.decodeFile(rotation.file.path)
        val translated = BitmapFactory.decodeFile(pan.file.path)
        val zoomed = BitmapFactory.decodeFile(zoom.file.path)
        assertEquals(800, first.width); assertEquals(800, first.height)
        near(first.getPixel(80, 80), Color.RED); near(rotated.getPixel(80, 80), Color.BLUE)
        near(translated.getPixel(20, 400), Color.WHITE); near(zoomed.getPixel(80, 80), Color.RED)
        assertArrayEquals(normal.file.readBytes(), reset.file.readBytes())
        assertArrayEquals(original, source.readBytes())
        listOf(first, rotated, translated, zoomed).forEach { it.recycle() }
    }
    @Test fun malformedInputFailsWithoutPublishingAPhoto() = runBlocking {
        val bad = File(context.cacheDir, "bad-${UUID.randomUUID()}").also { files += it; it.writeText("not an image") }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { processor.prepare(Uri.fromFile(bad)) } }
        assertEquals("not an image", bad.readText())
    }
}
