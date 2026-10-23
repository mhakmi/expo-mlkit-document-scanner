package dev.mhakmi.documentscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

private const val tag = "PdfWriterTest"
private const val a4WidthAt300Dpi = 2480
private const val a4HeightAt300Dpi = 3508

@RunWith(AndroidJUnit4::class)
class PdfWriterTest {
  companion object {
    private val folder: File by lazy {
      File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "pdf-check").apply { mkdirs() }
    }
    private val sources = mutableListOf<File>()

    @BeforeClass
    @JvmStatic
    fun makePages() {
      for (number in 1..3) {
        sources.add(scanLikePage(File(folder, "source-$number.jpg"), number))
      }
    }

    private fun scanLikePage(file: File, seed: Int): File {
      val width = a4WidthAt300Dpi
      val height = a4HeightAt300Dpi
      val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      val random = Random(seed)
      val row = IntArray(width)
      for (y in 0 until height) {
        for (x in 0 until width) {
          val shade = 238 + random.nextInt(14)
          row[x] = Color.rgb(shade, shade, shade - 4)
        }
        bitmap.setPixels(row, 0, width, 0, y, width, 1)
      }
      val canvas = Canvas(bitmap)
      val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 30, 40); textSize = 46f }
      canvas.drawText("Lease renewal notice, page $seed", 220f, 360f, ink.apply { textSize = 80f })
      ink.textSize = 46f
      for (line in 0 until 48) {
        canvas.drawText("The lease for unit 12 renews on September 1 for twelve months. Rent stays at 1850.", 220f, 520f + line * 62, ink)
      }
      file.outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream) }
      bitmap.recycle()
      return file
    }
  }

  private fun pagesOf(count: Int, rotations: Map<Int, Int> = emptyMap()): List<PageSource> =
    (0 until count).map { index -> PageSource(sources[index % sources.size], rotations[index] ?: 0) }

  private fun pageSizes(file: File): List<Pair<Int, Int>> {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
      PdfRenderer(descriptor).use { renderer ->
        return (0 until renderer.pageCount).map { index ->
          renderer.openPage(index).use { page -> Pair(page.width, page.height) }
        }
      }
    }
  }

  private fun writeMeasured(pages: List<PageSource>, output: File): Pair<PdfSummary, Long> {
    val before = Debug.getNativeHeapAllocatedSize()
    var peak = before
    var writing = true
    val sampler = Thread {
      while (writing) {
        peak = maxOf(peak, Debug.getNativeHeapAllocatedSize())
        Thread.sleep(5)
      }
    }
    sampler.start()
    val summary = try {
      PdfWriter.write(pages, output)
    } finally {
      writing = false
      sampler.join()
    }
    return Pair(summary, peak - before)
  }

  @Test
  fun tenPagesOpenAsTenA4Pages() {
    val output = File(folder, "ten-pages.pdf")
    val pages = pagesOf(10)
    val (summary, growth) = writeMeasured(pages, output)
    val scanned = pages.sumOf { page -> page.file.length() }
    Log.i(tag, "10 pages: ${summary.bytes} bytes from $scanned bytes of JPEG, native heap grew ${growth / 1_000_000} MB at most")
    assertEquals(10, summary.pageCount)
    assertEquals(output.length(), summary.bytes)
    assertTrue("the pages go in as scanned", summary.bytes in scanned..scanned + 10_000)
    assertEquals(List(10) { Pair(595, 842) }, pageSizes(output))
    assertFalse(File(folder, "ten-pages.pdf.part").exists())
  }

  @Test
  fun aPageAboveFourHundredDotsPerInchIsHalved() {
    val large = File(folder, "large.jpg")
    val bitmap = Bitmap.createBitmap(3400, 4808, Bitmap.Config.RGB_565)
    bitmap.eraseColor(Color.WHITE)
    large.outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream) }
    bitmap.recycle()
    val output = File(folder, "large.pdf")
    PdfWriter.write(listOf(PageSource(large, 0)), output)
    val text = output.readBytes().toString(Charsets.ISO_8859_1)
    assertTrue("the image is 1700 pixels wide", text.contains("/Width 1700 /Height 2404 "))
    assertEquals(listOf(Pair(595, 842)), pageSizes(output))
  }

  @Test
  fun aPngPageIsStoredAsJpeg() {
    val png = File(folder, "page.png")
    val bitmap = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.WHITE)
    png.outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
    bitmap.recycle()
    val output = File(folder, "png.pdf")
    PdfWriter.write(listOf(PageSource(png, 0)), output)
    assertTrue(output.readBytes().toString(Charsets.ISO_8859_1).contains("/Filter /DCTDecode"))
    assertEquals(listOf(Pair(595, 842)), pageSizes(output))
  }

  @Test
  fun thirtyPagesWrite() {
    val output = File(folder, "thirty-pages.pdf")
    val (summary, growth) = writeMeasured(pagesOf(30), output)
    Log.i(tag, "30 pages: ${summary.bytes} bytes, native heap grew ${growth / 1_000_000} MB at most")
    assertEquals(30, pageSizes(output).size)
  }

  @Test
  fun aTurnedPageComesOutLandscape() {
    val output = File(folder, "turned.pdf")
    PdfWriter.write(pagesOf(3, mapOf(1 to 90, 2 to 180)), output)
    assertEquals(listOf(Pair(595, 842), Pair(842, 595), Pair(595, 842)), pageSizes(output))
  }

  @Test
  fun aMissingPageIsReportedAndLeavesNoFile() {
    val output = File(folder, "missing.pdf")
    output.delete()
    assertThrows(PageMissingException::class.java) {
      PdfWriter.write(listOf(PageSource(sources[0], 0), PageSource(File(folder, "gone.jpg"), 0)), output)
    }
    assertFalse(output.exists())
  }

  @Test
  fun aPageThatIsNotAnImageIsReported() {
    val broken = File(folder, "broken.jpg").apply { writeText("not a picture") }
    assertThrows(DecodeFailedException::class.java) {
      PdfWriter.write(listOf(PageSource(broken, 0)), File(folder, "broken.pdf"))
    }
  }

  @Test
  fun writingTwiceToOneFileLeavesOneWholePdf() {
    val output = File(folder, "twice.pdf")
    PdfWriter.write(pagesOf(3), output)
    val summary = PdfWriter.write(pagesOf(2), output)
    assertEquals(2, summary.pageCount)
    assertEquals(output.length(), summary.bytes)
    assertEquals(List(2) { Pair(595, 842) }, pageSizes(output))
    assertFalse(File(folder, "twice.pdf.part").exists())
  }

  @Test
  fun aFailedWriteLeavesNoPdfAndNoPart() {
    val output = File(folder, "failed.pdf")
    output.delete()
    val broken = File(folder, "broken-page.jpg").apply { writeText("not a picture") }
    assertThrows(DecodeFailedException::class.java) {
      PdfWriter.write(listOf(PageSource(sources[0], 0), PageSource(broken, 0)), output)
    }
    assertFalse(output.exists())
    assertFalse(File(folder, "failed.pdf.part").exists())
  }

  @Test
  fun aReceiptKeepsItsShape() {
    assertEquals(PageSize(595f, 595f * 3), pageSizeFor(800, 2400))
    assertEquals(PageSize(612f, 792f), pageSizeFor(2550, 3300))
    assertEquals(PageSize(842f, 595f), pageSizeFor(3508, 2480))
  }
}
