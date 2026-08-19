package com.otool.eprint.render;

import android.graphics.Paint;

/**
 * 单行文本实测缩字：以 Paint.measureText 为准把过长单行缩进盒宽，
 * 预览（LabelCanvasView）与打印（LabelBitmapRenderer）共用，保证两路一致。
 * 工厂侧的字宽估算是经验值（0.6em/半角），长型号仍可能在真实字体下折行，
 * 渲染层的实测缩字是兜底——型号等关键字段必须始终单行完整。
 */
public final class TextFitUtil {
    private TextFitUtil() {}

    /**
     * 若 content（不含换行）在 basePx 字号下超过 maxWidthPx，按比例收缩 paint 字号
     * 直到放下（下限 minPx），返回生效字号；本来就放得下则不改动，返回 basePx。
     */
    public static float fitSingleLine(Paint paint, String content, float basePx,
                                      float maxWidthPx, float minPx) {
        if (content == null || content.isEmpty() || content.contains("\n")) return basePx;
        float size = basePx;
        paint.setTextSize(size);
        float width = paint.measureText(content);
        while (width > maxWidthPx && size > minPx) {
            float next = size * (maxWidthPx / width) * 0.98f;
            size = Math.max(minPx, Math.min(size * 0.94f, next));
            paint.setTextSize(size);
            width = paint.measureText(content);
        }
        return size;
    }
}
