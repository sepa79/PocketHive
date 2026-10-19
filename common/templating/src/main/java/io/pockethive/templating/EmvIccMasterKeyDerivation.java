package io.pockethive.templating;

import java.util.HexFormat;

/**
 * Responsibility: construct Option A diversification from validated PAN/PSN and derive ICC key bytes.
 * Must not: select Option B, calculate session keys or interpret transaction data.
 * Contract: RESP-EMV-ICC-KEY — docs/architecture/runtime-responsibilities.md#resp-emv-icc-key.
 */
final class EmvIccMasterKeyDerivation {
    private static final int DIVERSIFICATION_DIGITS = 16;

    private EmvIccMasterKeyDerivation() {
    }

    static byte[] derive(byte[] issuerKey, String pan, String psn) {
        String digits = pan + psn;
        String diversification = digits.length() >= DIVERSIFICATION_DIGITS
            ? digits.substring(digits.length() - DIVERSIFICATION_DIGITS)
            : "0".repeat(DIVERSIFICATION_DIGITS - digits.length()) + digits;
        byte[] panData = HexFormat.of().parseHex(diversification);
        byte[] blocks = new byte[panData.length * 2];
        System.arraycopy(panData, 0, blocks, 0, panData.length);
        for (int i = 0; i < panData.length; i++) {
            blocks[panData.length + i] = (byte) ~panData[i];
        }
        return EmvKeyDerivationCipher.encrypt(issuerKey, blocks);
    }
}
