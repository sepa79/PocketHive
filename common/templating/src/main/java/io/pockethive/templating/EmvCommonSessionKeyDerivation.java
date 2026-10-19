package io.pockethive.templating;

/**
 * Responsibility: derive a common session key from validated ICC key and eight-byte diversification data.
 * Must not: construct ATC/UN input, select profiles or calculate a MAC.
 * Contract: RESP-EMV-SESSION-KEY — docs/architecture/runtime-responsibilities.md#resp-emv-session-key.
 */
final class EmvCommonSessionKeyDerivation {
    private static final int VARIANT_OFFSET = 2;
    private static final byte LEFT_VARIANT = (byte) 0xF0;
    private static final byte RIGHT_VARIANT = 0x0F;

    private EmvCommonSessionKeyDerivation() {
    }

    static byte[] derive(byte[] cardKey, byte[] diversification) {
        int blockSize = diversification.length;
        byte[] blocks = new byte[blockSize * 2];
        System.arraycopy(diversification, 0, blocks, 0, blockSize);
        System.arraycopy(diversification, 0, blocks, blockSize, blockSize);
        blocks[VARIANT_OFFSET] = LEFT_VARIANT;
        blocks[blockSize + VARIANT_OFFSET] = RIGHT_VARIANT;
        return EmvKeyDerivationCipher.encrypt(cardKey, blocks);
    }
}
