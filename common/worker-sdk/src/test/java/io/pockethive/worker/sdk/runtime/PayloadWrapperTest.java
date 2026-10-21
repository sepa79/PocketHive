package io.pockethive.worker.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PayloadWrapperTest {
    @Test
    void retainsAllColumnAliasesAndArbitraryObjectProperties() {
        PayloadWrapper payload = new PayloadWrapper(Map.of(
            "col0", "000000", "col1", "000001", "col2", "000002", "col3", "000003", "col4", "000004",
            "col5", "000005", "col6", "000006", "col7", "000007", "col8", "000008", "col9", "000009"));

        assertThat(payload.getCol0()).isEqualTo("000000");
        assertThat(payload.getCol1()).isEqualTo("000001");
        assertThat(payload.getCol2()).isEqualTo("000002");
        assertThat(payload.getCol3()).isEqualTo("000003");
        assertThat(payload.getCol4()).isEqualTo("000004");
        assertThat(payload.getCol5()).isEqualTo("000005");
        assertThat(payload.getCol6()).isEqualTo("000006");
        assertThat(payload.getCol7()).isEqualTo("000007");
        assertThat(payload.getCol8()).isEqualTo("000008");
        assertThat(payload.getCol9()).isEqualTo("000009");
        assertThat(new PayloadWrapper(Map.of("customerId", "000123"))).containsEntry("customerId", "000123");
        assertThat(new PayloadWrapper(Map.of()).getCol0()).isNull();
    }
}
