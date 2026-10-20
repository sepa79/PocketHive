package io.pockethive.templating;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EmvCommonSessionKeyDerivationTest {
    // Independent PyEMV 1.5.0 kd.derive_common_sk results; R need not be ATC + zeros.
    @ParameterizedTest
    @CsvSource({
        "001C000000000000,E9FB384AF807B940FEDCEA613461B0C4",
        "1234567890123456,DF68FBD68F162C68B6DA2A528FBA6719",
        "0000000000000000,F86457DC2A7F52F4FD31CD1F25196251",
        "FFFFFFFFFFFFFFFF,0D6E4C01E0B338832C230D5BEAB054F8"
    })
    void derivesFromAllDiversificationBytesWithoutMutatingInputs(String rHex, String expected) {
        byte[] key = HexFormat.of().parseHex("0123456789ABCDEFFEDCBA9876543210");
        byte[] r = HexFormat.of().parseHex(rHex);
        byte[] originalKey = key.clone();
        byte[] originalR = r.clone();
        assertThat(EmvCommonSessionKeyDerivation.derive(key, r)).isEqualTo(HexFormat.of().parseHex(expected));
        assertThat(key).isEqualTo(originalKey);
        assertThat(r).isEqualTo(originalR);
    }
}
