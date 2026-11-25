package com.kobblo.gallery.file.explorer.viewer.pdf

import android.graphics.Bitmap
import android.os.Parcel
import android.os.Parcelable

/**
 * A rendered page on its way across processes. The pixels go in shared memory, so once they're written this side
 * has no more use for its copy. Left to the garbage collector, a few pages' worth would pile up in a process that
 * allocates very little else, and so hardly ever collects.
 */
class PdfBitmap(val bitmap: Bitmap?) : Parcelable {
    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        if (bitmap == null) {
            dest.writeInt(0)
        } else {
            dest.writeInt(1)
            bitmap.writeToParcel(dest, flags)
            bitmap.recycle()
        }
    }

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<PdfBitmap> {
            override fun createFromParcel(source: Parcel): PdfBitmap =
                PdfBitmap(if (source.readInt() != 0) Bitmap.CREATOR.createFromParcel(source) else null)

            override fun newArray(size: Int): Array<PdfBitmap?> = arrayOfNulls(size)
        }
    }
}
