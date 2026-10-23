package dev.mhakmi.documentscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

private const val baseline = 0xC0
private const val progressive = 0xC2
private const val lossless = 0xC3

class JpegHeaderTest {
  @get:Rule
  val folder = TemporaryFolder()

  private fun ByteArrayOutputStream.short(value: Int) {
    write(value shr 8)
    write(value and 0xFF)
  }

  private fun jpeg(frameMarker: Int, precision: Int = 8, components: Int = 3): ByteArray {
    val bytes = ByteArrayOutputStream()
    bytes.short(0xFFD8)
    bytes.short(0xFFE0)
    bytes.short(16)
    bytes.write("JFIF".toByteArray() + ByteArray(10))
    bytes.short(0xFF00 or frameMarker)
    bytes.short(8 + 3 * components)
    bytes.write(precision)
    bytes.short(3508)
    bytes.short(2480)
    bytes.write(components)
    repeat(components) { index -> bytes.write(byteArrayOf((index + 1).toByte(), 0x11, 0)) }
    bytes.short(0xFFDA)
    return bytes.toByteArray()
  }

  private fun headerOf(bytes: ByteArray): JpegHeader? {
    val file = folder.newFile()
    file.writeBytes(bytes)
    return jpegHeader(file)
  }

  @Test
  fun aBaselineHeaderGivesItsSizeAndComponents() {
    assertEquals(JpegHeader(2480, 3508, 3), headerOf(jpeg(baseline)))
  }

  @Test
  fun aProgressiveHeaderGivesItsSizeAndComponents() {
    assertEquals(JpegHeader(2480, 3508, 3), headerOf(jpeg(progressive)))
  }

  @Test
  fun aGrayHeaderReportsOneComponent() {
    assertEquals(JpegHeader(2480, 3508, 1), headerOf(jpeg(baseline, components = 1)))
  }

  @Test
  fun aCmykHeaderReportsFourComponents() {
    assertEquals(JpegHeader(2480, 3508, 4), headerOf(jpeg(baseline, components = 4)))
  }

  @Test
  fun aTwelveBitHeaderIsRefused() {
    assertNull(headerOf(jpeg(baseline, precision = 12)))
  }

  @Test
  fun aLosslessHeaderIsRefused() {
    assertNull(headerOf(jpeg(lossless)))
  }

  @Test
  fun aHeaderCutOffInsideASegmentIsRefused() {
    assertNull(headerOf(jpeg(baseline).copyOf(26)))
  }

  @Test
  fun aPngIsRefused() {
    assertNull(headerOf(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)))
  }
}
