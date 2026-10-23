package dev.mhakmi.documentscanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.system.ErrnoException
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class PageSource(val file: File, val rotation: Int)

data class PdfSummary(val pageCount: Int, val bytes: Long)

data class PageSize(val width: Float, val height: Float)

data class JpegHeader(val width: Int, val height: Int, val components: Int)

private const val pointsPerInch = 72f
private const val mostDotsPerInch = 400f
private const val reencodeQuality = 85
private const val paperTolerance = 0.04f

private val a4 = PageSize(595f, 842f)
private val letter = PageSize(612f, 792f)

private val baselineOrProgressive = setOf(0xC0, 0xC1, 0xC2)

fun pageSizeFor(imageWidth: Int, imageHeight: Int): PageSize {
  val long = max(imageWidth, imageHeight).toFloat()
  val short = min(imageWidth, imageHeight).toFloat()
  val ratio = long / short
  val portrait = imageHeight >= imageWidth
  for (paper in listOf(a4, letter)) {
    val paperRatio = paper.height / paper.width
    if (abs(ratio - paperRatio) / paperRatio <= paperTolerance) {
      return if (portrait) paper else PageSize(paper.height, paper.width)
    }
  }
  val pageShort = a4.width
  val pageLong = pageShort * ratio
  return if (portrait) PageSize(pageShort, pageLong) else PageSize(pageLong, pageShort)
}

fun exifRotation(file: File): Int {
  val orientation = try {
    ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
  } catch (error: IOException) {
    ExifInterface.ORIENTATION_NORMAL
  }
  return when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
    ExifInterface.ORIENTATION_ROTATE_180 -> 180
    ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
    else -> 0
  }
}

fun jpegHeader(file: File): JpegHeader? {
  try {
    DataInputStream(file.inputStream().buffered()).use { input ->
      if (input.readUnsignedShort() != 0xFFD8) {
        return null
      }
      while (true) {
        if (input.readUnsignedByte() != 0xFF) {
          return null
        }
        var marker = input.readUnsignedByte()
        while (marker == 0xFF) {
          marker = input.readUnsignedByte()
        }
        if (marker == 0x01 || marker in 0xD0..0xD7) {
          continue
        }
        if (marker == 0xD9 || marker == 0xDA) {
          return null
        }
        val length = input.readUnsignedShort()
        if (length < 2) {
          return null
        }
        if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
          val precision = input.readUnsignedByte()
          val height = input.readUnsignedShort()
          val width = input.readUnsignedShort()
          val components = input.readUnsignedByte()
          if (marker !in baselineOrProgressive || precision != 8 || width == 0 || height == 0) {
            return null
          }
          return JpegHeader(width, height, components)
        }
        input.readFully(ByteArray(length - 2))
      }
    }
  } catch (error: EOFException) {
    return null
  }
}

private sealed class PageImage(val width: Int, val height: Int, val components: Int) {
  abstract val length: Long

  abstract fun copyTo(output: OutputStream)

  class AsScanned(val file: File, header: JpegHeader) : PageImage(header.width, header.height, header.components) {
    override val length = file.length()

    override fun copyTo(output: OutputStream) {
      file.inputStream().use { input -> input.copyTo(output) }
    }
  }

  class Reencoded(val bytes: ByteArray, width: Int, height: Int) : PageImage(width, height, 3) {
    override val length = bytes.size.toLong()

    override fun copyTo(output: OutputStream) {
      output.write(bytes)
    }
  }
}

private class CountingStream(output: OutputStream) : FilterOutputStream(output) {
  var count = 0L
    private set

  override fun write(byte: Int) {
    out.write(byte)
    count += 1
  }

  override fun write(bytes: ByteArray, offset: Int, length: Int) {
    out.write(bytes, offset, length)
    count += length
  }

  fun text(value: String) {
    write(value.toByteArray(Charsets.ISO_8859_1))
  }
}

private fun number(value: Float): String = String.format(Locale.ROOT, "%.2f", value)

private fun dotsPerInch(width: Int, height: Int): Float {
  val size = pageSizeFor(width, height)
  return width / (size.width / pointsPerInch)
}

object PdfWriter {
  fun write(pages: List<PageSource>, output: File): PdfSummary {
    require(pages.isNotEmpty()) { "A PDF needs at least one page" }
    val partial = File(output.parentFile, "${output.name}.part")
    var moved = false
    try {
      CountingStream(FileOutputStream(partial).buffered()).use { stream -> writeDocument(stream, pages) }
      moved = partial.renameTo(output)
      if (!moved) {
        throw IOException("Could not move the PDF to ${output.path}")
      }
    } catch (error: IOException) {
      throw if (isOutOfSpace(error)) OutOfSpaceException(error) else error
    } finally {
      if (!moved) {
        partial.delete()
      }
    }
    return PdfSummary(pages.size, output.length())
  }

  private fun writeDocument(stream: CountingStream, pages: List<PageSource>) {
    val offsets = LongArray(3 + 3 * pages.size)
    stream.text("%PDF-1.4\n%âãÏÓ\n")
    pages.forEachIndexed { index, page -> writePage(stream, offsets, page, 3 + 3 * index) }

    offsets[1] = stream.count
    stream.text("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
    offsets[2] = stream.count
    val kids = pages.indices.joinToString(" ") { index -> "${3 + 3 * index} 0 R" }
    stream.text("2 0 obj\n<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>\nendobj\n")

    val xref = stream.count
    stream.text("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
    for (objectNumber in 1 until offsets.size) {
      stream.text(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[objectNumber]))
    }
    stream.text("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
  }

  private fun writePage(stream: CountingStream, offsets: LongArray, page: PageSource, pageObject: Int) {
    val image = pageImage(page.file)
    val rotation = (exifRotation(page.file) + page.rotation) % 360
    val size = pageSizeFor(image.width, image.height)
    val scale = min(size.width / image.width, size.height / image.height)
    val drawnWidth = image.width * scale
    val drawnHeight = image.height * scale
    val left = (size.width - drawnWidth) / 2
    val bottom = (size.height - drawnHeight) / 2
    val contents = "q ${number(drawnWidth)} 0 0 ${number(drawnHeight)} ${number(left)} ${number(bottom)} cm /Scan Do Q\n"
    val colorSpace = if (image.components == 1) "/DeviceGray" else "/DeviceRGB"

    offsets[pageObject] = stream.count
    stream.text(
      "$pageObject 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${number(size.width)} ${number(size.height)}] " +
        "/Rotate $rotation /Resources << /XObject << /Scan ${pageObject + 2} 0 R >> >> /Contents ${pageObject + 1} 0 R >>\nendobj\n",
    )
    offsets[pageObject + 1] = stream.count
    stream.text("${pageObject + 1} 0 obj\n<< /Length ${contents.length} >>\nstream\n${contents}endstream\nendobj\n")
    offsets[pageObject + 2] = stream.count
    stream.text(
      "${pageObject + 2} 0 obj\n<< /Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} " +
        "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode /Length ${image.length} >>\nstream\n",
    )
    image.copyTo(stream)
    stream.text("\nendstream\nendobj\n")
  }

  private fun pageImage(file: File): PageImage {
    if (!file.isFile) {
      throw PageMissingException(file.path)
    }
    val header = jpegHeader(file)
    if (header != null && header.components != 4 && dotsPerInch(header.width, header.height) <= mostDotsPerInch) {
      return PageImage.AsScanned(file, header)
    }
    return reencode(file)
  }

  private fun reencode(file: File): PageImage {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
      throw DecodeFailedException(file.path)
    }
    var sampleSize = 1
    while (dotsPerInch(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > mostDotsPerInch) {
      sampleSize *= 2
    }
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
      ?: throw DecodeFailedException(file.path)
    try {
      val bytes = ByteArrayOutputStream()
      bitmap.compress(Bitmap.CompressFormat.JPEG, reencodeQuality, bytes)
      return PageImage.Reencoded(bytes.toByteArray(), bitmap.width, bitmap.height)
    } finally {
      bitmap.recycle()
    }
  }

  private fun isOutOfSpace(error: IOException): Boolean {
    val cause = error.cause
    return (cause is ErrnoException && cause.errno == OsConstants.ENOSPC) || error.message?.contains("ENOSPC") == true
  }
}
