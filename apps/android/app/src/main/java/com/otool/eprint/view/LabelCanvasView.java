package com.otool.eprint.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.otool.eprint.model.LabelDocument;
import com.otool.eprint.model.TextElement;

public final class LabelCanvasView extends View {
    public interface Listener {
        void onSelectionChanged(@Nullable TextElement element);
        void onElementMoved(TextElement element);
    }

    private final Paint pagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pageBounds = new RectF();
    private final RectF itemBounds = new RectF();

    private LabelDocument document = LabelDocument.createDefault();
    private String selectedId;
    private Listener listener;
    private float pixelsPerMm = 1f;
    private float lastTouchX;
    private float lastTouchY;
    private boolean dragging;

    public LabelCanvasView(Context context) {
        this(context, null);
    }

    public LabelCanvasView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        pagePaint.setColor(Color.WHITE);
        pagePaint.setShadowLayer(dp(8), 0, dp(3), 0x28000000);
        setLayerType(LAYER_TYPE_SOFTWARE, pagePaint);

        gridPaint.setColor(0xFFE8E5DC);
        gridPaint.setStrokeWidth(dp(0.7f));

        selectionPaint.setStyle(Paint.Style.STROKE);
        selectionPaint.setStrokeWidth(dp(2));
        selectionPaint.setColor(0xFFE66F43);
    }

    public void setDocument(LabelDocument document) {
        this.document = document;
        if (document.find(selectedId) == null) {
            selectedId = document.elements.isEmpty() ? null : document.elements.get(0).id;
        }
        invalidate();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void select(String id) {
        selectedId = id;
        invalidate();
    }

    @Nullable
    public TextElement getSelectedElement() {
        return document.find(selectedId);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        calculatePageBounds();
        canvas.drawRoundRect(pageBounds, dp(3), dp(3), pagePaint);
        canvas.save();
        canvas.clipRect(pageBounds);
        drawGrid(canvas);
        for (TextElement element : document.elements) drawElement(canvas, element);
        canvas.restore();
    }

    private void calculatePageBounds() {
        float padding = dp(22);
        float availableWidth = Math.max(1f, getWidth() - padding * 2);
        float availableHeight = Math.max(1f, getHeight() - padding * 2);
        pixelsPerMm = Math.min(availableWidth / document.widthMm, availableHeight / document.heightMm);
        float pageWidth = document.widthMm * pixelsPerMm;
        float pageHeight = document.heightMm * pixelsPerMm;
        float left = (getWidth() - pageWidth) / 2f;
        float top = (getHeight() - pageHeight) / 2f;
        pageBounds.set(left, top, left + pageWidth, top + pageHeight);
    }

    private void drawGrid(Canvas canvas) {
        float step = 5f * pixelsPerMm;
        for (float x = pageBounds.left + step; x < pageBounds.right; x += step) {
            canvas.drawLine(x, pageBounds.top, x, pageBounds.bottom, gridPaint);
        }
        for (float y = pageBounds.top + step; y < pageBounds.bottom; y += step) {
            canvas.drawLine(pageBounds.left, y, pageBounds.right, y, gridPaint);
        }
    }

    private void drawElement(Canvas canvas, TextElement element) {
        float left = pageBounds.left + element.xMm * pixelsPerMm;
        float top = pageBounds.top + element.yMm * pixelsPerMm;
        int width = Math.max(1, Math.round(element.widthMm * pixelsPerMm));
        textPaint.setColor(0xFF102F29);
        textPaint.setTextSize(Math.max(dp(7), element.fontSizePt * 25.4f / 72f * pixelsPerMm));
        textPaint.setFakeBoldText(element.bold);

        StaticLayout layout = StaticLayout.Builder.obtain(element.text, 0, element.text.length(), textPaint, width)
                .setAlignment(toAlignment(element.alignment))
                .setIncludePad(false)
                .build();
        canvas.save();
        canvas.translate(left, top);
        layout.draw(canvas);
        canvas.restore();

        itemBounds.set(left, top, left + width, top + Math.max(layout.getHeight(), textPaint.getTextSize() * 1.2f));
        if (element.id.equals(selectedId)) {
            canvas.drawRoundRect(itemBounds, dp(2), dp(2), selectionPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            calculatePageBounds();
            TextElement hit = hitTest(event.getX(), event.getY());
            selectedId = hit == null ? null : hit.id;
            dragging = hit != null;
            lastTouchX = event.getX();
            lastTouchY = event.getY();
            if (listener != null) listener.onSelectionChanged(hit);
            invalidate();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && dragging) {
            TextElement selected = getSelectedElement();
            if (selected != null) {
                selected.xMm += (event.getX() - lastTouchX) / pixelsPerMm;
                selected.yMm += (event.getY() - lastTouchY) / pixelsPerMm;
                document.normalize();
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                if (listener != null) listener.onElementMoved(selected);
                invalidate();
            }
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            dragging = false;
            performClick();
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Nullable
    private TextElement hitTest(float x, float y) {
        for (int index = document.elements.size() - 1; index >= 0; index--) {
            TextElement element = document.elements.get(index);
            float left = pageBounds.left + element.xMm * pixelsPerMm;
            float top = pageBounds.top + element.yMm * pixelsPerMm;
            float width = element.widthMm * pixelsPerMm;
            float height = Math.max(element.fontSizePt * 25.4f / 72f * pixelsPerMm * 1.6f, dp(28));
            itemBounds.set(left, top, left + width, top + height);
            if (itemBounds.contains(x, y)) return element;
        }
        return null;
    }

    private static Layout.Alignment toAlignment(int alignment) {
        if (alignment == TextElement.ALIGN_CENTER) return Layout.Alignment.ALIGN_CENTER;
        if (alignment == TextElement.ALIGN_RIGHT) return Layout.Alignment.ALIGN_OPPOSITE;
        return Layout.Alignment.ALIGN_NORMAL;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
