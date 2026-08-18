package com.otool.eprint.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.Nullable;

import com.otool.eprint.scan.ScanInputMode;

/** 通用 N 段扫描模式控件：标签 · 二维码 · 条形码，标签文字绘制在轨道内部。 */
public final class BarcodeModeSwitch extends View {
    public interface Listener {
        void onModeChanged(ScanInputMode mode);
    }

    private static final int TRACK_COLOR = 0xFFE5ECE8;
    private static final int BORDER_COLOR = 0xFFB8C8C1;
    private static final int THUMB_COLOR = 0xFFEA765B;
    private static final int SELECTED_TEXT_COLOR = 0xFFFFFFFF;
    private static final int UNSELECTED_TEXT_COLOR = 0xFF1C4B40;

    private static final ScanInputMode[] MODES = ScanInputMode.values();

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackBounds = new RectF();
    private final RectF thumbBounds = new RectF();

    private int modeIndex = ScanInputMode.QR.index();
    private float thumbPosition = modeIndex;
    @Nullable private Listener listener;
    @Nullable private ValueAnimator animator;

    public BarcodeModeSwitch(Context context) {
        this(context, null);
    }

    public BarcodeModeSwitch(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BarcodeModeSwitch(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setClickable(true);
        setFocusable(true);
        trackPaint.setColor(TRACK_COLOR);
        borderPaint.setColor(BORDER_COLOR);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1));
        thumbPaint.setColor(THUMB_COLOR);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        textPaint.setTextSize(sp(13));
        updateAccessibilityDescription();
    }

    public void setOnModeChangedListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public ScanInputMode getMode() {
        return ScanInputMode.fromIndex(modeIndex);
    }

    public void setMode(ScanInputMode mode) {
        setModeIndex(mode.index(), false);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = Math.round(dp(240));
        int desiredHeight = Math.round(dp(44));
        setMeasuredDimension(resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float inset = dp(1);
        trackBounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float radius = trackBounds.height() / 2f;
        canvas.drawRoundRect(trackBounds, radius, radius, trackPaint);

        int segments = MODES.length;
        float thumbInset = dp(3);
        float segmentWidth = (trackBounds.width() - thumbInset * 2f) / segments;
        float thumbLeft = trackBounds.left + thumbInset + thumbPosition * segmentWidth;
        thumbBounds.set(thumbLeft, trackBounds.top + thumbInset,
                thumbLeft + segmentWidth, trackBounds.bottom - thumbInset);
        float thumbRadius = thumbBounds.height() / 2f;
        canvas.drawRoundRect(thumbBounds, thumbRadius, thumbRadius, thumbPaint);
        canvas.drawRoundRect(trackBounds, radius, radius, borderPaint);

        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float baseline = getHeight() / 2f - (metrics.ascent + metrics.descent) / 2f;
        for (int index = 0; index < segments; index++) {
            float centerX = getWidth() * (index + 0.5f) / segments;
            drawLabel(canvas, MODES[index].label, centerX, baseline, index == modeIndex);
        }
    }

    private void drawLabel(Canvas canvas, String label, float x, float baseline, boolean selected) {
        textPaint.setColor(selected ? SELECTED_TEXT_COLOR : UNSELECTED_TEXT_COLOR);
        canvas.drawText(label, x, baseline, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            setPressed(true);
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            setPressed(false);
            ScanInputMode requested = ScanInputMode.forFraction(event.getX() / getWidth());
            setModeIndex(requested.index(), true);
            super.performClick();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            setPressed(false);
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        setModeIndex((modeIndex + 1) % MODES.length, true);
        return true;
    }

    private void setModeIndex(int index, boolean notify) {
        boolean changed = this.modeIndex != index;
        this.modeIndex = index;
        animateThumbTo(index);
        updateAccessibilityDescription();
        if (changed && notify) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
            if (listener != null) listener.onModeChanged(ScanInputMode.fromIndex(index));
        }
    }

    private void animateThumbTo(float target) {
        if (!isLaidOut()) {
            thumbPosition = target;
            invalidate();
            return;
        }
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(thumbPosition, target);
        animator.setDuration(160L);
        animator.addUpdateListener(value -> {
            thumbPosition = (float) value.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void updateAccessibilityDescription() {
        setContentDescription("扫描模式：" + MODES[modeIndex].label + "。点击切换其他模式");
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
