package io.pockethive.templating;

import org.bouncycastle.crypto.engines.DESEngine;
import org.bouncycastle.crypto.macs.ISO9797Alg3Mac;
import org.bouncycastle.crypto.paddings.ISO7816d4Padding;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * Responsibility: compute an eight-byte ISO 9797 Algorithm 3 MAC using method-2 padding on validated bytes.
 * Must not: derive keys, decode external hex or enforce an EMV data layout.
 * Contract: RESP-ISO9797-MAC — docs/architecture/runtime-responsibilities.md#resp-iso9797-mac.
 */
final class Iso9797Algorithm3Mac {
    private static final int MAC_BITS = 64;

    private Iso9797Algorithm3Mac() {
    }

    static byte[] computeMethod2(byte[] key, byte[] data) {
        ISO9797Alg3Mac mac = new ISO9797Alg3Mac(new DESEngine(), MAC_BITS, new ISO7816d4Padding());
        mac.init(new KeyParameter(key));
        mac.update(data, 0, data.length);
        byte[] result = new byte[mac.getMacSize()];
        mac.doFinal(result, 0);
        return result;
    }
}
