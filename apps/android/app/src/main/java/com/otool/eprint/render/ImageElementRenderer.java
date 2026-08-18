package com.otool.eprint.render;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.LinkedHashMap;
import java.util.Map;

/** 图片元素渲染：解码缓存 + 盒内保持宽高比居中绘制。 */
public final class ImageElementRenderer {
    public static final String[] FIT_MODE_IDS = {"fit", "fill", "stretch"};
    public static final String[] FIT_MODE_LABELS = {"完整适应", "铺满裁切", "拉伸填满"};
    private static final int CACHE_CAPACITY = 8;
    private static final Map<String, Bitmap> CACHE = new LinkedHashMap<String, Bitmap>(
            CACHE_CAPACITY, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > CACHE_CAPACITY;
        }
    };
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private ImageElementRenderer() {}

    /** 盒内保持宽高比绘制图片；路径为空或文件不可读时返回 false（调用方画占位框）。 */
    public static synchronized boolean draw(Canvas canvas, String path,
                                            float left, float top, float width, float height) {
        return draw(canvas, path, left, top, width, height, "fit");
    }

    /** 支持完整适应、铺满裁切和拉伸填满三种盒内布局。 */
    public static synchronized boolean draw(Canvas canvas, String path,
                                            float left, float top, float width, float height,
                                            String fitMode) {
        Bitmap bitmap = decode(path);
        if (bitmap == null) return false;
        if ("stretch".equals(fitMode)) {
            canvas.drawBitmap(bitmap, null,
                    new RectF(left, top, left + width, top + height), PAINT);
            return true;
        }
        float scale = "fill".equals(fitMode)
                ? Math.max(width / bitmap.getWidth(), height / bitmap.getHeight())
                : Math.min(width / bitmap.getWidth(), height / bitmap.getHeight());
        float drawnWidth = bitmap.getWidth() * scale;
        float drawnHeight = bitmap.getHeight() * scale;
        float x = left + (width - drawnWidth) / 2f;
        float y = top + (height - drawnHeight) / 2f;
        canvas.save();
        canvas.clipRect(left, top, left + width, top + height);
        canvas.drawBitmap(bitmap, null, new RectF(x, y, x + drawnWidth, y + drawnHeight), PAINT);
        canvas.restore();
        return true;
    }

    public static int fitModeIndex(String value) {
        for (int index = 0; index < FIT_MODE_IDS.length; index++) {
            if (FIT_MODE_IDS[index].equals(value)) return index;
        }
        return 0;
    }

    public static synchronized void invalidate(String path) {
        if (path != null) CACHE.remove(path);
    }

    private static Bitmap decode(String path) {
        if (path == null || path.isEmpty()) return null;
        Bitmap cached = CACHE.get(path);
        if (cached != null && !cached.isRecycled()) return cached;
        Bitmap bitmap = BitmapFactory.decodeFile(path);
        if (bitmap != null) CACHE.put(path, bitmap);
        return bitmap;
    }
}
