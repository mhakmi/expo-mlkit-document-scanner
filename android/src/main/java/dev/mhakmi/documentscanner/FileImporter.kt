package dev.mhakmi.documentscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.util.UUID

private const val thumbnailWidth = 360

class FileImporter(private val context: Context) {
  fun import(uris: List<Uri>, directory: File): Map<String, Any> {
    directory.mkdirs()
    val files = mutableListOf<Map<String, Any>>()
    var skipped = 0
    for (uri in uris) {
      val imported = try {
        importOne(uri, directory)
      } catch (error: Exception) {
        Log.w("Scanner", "Could not bring in $uri", error)
        null
      }
      if (imported == null) {
        skipped += 1
      } else {
        files.add(imported)
      }
    }
    return mapOf("files" to files, "skipped" to skipped)
  }

  private fun importOne(uri: Uri, directory: File): Map<String, Any>? {
    val name = displayName(uri)
    val type = context.contentResolver.getType(uri)
      ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
      ?: return null
    return when {
      type == "application/pdf" -> importPdf(uri, name, directory)
      type.startsWith("image/") -> importImage(uri, name, type, directory)
      else -> null
    }
  }

  private fun importPdf(uri: Uri, name: String, directory: File): Map<String, Any>? {
    val file = File(directory, "${UUID.randomUUID()}.pdf")
    copy(uri, file)
    val thumbnail = File(directory, "${UUID.randomUUID()}.jpg")
    val pageCount = try {
      ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
          renderFirstPage(renderer, thumbnail)
          renderer.pageCount
        }
      }
    } catch (error: Exception) {
      file.delete()
      thumbnail.delete()
      return null
    }
    return mapOf(
      "kind" to "pdf",
      "uri" to Uri.fromFile(file).toString(),
      "name" to name,
      "pageCount" to pageCount,
      "thumbnailUri" to Uri.fromFile(thumbnail).toString(),
    )
  }

  private fun renderFirstPage(renderer: PdfRenderer, output: File) {
    renderer.openPage(0).use { page ->
      val height = thumbnailWidth * page.height / page.width
      val bitmap = Bitmap.createBitmap(thumbnailWidth, height, Bitmap.Config.ARGB_8888)
      try {
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        output.outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream) }
      } finally {
        bitmap.recycle()
      }
    }
  }

  private fun importImage(uri: Uri, name: String, type: String, directory: File): Map<String, Any>? {
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: "img"
    val file = File(directory, "${UUID.randomUUID()}.$extension")
    copy(uri, file)
    val size = uprightSize(file)
    if (size == null) {
      file.delete()
      return null
    }
    return mapOf(
      "kind" to "image",
      "uri" to Uri.fromFile(file).toString(),
      "name" to name,
      "width" to size.first,
      "height" to size.second,
    )
  }

  private fun copy(uri: Uri, file: File) {
    try {
      val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open $uri")
      input.use { source -> file.outputStream().use { target -> source.copyTo(target) } }
    } catch (error: Exception) {
      file.delete()
      throw error
    }
  }

  private fun displayName(uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst() && !cursor.isNull(0)) {
        return cursor.getString(0)
      }
    }
    return uri.lastPathSegment ?: ""
  }
}

fun uprightSize(file: File): Pair<Int, Int>? {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeFile(file.path, bounds)
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
    return null
  }
  val sideways = exifRotation(file) % 180 != 0
  return if (sideways) Pair(bounds.outHeight, bounds.outWidth) else Pair(bounds.outWidth, bounds.outHeight)
}
