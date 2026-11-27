package io.pockethive.templating;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Responsibility: validate and map string arguments/results for the composable crypto SpEL functions.
 * Must not: implement cryptographic algorithms, assemble transactions or select profiles.
 * Contract: RESP-TEMPLATE-CRYPTO-ADAPTER — docs/architecture/runtime-responsibilities.md#resp-template-crypto-adapter;
 * docs/contracts/template-capabilities.md#composable-emv-and-mac-functions.
 */
final class CryptoSpelFunctions {
    static final String ICC_MK_A_NAME = "emv_icc_mk_a";
    static final String COMMON_SK_NAME = "emv_common_sk";
    static final String MAC_M2_NAME = "iso9797_alg3_mac_m2";
    static final String PPS_CVC3_TRACK2_NO_PIN_NAME = "pps_cvc3_track2_no_pin";
    private static final int KEY_BYTES = 16;
    private static final int DIVERSIFICATION_BYTES = 8;
    private static final HexFormat HEX = HexFormat.of().withUpperCase();
    private static final Pattern HEX_DIGITS = Pattern.compile("[0-9a-fA-F]*");
    private static final Pattern PAN_DIGITS = Pattern.compile("[0-9]{1,19}");
    private static final Pattern TWO_DIGITS = Pattern.compile("[0-9]{2}");
    private static final Pattern TRACK2_PAN_DIGITS = Pattern.compile("[0-9]{16}");
    private static final Pattern EXPIRY_DIGITS = Pattern.compile("[0-9]{2}(0[1-9]|1[0-2])");
    private static final Pattern SERVICE_DIGITS = Pattern.compile("[0-9]{3}");

    private CryptoSpelFunctions() {
    }

    static String emvIccMkA(String imkHex, String pan, String panSeqNo) {
        requireDigits(pan, PAN_DIGITS, "pan must contain 1–19 ASCII decimal digits");
        requireDigits(panSeqNo, TWO_DIGITS, "panSeqNo must contain two ASCII decimal digits");
        byte[] key = decodeFixedHex(imkHex, KEY_BYTES, "imkHex");
        try {
            return encodeAndClear(EmvIccMasterKeyDerivation.derive(key, pan, panSeqNo));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    static String emvCommonSk(String iccMkHex, String diversificationHex) {
        byte[] diversification = decodeFixedHex(diversificationHex, DIVERSIFICATION_BYTES, "diversificationHex");
        byte[] key = decodeFixedHex(iccMkHex, KEY_BYTES, "iccMkHex");
        try {
            return encodeAndClear(EmvCommonSessionKeyDerivation.derive(key, diversification));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    static String iso9797Alg3MacM2(String keyHex, String dataHex) {
        byte[] data = decodeHex(dataHex, "dataHex");
        byte[] key = decodeFixedHex(keyHex, KEY_BYTES, "keyHex");
        try {
            return encodeAndClear(Iso9797Algorithm3Mac.computeMethod2(key, data));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    static String ppsCvc3Track2NoPin(String cvkHex, String pan, String expiryYymm, String serviceCode,
                                  String atcHex, String unDecimal) {
        requireDigits(pan, TRACK2_PAN_DIGITS, "pan must contain 16 ASCII decimal digits");
        requireDigits(expiryYymm, EXPIRY_DIGITS, "expiryYymm must contain YYMM with month 01–12");
        requireDigits(serviceCode, SERVICE_DIGITS, "serviceCode must contain three ASCII decimal digits");
        requireDigits(unDecimal, TWO_DIGITS, "unDecimal must contain two ASCII decimal digits");
        byte[] atc = decodeFixedHex(atcHex, 2, "atcHex");
        byte[] unBcd = HEX.parseHex("000000" + unDecimal);
        byte[] key = decodeFixedHex(cvkHex, KEY_BYTES, "cvkHex");
        try {
            return String.format(Locale.ROOT, "%03d", PpsTrack2Cvc3.compute(key, pan, expiryYymm, serviceCode, atc, unBcd));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static String encodeAndClear(byte[] result) {
        try {
            return HEX.formatHex(result);
        } finally {
            Arrays.fill(result, (byte) 0);
        }
    }

    private static byte[] decodeFixedHex(String value, int bytes, String argument) {
        if (value == null || value.length() != bytes * 2) {
            throw new IllegalArgumentException(argument + " must encode " + bytes + " bytes");
        }
        return decodeHex(value, argument);
    }

    private static byte[] decodeHex(String value, String argument) {
        if (value == null || (value.length() & 1) != 0 || !HEX_DIGITS.matcher(value).matches()) {
            throw new IllegalArgumentException(argument + " must be unseparated, even-length hex");
        }
        return HEX.parseHex(value);
    }

    private static void requireDigits(String value, Pattern pattern, String message) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(message);
        }
    }
}
