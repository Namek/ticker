package net.namekdev.ticker

import android.content.Context
import android.util.AttributeSet
import android.widget.Spinner

/** Native Spinner also reports choosing the same item again, so it can be previewed again. */
class SoundSpinner(context: Context, attrs: AttributeSet) : Spinner(context, attrs) {
    var onSelection: ((Int) -> Unit)? = null

    override fun setSelection(position: Int) {
        super.setSelection(position)
        onSelection?.invoke(position)
    }
}
