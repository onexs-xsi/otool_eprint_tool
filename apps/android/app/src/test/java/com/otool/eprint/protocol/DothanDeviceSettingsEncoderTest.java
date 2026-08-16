package com.otool.eprint.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.otool.eprint.device.DeviceCatalog;

import org.junit.Test;

public final class DothanDeviceSettingsEncoderTest {
    @Test
    public void encodesObservedOfficialP1DefaultsWithGuardedSessions() {
        byte[] stream = DothanDeviceSettingsEncoder.encode(
                DeviceCatalog.find("p1_private_203"));
        int offset = 0;
        int sessions = 0;
        boolean density = false;
        boolean speed = false;
        boolean gapType = false;
        boolean gapLength = false;
        boolean autoFeed = false;
        boolean beep = false;
        boolean powerOff = false;
        while (offset < stream.length) {
            int length = DothanFrameCodec.encodedLength(stream, offset, stream.length - offset);
            assertTrue(length > 0);
            DothanFrame frame = DothanFrameCodec.decode(
                    java.util.Arrays.copyOfRange(stream, offset, offset + length));
            if (frame.type == 0x80 && frame.payload.length == 1
                    && (frame.payload[0] & 0xFF) == 0x7F) sessions++;
            if (frame.type == 0x43) density = equals(frame.payload, 14);
            if (frame.type == 0x44) speed = equals(frame.payload, 0);
            if (frame.type == 0x42) gapType = equals(frame.payload, 2);
            if (frame.type == 0x45) {
                assertArrayEquals(new byte[]{(byte) 0xC1, 0x2C}, frame.payload);
                gapLength = true;
            }
            if (frame.type == 0x4D && frame.payload.length == 5) {
                if (frame.payload[3] == 4) autoFeed = frame.payload[0] == 1;
                // P1 uses a mute bit for this setting: zero enables the prompt sound.
                if (frame.payload[3] == 8) beep = frame.payload[0] == 0;
            }
            if (frame.type == 0x48) powerOff = equals(frame.payload, 15);
            offset += length;
        }
        assertEquals(7, sessions);
        assertTrue(density && speed && gapType && gapLength
                && autoFeed && beep && powerOff);
    }

    private static boolean equals(byte[] payload, int value) {
        return payload.length == 1 && (payload[0] & 0xFF) == value;
    }
}
