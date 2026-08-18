package com.otool.eprint.protocol;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

public final class DothanCommandCatalogTest {
    @Test
    public void speedAndDateTimeUsesBinaryFieldsAndLittleEndianSpeed() {
        Calendar value = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        value.clear();
        value.set(2026, Calendar.AUGUST, 18, 14, 35, 59);
        DothanFrame frame = DothanFrameCodec.decode(
                DothanCommandCatalog.speedAndDateTime((short) 0x1234, value));
        assertArrayEquals(new byte[]{
                0x34, 0x12, 0x07, (byte) 0xEA,
                8, 18, 2, 14, 35, 59
        }, frame.payload);
    }
}
