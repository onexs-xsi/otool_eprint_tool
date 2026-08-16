package com.otool.eprint.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
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
        return bitmap;
    }

    private static void drawElement(Canvas canvas, LabelDocument document, TextElement element) {
        int x = LabelDocument.mmToDots(element.xMm, document.dpi);
        int y = LabelDocument.mmToDots(element.yMm, document.dpi);
        int width = Math.max(1, LabelDocument.mmToDots(element.widthMm, document.dpi));

        TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG | TextPaint.SUBPIXEL_TEXT_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(Math.max(1f, element.fontSizePt * document.dpi / 72f));
        paint.setFakeBoldText(element.bold);

        StaticLayout layout = StaticLayout.Builder.obtain(element.text, 0, element.text.length(), paint, width)
                .setAlignment(toAlignment(element.alignment))
                .setIncludePad(false)
                .build();
        canvas.save();
        canvas.translate(x, y);
        layout.draw(canvas);
        canvas.restore();
    }

    private static Layout.Alignment toAlignment(int alignment) {
        if (alignment == TextElement.ALIGN_CENTER) return Layout.Alignment.ALIGN_CENTER;
        if (alignment == TextElement.ALIGN_RIGHT) return Layout.Alignment.ALIGN_OPPOSITE;
        return Layout.Alignment.ALIGN_NORMAL;
    }
}
