package com.otool.eprint.model;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 时间元素的格式预设；渲染与打印时取当前时间格式化。 */
public enum TimeElementFormat {
    DATE("yyyy-MM-dd", "2026-08-18"),
    DATE_TIME("yyyy-MM-dd HH:mm", "2026-08-18 15:35"),
    TIME("HH:mm:ss", "15:35:08"),
    MONTH_DAY("MM-dd", "08-18");

    public final String pattern;
    public final String sample;

    TimeElementFormat(String pattern, String sample) {
        this.pattern = pattern;
        this.sample = sample;
    }

    public String formatNow() {
        return new SimpleDateFormat(pattern, Locale.US).format(new Date());
    }

    public static TimeElementFormat byPattern(String pattern) {
        for (TimeElementFormat value : values()) {
            if (value.pattern.equals(pattern)) return value;
        }
        return DATE;
    }

    @Override
    public String toString() {
        return sample;
    }
}
