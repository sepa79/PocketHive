package io.pockethive.templating;

import java.util.Arrays;
import java.util.HexFormat;
import org.bouncycastle.crypto.engines.DESedeEngine;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * Responsibility: calculate the explicit PPS no-PIN Track2 CVC3 profile from validated inputs.
 * Must not: unwrap/select keys, allocate counters, generate randomness or assemble outgoing requests.
 * Contract: RESP-PPS-TRACK2-CVC3 — docs/architecture/runtime-responsibilities.md#resp-pps-track2-cvc3;
 * docs/contracts/template-capabilities.md#pps-track2-cvc3-without-pin.
 */
final class PpsTrack2Cvc3 {
    private static final int STATIC_TRACK_NIBBLES = 37;
    private static final int CVC3_MODULUS = 1000;

    private PpsTrack2Cvc3() {
    }

    static int compute(byte[] cvk, String pan, String expiry, String serviceCode, byte[] atc, byte[] unBcd) {
        byte[] cardKey = EmvIccMasterKeyDerivation.derive(cvk, pan, "00");
        byte[] mac = null;
        byte[] encrypted = new byte[8];
        byte[] dynamicBlock = new byte[8];
        try {
            String staticTrack = pan + "D" + expiry + serviceCode;
            byte[] trackBytes = HexFormat.of().parseHex(staticTrack
                + "0".repeat(STATIC_TRACK_NIBBLES - staticTrack.length()) + "F");
            mac = Iso9797Algorithm3Mac.computeMethod2(cardKey, trackBytes);
            System.arraycopy(mac, mac.length - 2, dynamicBlock, 0, 2);
            System.arraycopy(unBcd, 0, dynamicBlock, 2, 4);
            System.arraycopy(atc, 0, dynamicBlock, 6, 2);
            DESedeEngine cipher = new DESedeEngine();
            cipher.init(true, new KeyParameter(cardKey));
            cipher.processBlock(dynamicBlock, 0, encrypted, 0);
            int unsignedTail = ((encrypted[6] & 0xff) << 8) | (encrypted[7] & 0xff);
            return unsignedTail % CVC3_MODULUS;
        } finally {
            Arrays.fill(cardKey, (byte) 0);
            if (mac != null) {
                Arrays.fill(mac, (byte) 0);
            }
            Arrays.fill(encrypted, (byte) 0);
            Arrays.fill(dynamicBlock, (byte) 0);
        }
    }
}
