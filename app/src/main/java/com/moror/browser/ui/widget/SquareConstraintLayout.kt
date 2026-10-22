package com.moror.browser.ui.widget

import android.content.Context
import android.util.AttributeSet
import androidx.constraintlayout.widget.ConstraintLayout

/**
 * A ConstraintLayout that always enforces a 1:1 aspect ratio (perfect square)
 * based on its measured width, ensuring reliable layout inside RecyclerView grids.
 */
class SquareConstraintLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Enforce square dimensions: height equals width
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
