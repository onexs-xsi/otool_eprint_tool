package com.otool.eprint.protocol;

import com.otool.eprint.device.PrinterProfile;

/** Encodes persistent P1 device settings using the same guarded session envelope per setting. */
public final class DothanDeviceSettingsEncoder {
    private DothanDeviceSettingsEncoder() {}

    public static byte[] encode(PrinterProfile profile) {
        if (profile == null || profile.protocol != ProtocolType.DOTHAN_PRIVATE) {
            throw new IllegalArgumentException("P1 settings require a Dothan private profile");
        }
        return DothanFrameCodec.join(
                setting(DothanFrameCodec.encode(0x43,
                        (byte) DothanProtocolEncoder.wireLevel(profile.printDensity))),
                setting(DothanFrameCodec.encode(0x44,
                        (byte) DothanProtocolEncoder.wireLevel(profile.printSpeed))),
                setting(DothanFrameCodec.encode(0x42,
                        (byte) DothanProtocolEncoder.gapType(profile.paperType))),
                setting(DothanFrameCodec.encode(0x45,
                        DothanProtocolEncoder.distanceHundredthsMm(profile.gapLengthTenthsMm))),
                setting(DothanFrameCodec.encode(0x4D,
                        (byte) (profile.autoFeed ? 1 : 0), (byte) 0, (byte) 0,
                        (byte) 4, (byte) 0)),
                setting(DothanFrameCodec.encode(0x4D,
                        // P1 stores this bit as "mute": 0 means the completion prompt is on.
                        (byte) (profile.beep ? 0 : 1), (byte) 0, (byte) 0,
                        (byte) 8, (byte) 0)),
                setting(DothanFrameCodec.encode(0x48,
                        DothanFrameCodec.variableShort(profile.autoPowerOffMinutes)))
        );
    }

    private static byte[] setting(byte[] command) {
        return DothanFrameCodec.join(
                DothanFrameCodec.encode(0x80, (byte) 0x7F),
                command,
                DothanFrameCodec.encode(0x80, (byte) 0),
                DothanFrameCodec.encode(0x77)
        );
    }
}
