package com.otool.eprint.scan.opencv;

import android.util.Log;

import org.opencv.android.OpenCVLoader;

/**
 * OpenCV native 加载门卫。官方 4.9+ AAR 内置本地加载（{@link OpenCVLoader#initLocal()}），
 * 无需 OpenCV Manager；加载失败时结果缓存为 false，所有 OpenCV 增强路径回退纯 Java 实现。
 */
public final class OpenCVSupport {
    private static final String TAG = "OpenCVSupport";
    private static volatile Boolean available;

    private OpenCVSupport() {}

    /** 首次调用触发 native 加载并缓存结果；任何异常都视为不可用。 */
    public static boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) return cached;
        synchronized (OpenCVSupport.class) {
            if (available == null) {
                boolean loaded = false;
                try {
                    loaded = OpenCVLoader.initLocal();
                } catch (Throwable loadError) {
                    Log.w(TAG, "OpenCV native 加载异常，增强路径回退 Java 实现", loadError);
                }
                if (!loaded) {
                    Log.w(TAG, "OpenCV native 不可用，增强路径回退 Java 实现");
                } else {
                    Log.i(TAG, "OpenCV native 已加载");
                }
                available = loaded;
            }
            return available;
        }
    }
}
