package dev.mhakmi.documentscanner

import org.junit.Assert.assertEquals
import org.junit.Test

class PageSizeTest {
  @Test
  fun a4At300DotsPerInchIsA4() {
    assertEquals(PageSize(595f, 842f), pageSizeFor(2480, 3508))
  }

  @Test
  fun letterAt300DotsPerInchIsLetter() {
    assertEquals(PageSize(612f, 792f), pageSizeFor(2550, 3300))
  }

  @Test
  fun aLandscapeA4ImageGetsALandscapePage() {
    assertEquals(PageSize(842f, 595f), pageSizeFor(3508, 2480))
  }

  @Test
  fun aReceiptKeepsItsShapeAtTheWidthOfA4() {
    assertEquals(PageSize(595f, 1785f), pageSizeFor(800, 2400))
  }
}
