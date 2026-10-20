package io.pockethive.templating;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class EmvCryptoCompositionTest {
    // Synthetic key and fixed answers from PyEMV 1.5.0 (not calculated by Java under test):
    // mk = kd.derive_icc_mk_a(bytes.fromhex(IMK), pan, psn)
    // sk = kd.derive_common_sk(mk, bytes.fromhex(atc + "000000000000"))
    // ac.generate_ac(sk, bytes.fromhex(data), ac.PaddingType.EMV, 8).hex().upper()
    private static final String IMK = "0123456789ABCDEFFEDCBA9876543210";
    private static final String PREFIX = "0000000099990000000000000840800004800008409912310152BF458518000FFF";
    private static final String IAD = "06011203A0B800";

    @ParameterizedTest(name = "{0}")
    @MethodSource("knownAnswers")
    void matchesIndependentEmvAnswers(String name, String pan, String psn, String atc,
                                     String data, String expected) {
        String cardKey = CryptoSpelFunctions.emvIccMkA(IMK, pan, psn);
        String sessionKey = CryptoSpelFunctions.emvCommonSk(cardKey, atc + "000000000000");
        assertThat(CryptoSpelFunctions.iso9797Alg3MacM2(sessionKey, data)).isEqualTo(expected);
    }

    static Stream<Arguments> knownAnswers() {
        return Stream.of(
            Arguments.of("aligned (full padding block)", "1234567890123456", "00", "0FFF",
                PREFIX + IAD, "57E5F123CB66DF65"),
            Arguments.of("unaligned", "1234567890123456", "00", "0FFF",
                PREFIX + IAD + "00", "C215DCBBB01E745A"),
            Arguments.of("long PAN still uses Option A", "99012345678901234", "45", "0FFF",
                PREFIX + IAD, "45E23BAAC9F139D0"),
            Arguments.of("short PAN preserves leading zeros", "001234567890", "01", "0FFF",
                PREFIX + IAD, "C14E20EF0030E800"),
            Arguments.of("changed ATC", "1234567890123456", "00", "1000",
                PREFIX.substring(0, 62) + "1000" + IAD, "7423A76E711FE295"),
            Arguments.of("changed UN", "1234567890123456", "00", "0FFF",
                PREFIX.replace("52BF4585", "52BF4586") + IAD, "D39E47992CB4A492"),
            Arguments.of("zero-length IAD", "1234567890123456", "00", "0FFF",
                PREFIX, "FC620B878D2EC040"),
            Arguments.of("maximum IAD", "1234567890123456", "00", "0FFF",
                PREFIX + "AA".repeat(32), "1229E3F4E4EEF03C")
        );
    }

}
