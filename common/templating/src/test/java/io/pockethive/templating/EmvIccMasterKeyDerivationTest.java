package io.pockethive.templating;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EmvIccMasterKeyDerivationTest {
    // Independent PyEMV 1.5.0 kd.derive_icc_mk_a results using this synthetic issuer key.
    @ParameterizedTest
    @CsvSource({
        "1234567890123456,00,154F349D8585CB7F6B0798E9839B10C1",
        "99012345678901234,45,67F8292358083E5EA7AB7FDA58D53B6B",
        "001234567890,01,2C19BF850DF775FBA19B547AD3260408"
    })
    void derivesOptionAIncludingLeadingZerosAndLongPan(String pan, String psn, String expected) {
        byte[] key = HexFormat.of().parseHex("0123456789ABCDEFFEDCBA9876543210");
        byte[] original = key.clone();
        assertThat(EmvIccMasterKeyDerivation.derive(key, pan, psn))
            .isEqualTo(HexFormat.of().parseHex(expected));
        assertThat(key).isEqualTo(original);
    }
}
