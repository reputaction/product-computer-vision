package com.reputaction.product_computer_vision

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView

/**
 * a texture view that auto adjusts to a aspect
 */

class AutoTextureView : TextureView {

    private var mWidth: Int = 0
    private var mHeight: Int = 0

    constructor(ctx: Context) : super(ctx)
    constructor(ctx: Context, attrs: AttributeSet) : super(ctx, attrs)
    constructor(ctx: Context, attrs: AttributeSet, defStyle: Int) : super(ctx, attrs, defStyle)

    fun setAspect(width: Int, height: Int) {
        if (width < 0 || height < 0) {
            throw IllegalArgumentException("width and height should be > 0")
        }

        mWidth = width
        mHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        if (mWidth == 0 || mHeight == 0) {
            setMeasuredDimension(width, height)
        } else {
            if (width < height * mWidth / mHeight) {
                setMeasuredDimension(width, width * mHeight / mWidth)
            } else {
                setMeasuredDimension(height * mWidth / mHeight, height)
            }
        }
    }
}
