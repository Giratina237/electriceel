package com.google.android.exoplayer2.source.sabr.parser.exceptions;

import com.google.android.exoplayer2.source.sabr.parser.parts.PoTokenStatusSabrPart.PoTokenStatus;

/**
 * The server won't send media past its grace period (about a minute) with this poToken. It requires attestation
 * (StreamProtectionStatus ATTESTATION_REQUIRED): the one sent is invalid, or none was sent. Or the attestation is still pending
 * (ATTESTATION_PENDING) and it stopped sending media (see SabrStream#onResponseEnd).
 */
public class PoTokenError extends SabrStreamError {
    /**
     * INVALID when a poToken was sent, MISSING when none was. PENDING and PENDING_MISSING likewise, its attestation pending.
     */
    public final PoTokenStatus status;

    public PoTokenError(PoTokenStatus status) {
        super(getMessage(status));
        this.status = status;
    }

    private static String getMessage(PoTokenStatus status) {
        if (status == PoTokenStatus.INVALID) {
            return "SABR server rejected the poToken";
        } else if (status == PoTokenStatus.PENDING || status == PoTokenStatus.PENDING_MISSING) {
            return "SABR server stopped sending media pending the poToken's attestation";
        }

        return "SABR server requires a poToken";
    }
}
