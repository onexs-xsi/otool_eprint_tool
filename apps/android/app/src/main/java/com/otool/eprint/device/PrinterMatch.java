package com.otool.eprint.device;

import androidx.annotation.Nullable;

public final class PrinterMatch {
    public final int score;
    public final String confidence;
    public final String reason;
    @Nullable public final PrinterProfile suggestedProfile;
    @Nullable public final PrinterIdentity identity;
    public final boolean privateProtocolRisk;

    public PrinterMatch(int score, String confidence, String reason,
                        @Nullable PrinterProfile suggestedProfile,
                        @Nullable PrinterIdentity identity, boolean privateProtocolRisk) {
        this.score = score;
        this.confidence = confidence;
        this.reason = reason;
        this.suggestedProfile = suggestedProfile;
        this.identity = identity;
        this.privateProtocolRisk = privateProtocolRisk;
    }

    public boolean isLikelyPrinter() {
        return score >= 40;
    }

    public boolean isRecommended() {
        return score >= 85 && suggestedProfile != null && !privateProtocolRisk;
    }
}
