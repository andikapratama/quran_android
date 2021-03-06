package com.quran.labs.androidquran.util

import android.content.Context
import android.graphics.BitmapFactory
import com.crashlytics.android.answers.Answers
import com.crashlytics.android.answers.CustomEvent
import timber.log.Timber
import java.io.File
import javax.inject.Inject

class QuranPartialPageChecker @Inject constructor(val appContext: Context,
                                                  val quranSettings: QuranSettings,
                                                  val quranFileUtils: QuranFileUtils) {

  /**
   * Checks all the pages to find and delete partially downloaded images.
   */
  fun checkPages(numberOfPages: Int, width: String, secondWidth: String) {
    // internally, this is a set of page types to ensure running this
    // once per page type (even if there are multiple switches).
    if (!quranSettings.didCheckPartialImages()) {
      // past versions of the partial page checker didn't run the checker
      // whenever any .vX file exists. this was noted as a "works sometimes"
      // solution because not all zip files contain .vX files (ex naskh and
      // warsh). removed this now because it is actually invalid for madani
      // in some cases as well due to the fact that someone could have manually
      // downloaded images, have broken images, and then get a patch zip which
      // contains the .vX file.
      try {
        // check the partial images for the width
        checkPartialImages(width, numberOfPages)
        if (width != secondWidth) {
          // and only check for the tablet dimension width if it's different
          checkPartialImages(secondWidth, numberOfPages)
        }
        quranSettings.setCheckedPartialImages()
      } catch (throwable: Throwable) {
        Timber.e(throwable, "Error while checking partial pages: $width and $secondWidth")
      }
    }
  }

  /**
   * Check for partial images and delete them.
   * This opens every downloaded image and looks at the last set of pixels.
   * If the last few rows are blank, the image is assumed to be partial and
   * the image is deleted.
   */
  private fun checkPartialImages(width: String, numberOfPages: Int) {
    quranFileUtils.getQuranImagesDirectory(appContext, width)?.let { directoryName ->
      // scale images down to 1/16th of size
      val options = BitmapFactory.Options().apply {
        inSampleSize = 16
      }

      val directory = File(directoryName)
      // optimization to avoid re-generating the pixel array every time
      var pixelArray: IntArray? = null

      var deletedImages = 0
      // skip pages 1 and 2 since they're "special" (not full pages)
      for (page in 3..numberOfPages) {
        val filename = quranFileUtils.getPageFileName(page)
        if (File(directory, filename).exists()) {
          val bitmap = BitmapFactory.decodeFile(
              directoryName + File.separator + filename, options)

          // this is an optimization to avoid allocating 8 * width of memory
          // for everything.
          val rowsToCheck =
              // madani, 8 for 1920, 6 for 1280, 4 or less for smaller
              //   a handful of pages in 1260 are slightly shorter, so 6
              //   is a safer default.
              // for naskh, 1 for everything
              // for qaloon, 2 for largest size, 1 for smallest
              // for warsh, 2 for everything
              when (width) {
                "_1920" -> 8
                else -> 6
              }

          val bitmapWidth = bitmap.width
          // these should all be the same size, so we can just allocate once
          val pixels = if (pixelArray?.size == (bitmapWidth * rowsToCheck)) {
            pixelArray
          } else {
            pixelArray = IntArray(bitmapWidth * rowsToCheck)
            pixelArray
          }

          // get the set of pixels
          bitmap.getPixels(pixels,
              0,
              bitmapWidth,
              0,
              bitmap.height - rowsToCheck,
              bitmapWidth,
              rowsToCheck)

          // see if there's any non-0 pixel
          val foundPixel = pixels.any { it != 0 }

          // if all are non-zero, assume the image is partially blank
          if (!foundPixel) {
            deletedImages++
            File(directory, filename).delete()
          }
        }
      }

      if (deletedImages > 0) {
        // ideally we should see this number reach 0 at some point
        Answers.getInstance()
            .logCustom(CustomEvent("partialPagesRemoved")
            .putCustomAttribute("pagesRemoved", deletedImages)
            .putCustomAttribute("width", width))
      }
    }
  }
}
