package io.pockethive.templating;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class Iso9797Algorithm3MacTest {
    // Independent PyEMV 1.5.0 ac.generate_ac(..., PaddingType.EMV, 8) results.
    @ParameterizedTest
    @CsvSource({
        "'',8F2AD7871698F5FE",
        "01,92CDF15C0323829F",
        "0123456789ABCDEF,6656D1F303DADEF7",
        "0123456789ABCDEF0123456789ABCDEF,FA624250B008B59A"
    })
    void computesMethod2MacForEmptyUnalignedAndAlignedData(String dataHex, String expected) {
        byte[] key = HexFormat.of().parseHex("29B33180E567CE38EA4CBC9D753B0E61");
        byte[] data = HexFormat.of().parseHex(dataHex);
        byte[] originalKey = key.clone();
        byte[] originalData = data.clone();
        assertThat(Iso9797Algorithm3Mac.computeMethod2(key, data)).isEqualTo(HexFormat.of().parseHex(expected));
        assertThat(key).isEqualTo(originalKey);
        assertThat(data).isEqualTo(originalData);
    }
}
