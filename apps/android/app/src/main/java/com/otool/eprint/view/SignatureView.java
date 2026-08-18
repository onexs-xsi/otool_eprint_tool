package com.otool.eprint.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** 手写签名画板：轨迹描画、清除、导出为白底黑字位图。 */
public final class SignatureView extends View {
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Path> strokes = new ArrayList<>();
    private Path current;

    public SignatureView(Context context) {
        this(context, null);
    }

    public SignatureView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        ink.setColor(Color.BLACK);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(7f);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        setBackgroundColor(Color.WHITE);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize(560, widthMeasureSpec);
        setMeasuredDimension(width, width / 2);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (Path path : strokes) canvas.drawPath(path, ink);
        if (current != null) canvas.drawPath(current, ink);
        // 底部基线提示
        ink.setColor(0xFFCCCCCC);
        ink.setStrokeWidth(2f);
        canvas.drawLine(20, getHeight() - 30, getWidth() - 20, getHeight() - 30, ink);
        ink.setColor(Color.BLACK);
        ink.setStrokeWidth(7f);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                current = new Path();
                current.moveTo(x, y);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (current != null) {
                    current.lineTo(x, y);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (current != null) {
                    current.lineTo(x, y);
                    strokes.add(current);
                    current = null;
                    invalidate();
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    public void clear() {
        strokes.clear();
        current = null;
        invalidate();
    }

    public boolean isEmpty() {
        return strokes.isEmpty();
    }

    /** 在 UI 线程生成白底黑字位图快照；签名为空返回 null。 */
    @Nullable
    public Bitmap createBitmap() {
        if (isEmpty()) return null;
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, getWidth()), Math.max(1, getHeight()),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(ink);
        for (Path path : strokes) canvas.drawPath(path, paint);
        return bitmap;
    }

    /** 在工作线程把位图导出为 PNG，返回文件路径。位图在完成后由该方法回收。 */
    @Nullable
    @WorkerThread
    public static String saveBitmap(Bitmap bitmap, File directory, String fileName) {
        if (!directory.exists() && !directory.mkdirs()) {
            bitmap.recycle();
            return null;
        }
        File file = new File(directory, fileName);
        try (OutputStream out = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null;
        } catch (IOException error) {
            return null;
        } finally {
            bitmap.recycle();
        }
        return file.getAbsolutePath();
    }
}
