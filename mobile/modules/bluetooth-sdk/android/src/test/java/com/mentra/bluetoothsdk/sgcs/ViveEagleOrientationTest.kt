package com.mentra.bluetoothsdk.sgcs

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViveEagleOrientationTest {

    // ---------- orientationTransform (pure) ----------

    @Test
    fun `each EXIF orientation maps to its rotation and mirror`() {
        val expected =
            mapOf(
                ExifInterface.ORIENTATION_NORMAL to EagleHeic.Transform(0f, false),
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL to EagleHeic.Transform(0f, true),
                ExifInterface.ORIENTATION_ROTATE_180 to EagleHeic.Transform(180f, false),
                ExifInterface.ORIENTATION_FLIP_VERTICAL to EagleHeic.Transform(180f, true),
                ExifInterface.ORIENTATION_TRANSPOSE to EagleHeic.Transform(90f, true),
                ExifInterface.ORIENTATION_ROTATE_90 to EagleHeic.Transform(90f, false),
                ExifInterface.ORIENTATION_TRANSVERSE to EagleHeic.Transform(270f, true),
                ExifInterface.ORIENTATION_ROTATE_270 to EagleHeic.Transform(270f, false),
            )
        assertThat(expected.keys).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8)
        for ((orientation, transform) in expected) {
            assertThat(EagleHeic.orientationTransform(orientation))
                .describedAs("orientation $orientation")
                .isEqualTo(transform)
        }
    }

    @Test
    fun `undefined and out of range orientations leave the image alone`() {
        for (orientation in listOf(ExifInterface.ORIENTATION_UNDEFINED, -1, 9, 42)) {
            assertThat(EagleHeic.orientationTransform(orientation))
                .describedAs("orientation $orientation")
                .isEqualTo(EagleHeic.Transform(0f, false))
        }
    }

    // ---------- applyOrientation (pixels) ----------

    private val width = 3
    private val height = 2

    /** Opaque, distinct colour per source pixel. */
    private fun colorAt(x: Int, y: Int): Int = 0xFF000000.toInt() or ((x * 60 + 20) shl 16) or ((y * 100 + 30) shl 8)

    private fun source(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) for (x in 0 until width) bitmap.setPixel(x, y, colorAt(x, y))
        return bitmap
    }

    /**
     * Where source pixel (x, y) must land so the result is upright, straight from the EXIF
     * definitions (W, H = source size), plus whether the output is W x H or H x W.
     */
    private fun destination(orientation: Int, x: Int, y: Int): Pair<Int, Int> {
        val w = width
        val h = height
        return when (orientation) {
            2 -> (w - 1 - x) to y // mirror left-right
            3 -> (w - 1 - x) to (h - 1 - y) // 180
            4 -> x to (h - 1 - y) // mirror top-bottom
            5 -> y to x // transpose (main diagonal)
            6 -> (h - 1 - y) to x // 90 clockwise
            7 -> (h - 1 - y) to (w - 1 - x) // transverse (anti-diagonal)
            8 -> y to (w - 1 - x) // 270 clockwise
            else -> x to y
        }
    }

    private fun swapsSides(orientation: Int) = orientation in 5..8

    private fun assertUpright(orientation: Int) {
        val src = source()
        val out = EagleHeic.applyOrientation(src, orientation)
        val (outW, outH) = if (swapsSides(orientation)) height to width else width to height
        assertThat(out.width).describedAs("width for orientation $orientation").isEqualTo(outW)
        assertThat(out.height).describedAs("height for orientation $orientation").isEqualTo(outH)
        for (y in 0 until height) for (x in 0 until width) {
            val (dx, dy) = destination(orientation, x, y)
            assertThat(Integer.toHexString(out.getPixel(dx, dy)))
                .describedAs("orientation $orientation: source ($x,$y) at ($dx,$dy)")
                .isEqualTo(Integer.toHexString(colorAt(x, y)))
        }
    }

    @Test fun `orientation 2 mirrors left to right`() = assertUpright(2)

    @Test fun `orientation 3 rotates 180`() = assertUpright(3)

    @Test fun `orientation 4 mirrors top to bottom`() = assertUpright(4)

    @Test fun `orientation 5 transposes`() = assertUpright(5)

    @Test fun `orientation 6 rotates 90 clockwise`() = assertUpright(6)

    @Test fun `orientation 7 transverses`() = assertUpright(7)

    @Test fun `orientation 8 rotates 270 clockwise`() = assertUpright(8)

    @Test
    fun `normal undefined and unknown orientations return the same bitmap untouched`() {
        for (orientation in listOf(ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED, 9)) {
            val src = source()
            assertThat(EagleHeic.applyOrientation(src, orientation)).isSameAs(src)
            assertUpright(orientation)
        }
    }
}
