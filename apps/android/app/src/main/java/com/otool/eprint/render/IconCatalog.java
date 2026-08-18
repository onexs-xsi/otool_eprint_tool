package com.otool.eprint.render;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/** 内置矢量图标：8 个离线绘制的简易图形，画布与打印渲染共用。 */
public final class IconCatalog {
    public static final String[] IDS = {
            "check", "cross", "warning", "star", "arrow_right", "circle", "plus", "home"};
    public static final String[] LABELS = {
            "对勾", "叉", "警示", "星", "右箭头", "圆", "加", "房屋"};

    private IconCatalog() {}

    public static int indexOf(String id) {
        for (int index = 0; index < IDS.length; index++) {
            if (IDS[index].equals(id)) return index;
        }
        return 0;
    }

    /** 在 (left,top,size×size) 区域内绘制图标；paint 颜色由调用方给定，此处只设线宽/样式。 */
    public static void draw(Canvas canvas, String id, float left, float top, float size,
                            float strokeWidth, Paint paint) {
        Paint.Style originalStyle = paint.getStyle();
        float originalStrokeWidth = paint.getStrokeWidth();
        Paint.Cap originalStrokeCap = paint.getStrokeCap();
        Paint.Join originalStrokeJoin = paint.getStrokeJoin();
        try {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(strokeWidth);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            float x = left, y = top, s = size;
            switch (id == null ? "check" : id) {
            case "cross":
                canvas.drawLine(x + 0.2f * s, y + 0.2f * s, x + 0.8f * s, y + 0.8f * s, paint);
                canvas.drawLine(x + 0.8f * s, y + 0.2f * s, x + 0.2f * s, y + 0.8f * s, paint);
                break;
            case "warning":
                Path triangle = new Path();
                triangle.moveTo(x + 0.5f * s, y + 0.12f * s);
                triangle.lineTo(x + 0.94f * s, y + 0.88f * s);
                triangle.lineTo(x + 0.06f * s, y + 0.88f * s);
                triangle.close();
                canvas.drawPath(triangle, paint);
                canvas.drawLine(x + 0.5f * s, y + 0.38f * s, x + 0.5f * s, y + 0.62f * s, paint);
                canvas.drawLine(x + 0.5f * s, y + 0.72f * s, x + 0.5f * s, y + 0.74f * s, paint);
                break;
            case "star":
                canvas.drawPath(starPath(x + 0.5f * s, y + 0.5f * s, 0.42f * s, 0.17f * s), paint);
                break;
            case "arrow_right":
                canvas.drawLine(x + 0.15f * s, y + 0.5f * s, x + 0.8f * s, y + 0.5f * s, paint);
                canvas.drawLine(x + 0.58f * s, y + 0.28f * s, x + 0.84f * s, y + 0.5f * s, paint);
                canvas.drawLine(x + 0.84f * s, y + 0.5f * s, x + 0.58f * s, y + 0.72f * s, paint);
                break;
            case "circle":
                canvas.drawCircle(x + 0.5f * s, y + 0.5f * s, 0.34f * s, paint);
                break;
            case "plus":
                canvas.drawLine(x + 0.5f * s, y + 0.2f * s, x + 0.5f * s, y + 0.8f * s, paint);
                canvas.drawLine(x + 0.2f * s, y + 0.5f * s, x + 0.8f * s, y + 0.5f * s, paint);
                break;
            case "home":
                Path roof = new Path();
                roof.moveTo(x + 0.14f * s, y + 0.52f * s);
                roof.lineTo(x + 0.5f * s, y + 0.16f * s);
                roof.lineTo(x + 0.86f * s, y + 0.52f * s);
                canvas.drawPath(roof, paint);
                canvas.drawRect(x + 0.26f * s, y + 0.5f * s, x + 0.74f * s, y + 0.86f * s, paint);
                break;
            case "check":
            default:
                canvas.drawLine(x + 0.16f * s, y + 0.55f * s, x + 0.4f * s, y + 0.8f * s, paint);
                canvas.drawLine(x + 0.4f * s, y + 0.8f * s, x + 0.86f * s, y + 0.24f * s, paint);
                break;
            }
        } finally {
            paint.setStyle(originalStyle);
            paint.setStrokeWidth(originalStrokeWidth);
            paint.setStrokeCap(originalStrokeCap);
            paint.setStrokeJoin(originalStrokeJoin);
        }
    }

    private static Path starPath(float centerX, float centerY, float outer, float inner) {
        Path path = new Path();
        for (int point = 0; point < 10; point++) {
            double angle = -Math.PI / 2 + point * Math.PI / 5;
            float radius = point % 2 == 0 ? outer : inner;
            float px = centerX + (float) (radius * Math.cos(angle));
            float py = centerY + (float) (radius * Math.sin(angle));
            if (point == 0) path.moveTo(px, py);
            else path.lineTo(px, py);
        }
        path.close();
        return path;
    }
}
