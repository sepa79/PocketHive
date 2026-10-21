package io.pockethive.templating;

import org.bouncycastle.crypto.engines.DESedeEngine;
import org.bouncycastle.crypto.params.DESParameters;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * Responsibility: encrypt two validated diversification blocks and set odd DES key parity.
 * Must not: construct diversification data, select profiles or retain cipher state.
 * Contract: RESP-EMV-KEY-CIPHER — docs/architecture/runtime-responsibilities.md#resp-emv-key-cipher.
 */
final class EmvKeyDerivationCipher {
    private EmvKeyDerivationCipher() {
    }

    static byte[] encrypt(byte[] key, byte[] blocks) {
        DESedeEngine cipher = new DESedeEngine();
        cipher.init(true, new KeyParameter(key));
        int blockSize = cipher.getBlockSize();
        byte[] result = new byte[blockSize * 2];
        cipher.processBlock(blocks, 0, result, 0);
        cipher.processBlock(blocks, blockSize, result, blockSize);
        DESParameters.setOddParity(result);
        return result;
    }
}
