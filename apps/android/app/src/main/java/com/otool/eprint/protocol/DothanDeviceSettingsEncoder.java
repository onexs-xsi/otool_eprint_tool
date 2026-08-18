package com.otool.eprint.protocol;

import com.otool.eprint.device.PrinterProfile;

/** Encodes persistent P1 device settings using the same guarded session envelope per setting. */
public final class DothanDeviceSettingsEncoder {
    private DothanDeviceSettingsEncoder() {}

    public static byte[] encode(PrinterProfile profile) {
        if (profile == null || profile.protocol != ProtocolType.DOTHAN_PRIVATE) {
            throw new IllegalArgumentException("P1 settings require a Dothan private profile");
        }
        byte[] experimentalExtras = profile.applyDothanExtras
                ? DothanFrameCodec.join(
                setting(DothanCommandCatalog.motorMode(profile.motorMode)),
                setting(DothanCommandCatalog.language(profile.language)))
                : new byte[0];
        return DothanFrameCodec.join(
                setting(DothanCommandCatalog.density(profile.printDensity)),
                setting(DothanCommandCatalog.speed(profile.printSpeed)),
                setting(DothanCommandCatalog.gapType(profile.paperType)),
                setting(DothanCommandCatalog.gapLength(profile.gapLengthTenthsMm)),
                setting(DothanCommandCatalog.flags(profile.autoFeed ? 1 : 0, 4)),
                setting(DothanCommandCatalog.flags(profile.beep ? 0 : 1, 8)),
                setting(DothanCommandCatalog.autoPowerOffMinutes(profile.autoPowerOffMinutes)),
                experimentalExtras
        );
    }

    private static byte[] setting(byte[] command) {
        return DothanFrameCodec.join(
                DothanCommandCatalog.sessionStart(false),
                command,
                DothanCommandCatalog.sessionClose(),
                DothanCommandCatalog.bufferQuery()
        );
    }
}
