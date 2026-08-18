package com.otool.eprint.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import com.otool.eprint.model.LabelDocument;
import com.otool.eprint.model.TextElement;

public final class LabelBitmapRenderer {
    private LabelBitmapRenderer() {}

    public static Bitmap render(LabelDocument document) {
        document.normalize();
        int width = LabelDocument.mmToDots(document.widthMm, document.dpi);
        int height = LabelDocument.mmToDots(document.heightMm, document.dpi);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);

        for (TextElement element : document.elements) {
            drawElement(canvas, document, element);
        }
        drawBorder(canvas, document, width, height);
        return bitmap;
    }

    private static void drawBorder(Canvas canvas, LabelDocument document, int width, int height) {
        if (document.borderStyle <= 0) return;
        Paint ink = new Paint();
        ink.setColor(Color.BLACK);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(Math.max(1, LabelDocument.mmToDots(0.3f, document.dpi)));
        int single = LabelDocument.mmToDots(0.5f, document.dpi);
        canvas.drawRect(single, single, width - single, height - single, ink);
        if (document.borderStyle == 2) {
            int dual = LabelDocument.mmToDots(1.4f, document.dpi);
            canvas.drawRect(dual, dual, width - dual, height - dual, ink);
        }
    }

    private static void drawElement(Canvas canvas, LabelDocument document, TextElement element) {
        switch (element.type) {
            case TextElement.TYPE_BARCODE:
            case TextElement.TYPE_QRCODE:
                drawCode(canvas, document, element);
                break;
            case TextElement.TYPE_LINE:
                drawLine(canvas, document, element);
                break;
            case TextElement.TYPE_RECT:
                drawRect(canvas, document, element);
                break;
            case TextElement.TYPE_IMAGE:
                drawImage(canvas, document, element);
                break;
            case TextElement.TYPE_ICON:
                drawIcon(canvas, document, element);
                break;
            case TextElement.TYPE_TABLE:
                drawTable(canvas, document, element);
                break;
            case TextElement.TYPE_ARCTEXT:
                drawArcText(canvas, document, element);
                break;
            case TextElement.TYPE_TIME:
            case TextElement.TYPE_SERIAL:
            case TextElement.TYPE_TEXT:
            default:
                drawText(canvas, document, element, element.displayText());
                break;
        }
    }

    private static void drawImage(Canvas canvas, LabelDocument document, TextElement element) {
        int x = LabelDocument.mmPositionToDots(element.xMm, document.dpi);
        int y = LabelDocument.mmPositionToDots(element.yMm, document.dpi);
        int boxWidth = LabelDocument.mmToDots(element.widthMm, document.dpi);
        int boxHeight = LabelDocument.mmToDots(element.heightMm, document.dpi);
        ImageElementRenderer.draw(canvas, element.text, x, y, boxWidth, boxHeight, element.format);
    }

    private static void drawIcon(Canvas canvas, LabelDocument document, TextElement element) {
        int x = LabelDocument.mmPositionToDots(element.xMm, document.dpi);
        int y = LabelDocument.mmPositionToDots(element.yMm, document.dpi);
        int boxWidth = LabelDocument.mmToDots(element.widthMm, document.dpi);
        int boxHeight = LabelDocument.mmToDots(element.heightMm, document.dpi);
        float size = Math.min(boxWidth, boxHeight) * 0.86f;
        Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        ink.setColor(Color.BLACK);
        IconCatalog.draw(canvas, element.format,
                x + (boxWidth - size) / 2f, y + (boxHeight - size) / 2f, size,
                Math.max(1f, LabelDocument.mmToDots(element.lineWidthMm, document.dpi)), ink);
    }

    private static void drawTable(Canvas canvas, LabelDocument document, TextElement element) {
        int x = LabelDocument.mmPositionToDots(element.xMm, document.dpi);
        int y = LabelDocument.mmPositionToDots(element.yMm, document.dpi);
        int boxWidth = LabelDocument.mmToDots(element.widthMm, document.dpi);
        int boxHeight = LabelDocument.mmToDots(element.heightMm, document.dpi);
        int rows = Math.max(1, element.tableRows);
        int cols = Math.max(1, element.tableCols);
        Paint ink = new Paint();
        ink.setColor(Color.BLACK);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(Math.max(1, LabelDocument.mmToDots(element.lineWidthMm, document.dpi)));
        canvas.drawRect(x, y, x + boxWidth, y + boxHeight, ink);
        for (int row = 1; row < rows; row++) {
            int lineY = y + boxHeight * row / rows;
            canvas.drawLine(x, lineY, x + boxWidth, lineY, ink);
        }
        for (int col = 1; col < cols; col++) {
            int lineX = x + boxWidth * col / cols;
            canvas.drawLine(lineX, y, lineX, y + boxHeight, ink);
        }
        String[] cells = element.tableCells();
        float cellWidth = boxWidth / (float) cols;
        float cellHeight = boxHeight / (float) rows;
        TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(Math.max(1f, element.fontSizePt * document.dpi / 72f));
        paint.setFakeBoldText(element.bold);
        for (int index = 0; index < Math.min(cells.length, rows * cols); index++) {
            if (cells[index].isEmpty()) continue;
            int row = index / cols;
            int col = index % cols;
            StaticLayout layout = StaticLayout.Builder.obtain(cells[index], 0,
                            cells[index].length(), paint, Math.max(1, Math.round(cellWidth)))
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .build();
            canvas.save();
            canvas.translate(x + col * cellWidth,
                    y + row * cellHeight + Math.max(0f, (cellHeight - layout.getHeight()) / 2f));
            layout.draw(canvas);
            canvas.restore();
        }
    }

    private static void drawArcText(Canvas canvas, LabelDocument document, TextElement element) {
        float scale = document.dpi / 25.4f;
        float left = element.xMm * scale;
        float top = element.yMm * scale;
        float boxWidth = element.widthMm * scale;
        float boxHeight = element.heightMm * scale;
        float radius = Math.min(boxWidth / 2f, boxHeight);
        float centerX = left + boxWidth / 2f;
        boolean down = "down".equals(element.format);
        float centerY = down ? top : top + boxHeight;
        android.graphics.RectF oval = new android.graphics.RectF(
                centerX - radius, centerY - radius, centerX + radius, centerY + radius);
        android.graphics.Path path = new android.graphics.Path();
        path.addArc(oval, 180f, down ? -180f : 180f);
        TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG | TextPaint.SUBPIXEL_TEXT_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(Math.max(1f, element.fontSizePt * document.dpi / 72f));
        paint.setFakeBoldText(element.bold);
        float textWidth = paint.measureText(element.text);
        float arcLength = (float) Math.PI * radius;
        float offset = Math.max(0f, (arcLength - textWidth) / 2f);
        canvas.drawTextOnPath(element.text, path, offset, 0f, paint);
    }

    private static void drawText(Canvas canvas, LabelDocument document,
                                 TextElement element, String content) {
        int x = LabelDocument.mmPositionToDots(element.xMm, document.dpi);
        int y = LabelDocument.mmPositionToDots(element.yMm, document.dpi);
        int width = Math.max(1, LabelDocument.mmToDots(element.widthMm, document.dpi));

        TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG | TextPaint.SUBPIXEL_TEXT_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(Math.max(1f, element.fontSizePt * document.dpi / 72f));
        paint.setFakeBoldText(element.bold);

        StaticLayout layout = StaticLayout.Builder.obtain(content, 0, content.length(), paint, width)
                .setAlignment(toAlignment(element.alignment))
                .setIncludePad(false)
                .build();
        canvas.save();
        canvas.translate(x, y);
        canvas.clipRect(0, 0, width,
                Math.max(1, LabelDocument.mmToDots(element.heightMm, document.dpi)));
        layout.draw(canvas);
        canvas.restore();
    }

    private static void drawCode(Canvas canvas, LabelDocument document, TextElement element) {
        boolean[][] matrix;
        try {
            matrix = BarcodeRasterizer.matrix(element.text, element.format,
                    element.qrErrorCorrection, element.quietZoneModules);
        } catch (IllegalArgumentException invalid) {
            // 内容不符合码制：打印时跳过该元素，不输出非法点阵
            return;
        }
        int x = LabelDocument.mmPositionToDots(element.xMm, document.dpi);
        int y = LabelDocument.mmPositionToDots(element.yMm, document.dpi);
        int boxWidth = LabelDocument.mmToDots(element.widthMm, document.dpi);
        int boxHeight = LabelDocument.mmToDots(element.heightMm, document.dpi);
        Paint ink = new Paint();
        ink.setColor(Color.BLACK);
        ink.setAntiAlias(false);
        int rows = matrix.length;
        int cols = matrix[0].length;
        int rotation = element.rotationDegrees;
        int drawWidth = rotation == 90 || rotation == 270 ? boxHeight : boxWidth;
        int drawHeight = rotation == 90 || rotation == 270 ? boxWidth : boxHeight;
        canvas.save();
        if (rotation == 90) {
            canvas.translate(x + boxWidth, y);
            canvas.rotate(90f);
        } else if (rotation == 180) {
            canvas.translate(x + boxWidth, y + boxHeight);
            canvas.rotate(180f);
        } else if (rotation == 270) {
            canvas.translate(x, y + boxHeight);
            canvas.rotate(270f);
        } else {
            canvas.translate(x, y);
        }
        if (TextElement.TYPE_BARCODE.equals(element.type)) {
            String textPosition = element.effectiveBarcodeTextPosition();
            int labelHeight = !"none".equals(textPosition)
                    ? Math.min(Math.round(drawHeight * 0.28f),
                    Math.max(1, Math.round(element.fontSizePt * document.dpi / 72f * 1.25f)))
                    : 0;
            int barsHeight = Math.max(1, drawHeight - labelHeight);
            int barsTop = "top".equals(textPosition) ? labelHeight : 0;
            float moduleWidth = drawWidth / (float) cols;
            for (int col = 0; col < cols; col++) {
                boolean dark = false;
                for (int row = 0; row < rows && !dark; row++) dark = matrix[row][col];
                if (dark) {
                    canvas.drawRect(col * moduleWidth, barsTop,
                            (col + 1) * moduleWidth, barsTop + barsHeight, ink);
                }
            }
            if (!"none".equals(textPosition) && labelHeight > 1) {
                Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                labelPaint.setColor(Color.BLACK);
                labelPaint.setTextAlign(Paint.Align.CENTER);
                labelPaint.setTextSize(Math.max(1f, Math.min(labelHeight * 0.72f,
                        element.fontSizePt * document.dpi / 72f)));
                labelPaint.setFakeBoldText(element.bold);
                Paint.FontMetrics metrics = labelPaint.getFontMetrics();
                float labelTop = "top".equals(textPosition) ? 0 : barsHeight;
                float baseline = labelTop
                        + (labelHeight - metrics.bottom - metrics.top) / 2f;
                canvas.drawText(element.text, drawWidth / 2f, baseline, labelPaint);
            }
            canvas.restore();
            return;
        }
        // 二维码模块保持正方形并按整数点居中，避免插值破坏可扫性。
        int module = Math.max(1, Math.min(drawWidth / cols, drawHeight / rows));
        int offsetX = (drawWidth - module * cols) / 2;
        int offsetY = (drawHeight - module * rows) / 2;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                if (matrix[row][col]) {
                    canvas.drawRect(offsetX + col * module, offsetY + row * module,
                            offsetX + (col + 1) * module, offsetY + (row + 1) * module, ink);
                }
            }
        }
        canvas.restore();
    }

    private static void drawLine(Canvas canvas, LabelDocument document, TextElement element) {
        int x = LabelDocument.mmPositionToDots(element.xMm, document.dpi);
        int y = LabelDocument.mmPositionToDots(element.yMm, document.dpi);
        int thick = Math.max(1, LabelDocument.mmToDots(element.lineWidthMm, document.dpi));
        boolean vertical = "v".equals(element.format);
        int right = vertical ? x + thick
                : x + LabelDocument.mmToDots(element.widthMm, document.dpi);
        int bottom = vertical ? y + LabelDocument.mmToDots(element.heightMm, document.dpi)
                : y + thick;
        Paint ink = new Paint();
        ink.setColor(Color.BLACK);
        canvas.drawRect(x, y, right, bottom, ink);
    }

    private static void drawRect(Canvas canvas, LabelDocument document, TextElement element) {
        int x = LabelDocument.mmPositionToDots(element.xMm, document.dpi);
        int y = LabelDocument.mmPositionToDots(element.yMm, document.dpi);
        int right = x + LabelDocument.mmToDots(element.widthMm, document.dpi);
        int bottom = y + LabelDocument.mmToDots(element.heightMm, document.dpi);
        Paint ink = new Paint();
        ink.setColor(Color.BLACK);
        if (element.filled) {
            canvas.drawRect(x, y, right, bottom, ink);
        } else {
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(Math.max(1, LabelDocument.mmToDots(element.lineWidthMm, document.dpi)));
            canvas.drawRect(x, y, right, bottom, ink);
        }
    }

    private static Layout.Alignment toAlignment(int alignment) {
        if (alignment == TextElement.ALIGN_CENTER) return Layout.Alignment.ALIGN_CENTER;
        if (alignment == TextElement.ALIGN_RIGHT) return Layout.Alignment.ALIGN_OPPOSITE;
        return Layout.Alignment.ALIGN_NORMAL;
    }
}
