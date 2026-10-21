package io.pockethive.templating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class CryptoSpelFunctionsTest {
    private static final String KEY = "0123456789ABCDEFFEDCBA9876543210";

    @Test
    void acceptsLowercaseAndReturnsUppercaseFromEachFunction() {
        assertThat(CryptoSpelFunctions.emvIccMkA(KEY.toLowerCase(java.util.Locale.ROOT), "99012345678901234", "45"))
            .isEqualTo("67F8292358083E5EA7AB7FDA58D53B6B");
        assertThat(CryptoSpelFunctions.emvCommonSk(KEY, "001c000000000000"))
            .isEqualTo("E9FB384AF807B940FEDCEA613461B0C4");
        assertThat(CryptoSpelFunctions.iso9797Alg3MacM2("29b33180e567ce38ea4cbc9d753b0e61", "0123456789abcdef"))
            .isEqualTo("6656D1F303DADEF7");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "0123456789ABCDEF", "0123456789ABCDEFFEDCBA987654321000", "GGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGG", " 123456789ABCDEFFEDCBA9876543210", "0123456789ABCDEFFEDCBA98765432100123456789ABCDEF"})
    void allFunctionsRejectInvalidDoubleLengthKeys(String key) {
        assertThatThrownBy(() -> CryptoSpelFunctions.emvIccMkA(key, "1234567890123456", "00"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("imkHex");
        assertThatThrownBy(() -> CryptoSpelFunctions.emvCommonSk(key, "001C000000000000"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("iccMkHex");
        assertThatThrownBy(() -> CryptoSpelFunctions.iso9797Alg3MacM2(key, ""))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("keyHex");
    }

    @ParameterizedTest
    @MethodSource("invalidCardIdentity")
    void rejectsInvalidPanAndSequenceWithoutDefaults(String pan, String psn, String label) {
        assertThatThrownBy(() -> CryptoSpelFunctions.emvIccMkA(KEY, pan, psn))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(label).hasMessageNotContaining(KEY);
    }

    static Stream<Arguments> invalidCardIdentity() {
        return Stream.of(
            Arguments.of(null, "00", "pan"), Arguments.of("", "00", "pan"),
            Arguments.of("12345678901234567890", "00", "pan"), Arguments.of("123F", "00", "pan"),
            Arguments.of("１２３４", "00", "pan"), Arguments.of("1234", null, "panSeqNo"),
            Arguments.of("1234", "", "panSeqNo"), Arguments.of("1234", "1", "panSeqNo"),
            Arguments.of("1234", "001", "panSeqNo"), Arguments.of("1234", "0F", "panSeqNo"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "0000", "001C00000000000", "001C00000000000000", "001C00000000000G", " 01C000000000000"})
    void requiresExactlyEightDiversificationBytes(String r) {
        assertThatThrownBy(() -> CryptoSpelFunctions.emvCommonSk(KEY, r))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("diversificationHex");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "0x01", "00 01", "GG", "００"})
    void rejectsMalformedMacData(String data) {
        assertThatThrownBy(() -> CryptoSpelFunctions.iso9797Alg3MacM2(KEY, data))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dataHex");
    }
}
