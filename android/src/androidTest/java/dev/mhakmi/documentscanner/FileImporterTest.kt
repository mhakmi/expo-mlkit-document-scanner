package dev.mhakmi.documentscanner

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FileImporterTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val sources = File(context.filesDir, "import-sources")
  private val imported = File(context.filesDir, "imported")

  @Before
  fun emptyFolders() {
    sources.deleteRecursively()
    imported.deleteRecursively()
    sources.mkdirs()
  }

  private fun jpeg(name: String, width: Int, height: Int): File {
    val file = File(sources, name)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
    bitmap.eraseColor(Color.WHITE)
    file.outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream) }
    bitmap.recycle()
    return file
  }

  private fun importOne(file: File): Map<String, Any> =
    FileImporter(context).import(listOf(Uri.fromFile(file)), imported)

  private fun filesOf(result: Map<String, Any>): List<Map<*, *>> = (result["files"] as List<*>).map { file -> file as Map<*, *> }

  private fun fileAt(uri: Any?): File = fileOf(uri as String)

  @Test
  fun aPdfImportsWithItsPageCountAndAThumbnail() {
    val page = jpeg("page.jpg", 1240, 1754)
    val pdf = File(sources, "three-pages.pdf")
    PdfWriter.write(List(3) { PageSource(page, 0) }, pdf)

    val result = importOne(pdf)

    assertEquals(0, result["skipped"])
    val file = filesOf(result).single()
    assertEquals("pdf", file["kind"])
    assertEquals("three-pages.pdf", file["name"])
    assertEquals(3, file["pageCount"])
    assertEquals(pdf.length(), fileAt(file["uri"]).length())
    assertTrue(fileAt(file["thumbnailUri"]).length() > 0)
  }

  @Test
  fun aJpegTurnedByExifImportsWithItsUprightSize() {
    val photo = jpeg("turned.jpg", 1600, 1200)
    ExifInterface(photo.path).apply {
      setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
      saveAttributes()
    }

    val file = filesOf(importOne(photo)).single()

    assertEquals("image", file["kind"])
    assertEquals(1200, file["width"])
    assertEquals(1600, file["height"])
  }

  @Test
  fun aTextFileIsSkippedAndLeavesNothingBehind() {
    val text = File(sources, "notes.txt").apply { writeText("not a document") }

    val result = importOne(text)

    assertEquals(1, result["skipped"])
    assertEquals(0, filesOf(result).size)
    assertEquals(0, imported.listFiles()?.size ?: 0)
  }

  @Test
  fun aFileNamedPdfThatIsNotAPdfIsSkippedAndLeavesNothingBehind() {
    val fake = File(sources, "fake.pdf").apply { writeText("not a PDF either") }

    val result = importOne(fake)

    assertEquals(1, result["skipped"])
    assertEquals(0, filesOf(result).size)
    assertEquals(0, imported.listFiles()?.size ?: 0)
  }
}
