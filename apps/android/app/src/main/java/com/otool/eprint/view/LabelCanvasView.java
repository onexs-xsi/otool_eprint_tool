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
import com.otool.eprint.render.BarcodeRasterizer;
import com.otool.eprint.render.IconCatalog;
import com.otool.eprint.render.ImageElementRenderer;

public final class LabelCanvasView extends View {
    public interface Listener {
        void onSelectionChanged(@Nullable TextElement element);
        void onElementMoved(TextElement element);
        void onElementResized(TextElement element);
    }

    private final Paint pagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint resizeHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
    private boolean resizing;
    private float resizeStartTouchX;
    private float resizeStartTouchY;
    private float resizeStartWidthMm;
    private float resizeStartHeightMm;

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

        resizeHandlePaint.setStyle(Paint.Style.FILL);
        resizeHandlePaint.setColor(0xFFE66F43);

        inkPaint.setColor(0xFF102F29);
        inkPaint.setStyle(Paint.Style.FILL);
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
        drawBorder(canvas);
        canvas.restore();
        drawSelectionOverlay(canvas);
    }

    private void drawBorder(Canvas canvas) {
        if (document.borderStyle <= 0) return;
        inkPaint.setStyle(Paint.Style.STROKE);
        inkPaint.setStrokeWidth(Math.max(dp(0.5f), 0.3f * pixelsPerMm));
        float single = 0.5f * pixelsPerMm;
        canvas.drawRect(pageBounds.left + single, pageBounds.top + single,
                pageBounds.right - single, pageBounds.bottom - single, inkPaint);
        if (document.borderStyle == 2) {
            float dual = 1.4f * pixelsPerMm;
            canvas.drawRect(pageBounds.left + dual, pageBounds.top + dual,
                    pageBounds.right - dual, pageBounds.bottom - dual, inkPaint);
        }
        inkPaint.setStyle(Paint.Style.FILL);
    }

    private void calculatePageBounds() {
        // 只保留选择框所需的最小留白，让不同长宽比的标签尽量铺满实时预览。
        float padding = dp(2);
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
        switch (element.type) {
            case TextElement.TYPE_BARCODE:
            case TextElement.TYPE_QRCODE:
                drawCode(canvas, element, left, top);
                break;
            case TextElement.TYPE_LINE:
                drawLine(canvas, element, left, top);
                break;
            case TextElement.TYPE_RECT:
                drawRect(canvas, element, left, top);
                break;
            case TextElement.TYPE_IMAGE:
                drawImage(canvas, element, left, top);
                break;
            case TextElement.TYPE_ICON:
                drawIcon(canvas, element, left, top);
                break;
            case TextElement.TYPE_TABLE:
                drawTable(canvas, element, left, top);
                break;
            case TextElement.TYPE_ARCTEXT:
                drawArcText(canvas, element, left, top);
                break;
            case TextElement.TYPE_TIME:
            case TextElement.TYPE_SERIAL:
            case TextElement.TYPE_TEXT:
            default:
                drawText(canvas, element.displayText(), element, left, top);
                break;
        }
    }

    private float drawText(Canvas canvas, String content, TextElement element, float left, float top) {
        int width = Math.max(1, Math.round(element.widthMm * pixelsPerMm));
        textPaint.setColor(0xFF102F29);
        textPaint.setTextSize(Math.max(dp(7), element.fontSizePt * 25.4f / 72f * pixelsPerMm));
        textPaint.setFakeBoldText(element.bold);

        StaticLayout layout = StaticLayout.Builder.obtain(content, 0, content.length(), textPaint, width)
                .setAlignment(toAlignment(element.alignment))
                .setIncludePad(false)
                .build();
        canvas.save();
        canvas.translate(left, top);
        canvas.clipRect(0, 0, width,
                Math.max(1f, element.heightMm * pixelsPerMm));
        layout.draw(canvas);
        canvas.restore();
        return top + Math.max(1f, element.heightMm * pixelsPerMm);
    }

    private float drawCode(Canvas canvas, TextElement element, float left, float top) {
        float boxWidth = element.widthMm * pixelsPerMm;
        float boxHeight = element.heightMm * pixelsPerMm;
        boolean[][] matrix;
        try {
            matrix = BarcodeRasterizer.matrix(element.text, element.format,
                    element.qrErrorCorrection, element.quietZoneModules);
        } catch (IllegalArgumentException invalid) {
            // 内容不符合码制：画出虚位框提示，不落非法数据
            selectionPaint.setColor(0xFFB0B7B1);
            canvas.drawRect(left, top, left + boxWidth, top + boxHeight, selectionPaint);
            selectionPaint.setColor(0xFFE66F43);
            return top + boxHeight;
        }
        int rows = matrix.length;
        int cols = matrix[0].length;
        int rotation = element.rotationDegrees;
        float drawWidth = rotation == 90 || rotation == 270 ? boxHeight : boxWidth;
        float drawHeight = rotation == 90 || rotation == 270 ? boxWidth : boxHeight;
        canvas.save();
        if (rotation == 90) {
            canvas.translate(left + boxWidth, top);
            canvas.rotate(90f);
        } else if (rotation == 180) {
            canvas.translate(left + boxWidth, top + boxHeight);
            canvas.rotate(180f);
        } else if (rotation == 270) {
            canvas.translate(left, top + boxHeight);
            canvas.rotate(270f);
        } else {
            canvas.translate(left, top);
        }
        if (TextElement.TYPE_BARCODE.equals(element.type)) {
            String textPosition = element.effectiveBarcodeTextPosition();
            float labelHeight = !"none".equals(textPosition)
                    ? Math.min(drawHeight * 0.28f,
                    Math.max(dp(10), element.fontSizePt * 25.4f / 72f * pixelsPerMm * 1.25f))
                    : 0f;
            float barsHeight = Math.max(1f, drawHeight - labelHeight);
            float barsTop = "top".equals(textPosition) ? labelHeight : 0f;
            float moduleWidth = drawWidth / cols;
            for (int col = 0; col < cols; col++) {
                boolean dark = false;
                for (int row = 0; row < rows && !dark; row++) dark = matrix[row][col];
                if (dark) {
                    canvas.drawRect(col * moduleWidth, barsTop,
                            (col + 1) * moduleWidth, barsTop + barsHeight, inkPaint);
                }
            }
            if (!"none".equals(textPosition) && labelHeight > 1f) {
                textPaint.setColor(0xFF102F29);
                textPaint.setTextAlign(Paint.Align.CENTER);
                textPaint.setTextSize(Math.max(dp(6), Math.min(labelHeight * 0.72f,
                        element.fontSizePt * 25.4f / 72f * pixelsPerMm)));
                textPaint.setFakeBoldText(element.bold);
                Paint.FontMetrics metrics = textPaint.getFontMetrics();
                float labelTop = "top".equals(textPosition) ? 0f : barsHeight;
                float baseline = labelTop
                        + (labelHeight - metrics.bottom - metrics.top) / 2f;
                canvas.drawText(element.text, drawWidth / 2f, baseline, textPaint);
                textPaint.setTextAlign(Paint.Align.LEFT);
            }
            canvas.restore();
            return top + boxHeight;
        }
        float module = Math.min(drawWidth / cols, drawHeight / rows);
        float offsetX = (drawWidth - module * cols) / 2f;
        float offsetY = (drawHeight - module * rows) / 2f;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                if (matrix[row][col]) {
                    canvas.drawRect(offsetX + col * module, offsetY + row * module,
                            offsetX + (col + 1) * module, offsetY + (row + 1) * module, inkPaint);
                }
            }
        }
        canvas.restore();
        return top + boxHeight;
    }

    private float drawLine(Canvas canvas, TextElement element, float left, float top) {
        float thick = element.lineWidthMm * pixelsPerMm;
        boolean vertical = "v".equals(element.format);
        float right = vertical ? left + thick : left + element.widthMm * pixelsPerMm;
        float bottom = vertical ? top + element.heightMm * pixelsPerMm : top + thick;
        canvas.drawRect(left, top, right, bottom, inkPaint);
        return vertical ? bottom : top + Math.max(thick, element.heightMm * pixelsPerMm);
    }

    private float drawRect(Canvas canvas, TextElement element, float left, float top) {
        float width = element.widthMm * pixelsPerMm;
        float height = element.heightMm * pixelsPerMm;
        if (element.filled) {
            canvas.drawRect(left, top, left + width, top + height, inkPaint);
        } else {
            inkPaint.setStyle(Paint.Style.STROKE);
            inkPaint.setStrokeWidth(Math.max(dp(0.5f), element.lineWidthMm * pixelsPerMm));
            canvas.drawRect(left, top, left + width, top + height, inkPaint);
            inkPaint.setStyle(Paint.Style.FILL);
        }
        return top + height;
    }

    private float drawImage(Canvas canvas, TextElement element, float left, float top) {
        float boxWidth = element.widthMm * pixelsPerMm;
        float boxHeight = element.heightMm * pixelsPerMm;
        boolean drawn = ImageElementRenderer.draw(canvas, element.text, left, top,
                boxWidth, boxHeight, element.format);
        if (!drawn) {
            // 未选图或文件丢失：占位框 + 提示
            inkPaint.setStyle(Paint.Style.STROKE);
            inkPaint.setStrokeWidth(dp(1));
            inkPaint.setColor(0xFFB0B7B1);
            canvas.drawRect(left, top, left + boxWidth, top + boxHeight, inkPaint);
            inkPaint.setColor(0xFF102F29);
            inkPaint.setStyle(Paint.Style.FILL);
            textPaint.setColor(0xFFB0B7B1);
            textPaint.setTextSize(dp(9));
            textPaint.setFakeBoldText(false);
            float textWidth = textPaint.measureText("点右侧更换图片");
            canvas.drawText("点右侧更换图片", left + (boxWidth - textWidth) / 2f,
                    top + boxHeight / 2f, textPaint);
        }
        return top + boxHeight;
    }

    private float drawIcon(Canvas canvas, TextElement element, float left, float top) {
        float boxWidth = element.widthMm * pixelsPerMm;
        float boxHeight = element.heightMm * pixelsPerMm;
        float size = Math.min(boxWidth, boxHeight) * 0.86f;
        float x = left + (boxWidth - size) / 2f;
        float y = top + (boxHeight - size) / 2f;
        IconCatalog.draw(canvas, element.format, x, y, size,
                Math.max(dp(0.5f), element.lineWidthMm * pixelsPerMm), inkPaint);
        return top + boxHeight;
    }

    private float drawTable(Canvas canvas, TextElement element, float left, float top) {
        float boxWidth = element.widthMm * pixelsPerMm;
        float boxHeight = element.heightMm * pixelsPerMm;
        int rows = Math.max(1, element.tableRows);
        int cols = Math.max(1, element.tableCols);
        float stroke = Math.max(dp(0.4f), element.lineWidthMm * pixelsPerMm);
        inkPaint.setStyle(Paint.Style.STROKE);
        inkPaint.setStrokeWidth(stroke);
        canvas.drawRect(left, top, left + boxWidth, top + boxHeight, inkPaint);
        for (int row = 1; row < rows; row++) {
            float y = top + boxHeight * row / rows;
            canvas.drawLine(left, y, left + boxWidth, y, inkPaint);
        }
        for (int col = 1; col < cols; col++) {
            float x = left + boxWidth * col / cols;
            canvas.drawLine(x, top, x, top + boxHeight, inkPaint);
        }
        inkPaint.setStyle(Paint.Style.FILL);
        String[] cells = element.tableCells();
        float cellWidth = boxWidth / cols;
        float cellHeight = boxHeight / rows;
        textPaint.setColor(0xFF102F29);
        textPaint.setTextSize(Math.max(dp(5), element.fontSizePt * 25.4f / 72f * pixelsPerMm));
        textPaint.setFakeBoldText(element.bold);
        for (int index = 0; index < Math.min(cells.length, rows * cols); index++) {
            if (cells[index].isEmpty()) continue;
            int row = index / cols;
            int col = index % cols;
            StaticLayout layout = StaticLayout.Builder.obtain(cells[index], 0,
                            cells[index].length(), textPaint, Math.max(1, Math.round(cellWidth)))
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .build();
            canvas.save();
            canvas.translate(left + col * cellWidth,
                    top + row * cellHeight + Math.max(0f, (cellHeight - layout.getHeight()) / 2f));
            layout.draw(canvas);
            canvas.restore();
        }
        return top + boxHeight;
    }

    private float drawArcText(Canvas canvas, TextElement element, float left, float top) {
        float boxWidth = element.widthMm * pixelsPerMm;
        float boxHeight = element.heightMm * pixelsPerMm;
        float radius = Math.min(boxWidth / 2f, boxHeight);
        float centerX = left + boxWidth / 2f;
        boolean down = "down".equals(element.format);
        float centerY = down ? top : top + boxHeight;
        android.graphics.RectF oval = new android.graphics.RectF(
                centerX - radius, centerY - radius, centerX + radius, centerY + radius);
        android.graphics.Path path = new android.graphics.Path();
        path.addArc(oval, 180f, down ? -180f : 180f);
        textPaint.setColor(0xFF102F29);
        textPaint.setTextSize(Math.max(dp(7), element.fontSizePt * 25.4f / 72f * pixelsPerMm));
        textPaint.setFakeBoldText(element.bold);
        float textWidth = textPaint.measureText(element.text);
        float arcLength = (float) Math.PI * radius;
        float offset = Math.max(0f, (arcLength - textWidth) / 2f);
        canvas.drawTextOnPath(element.text, path, offset, 0f, textPaint);
        return top + boxHeight;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            calculatePageBounds();
            TextElement current = getSelectedElement();
            boolean resizeHit = current != null && !current.locked
                    && resizeHandleHit(current, event.getX(), event.getY());
            TextElement hit = resizeHit ? current : hitTest(event.getX(), event.getY());
            selectedId = hit == null ? null : hit.id;
            resizing = resizeHit;
            dragging = !resizing && hit != null && !hit.locked;
            lastTouchX = event.getX();
            lastTouchY = event.getY();
            if (resizing) {
                resizeStartTouchX = event.getX();
                resizeStartTouchY = event.getY();
                resizeStartWidthMm = current.widthMm;
                resizeStartHeightMm = current.heightMm;
            }
            if (listener != null) listener.onSelectionChanged(hit);
            invalidate();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && resizing) {
            TextElement selected = getSelectedElement();
            if (selected != null) {
                float width = Math.max(LabelDocument.MIN_ELEMENT_SIZE_MM,
                        resizeStartWidthMm + (event.getX() - resizeStartTouchX) / pixelsPerMm);
                float height = Math.max(LabelDocument.MIN_ELEMENT_SIZE_MM,
                        resizeStartHeightMm + (event.getY() - resizeStartTouchY) / pixelsPerMm);
                if (TextElement.TYPE_QRCODE.equals(selected.type)) {
                    float dxMm = (event.getX() - resizeStartTouchX) / pixelsPerMm;
                    float dyMm = (event.getY() - resizeStartTouchY) / pixelsPerMm;
                    float deltaMm = Math.abs(dxMm) >= Math.abs(dyMm) ? dxMm : dyMm;
                    float side = Math.max(LabelDocument.MIN_ELEMENT_SIZE_MM,
                            resizeStartWidthMm + deltaMm);
                    selected.widthMm = side;
                    selected.heightMm = side;
                } else if (TextElement.TYPE_LINE.equals(selected.type)) {
                    if ("v".equals(selected.format)) selected.heightMm = height;
                    else selected.widthMm = width;
                } else {
                    selected.widthMm = width;
                    selected.heightMm = height;
                }
                document.normalize();
                if (listener != null) listener.onElementResized(selected);
                invalidate();
            }
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
            resizing = false;
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
            calculateElementBounds(element, itemBounds);
            itemBounds.inset(-dp(4), -dp(4));
            if (itemBounds.contains(x, y)) return element;
        }
        return null;
    }

    private void drawSelectionOverlay(Canvas canvas) {
        TextElement selected = getSelectedElement();
        if (selected == null) return;
        calculateElementBounds(selected, itemBounds);
        canvas.drawRoundRect(itemBounds, dp(2), dp(2), selectionPaint);
        if (!selected.locked) {
            canvas.drawCircle(itemBounds.right, itemBounds.bottom, dp(7), resizeHandlePaint);
            selectionPaint.setColor(Color.WHITE);
            canvas.drawLine(itemBounds.right - dp(3), itemBounds.bottom,
                    itemBounds.right + dp(3), itemBounds.bottom, selectionPaint);
            canvas.drawLine(itemBounds.right, itemBounds.bottom - dp(3),
                    itemBounds.right, itemBounds.bottom + dp(3), selectionPaint);
            selectionPaint.setColor(0xFFE66F43);
        }
    }

    private boolean resizeHandleHit(TextElement element, float x, float y) {
        calculateElementBounds(element, itemBounds);
        float radius = dp(16);
        return Math.abs(x - itemBounds.right) <= radius
                && Math.abs(y - itemBounds.bottom) <= radius;
    }

    private void calculateElementBounds(TextElement element, RectF out) {
        float left = pageBounds.left + element.xMm * pixelsPerMm;
        float top = pageBounds.top + element.yMm * pixelsPerMm;
        float width = Math.max(1f, element.widthMm * pixelsPerMm);
        float height = Math.max(1f, element.heightMm * pixelsPerMm);
        if (TextElement.TYPE_LINE.equals(element.type)) {
            if ("v".equals(element.format)) width = Math.max(dp(8), element.lineWidthMm * pixelsPerMm);
            else height = Math.max(dp(8), element.lineWidthMm * pixelsPerMm);
        }
        out.set(left, top, left + width, top + height);
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
