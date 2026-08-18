package com.otool.eprint.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 标签档取景引导框：在相机预览上叠加约 4:3 横版贴纸比例的圆角边框，
 * 框外半透明压暗。仅「标签」档显示，供抓帧 ROI 与用户对齐使用。
 */
public final class LabelGuideOverlay extends View {
    private static final int DIM_COLOR = 0x66071512;
    private static final int BORDER_COLOR = 0xFFEA765B;
    /** 引导框占预览画面的最大比例。 */
    private static final float GUIDE_MAX_FRACTION = 0.86f;
    /** 横版贴纸宽高比 4:3。 */
    private static final float GUIDE_ASPECT = 4f / 3f;

    private final Paint dimPaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF guideBounds = new RectF();

    public LabelGuideOverlay(Context context) {
        this(context, null);
    }

    public LabelGuideOverlay(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LabelGuideOverlay(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        dimPaint.setColor(DIM_COLOR);
        borderPaint.setColor(BORDER_COLOR);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
    }

    /** 引导框相对本视图的归一化矩形（0~1），用于抓帧 ROI 裁剪。 */
    public RectF normalizedGuideRect() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return new RectF(0.07f, 0.07f, 0.93f, 0.93f);
        RectF bounds = computeGuideBounds(width, height);
        return new RectF(bounds.left / width, bounds.top / height,
                bounds.right / width, bounds.bottom / height);
    }

    private static RectF computeGuideBounds(int width, int height) {
        float guideWidth = width * GUIDE_MAX_FRACTION;
        float guideHeight = guideWidth / GUIDE_ASPECT;
        if (guideHeight > height * GUIDE_MAX_FRACTION) {
            guideHeight = height * GUIDE_MAX_FRACTION;
            guideWidth = guideHeight * GUIDE_ASPECT;
        }
        float left = (width - guideWidth) / 2f;
        float top = (height - guideHeight) / 2f;
        return new RectF(left, top, left + guideWidth, top + guideHeight);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        guideBounds.set(computeGuideBounds(getWidth(), getHeight()));
        // 框外压暗，突出取景区域。
        canvas.drawRect(0, 0, getWidth(), guideBounds.top, dimPaint);
        canvas.drawRect(0, guideBounds.bottom, getWidth(), getHeight(), dimPaint);
        canvas.drawRect(0, guideBounds.top, guideBounds.left, guideBounds.bottom, dimPaint);
        canvas.drawRect(guideBounds.right, guideBounds.top, getWidth(), guideBounds.bottom, dimPaint);
        float radius = 12f * getResources().getDisplayMetrics().density;
        canvas.drawRoundRect(guideBounds, radius, radius, borderPaint);
    }
}
