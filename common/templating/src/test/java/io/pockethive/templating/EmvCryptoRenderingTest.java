package io.pockethive.templating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.pockethive.templating.api.DisabledSequenceAccess;
import io.pockethive.templating.api.TemplateRenderingException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmvCryptoRenderingTest {
    private static final String IMK = "0123456789ABCDEFFEDCBA9876543210";
    private static final String DATA = "0000000099990000000000000840800004800008409912310152BF458518000FFF06011203A0B800";
    private static final String TEMPLATE = """
        {{ eval("'9F2608' + #iso9797_alg3_mac_m2(#emv_common_sk(#emv_icc_mk_a(vars.imkAcHex, #json_path(payload, '/pan'), #json_path(payload, '/panSeqNo')), #json_path(payload, '/emv/atc') + '000000000000'), #json_path(payload, '/emv/arqcDataHex'))") }}""";
    private final PebbleTemplateRenderer renderer = new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE);

    @Test
    void rendersTag9f26ThroughThePublicRendererAndRecomputesForEachContext() {
        assertThat(renderer.render(TEMPLATE, context(IMK, "52BF4585", DATA)))
            .isEqualTo("9F260857E5F123CB66DF65");
        assertThat(renderer.render(TEMPLATE, context(IMK, "52BF4586", DATA.replace("52BF4585", "52BF4586"))))
            .isEqualTo("9F2608D39E47992CB4A492");
        assertThat(renderer.render(TEMPLATE, context(IMK, "52BF4585", DATA)))
            .isEqualTo("9F260857E5F123CB66DF65");
    }

    @Test
    void validatesSyntaxWithoutKeysOrPayload() {
        assertThatCode(() -> renderer.validateSyntax(TEMPLATE)).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnregisteredTransactionShortcut() {
        assertThatThrownBy(() -> renderer.render("{{ eval(\"#emv_arqc('','','','','','')\") }}", Map.of()))
            .isInstanceOf(TemplateRenderingException.class)
            .hasRootCauseMessage("EL1006E: Function 'emv_arqc' could not be found");
    }

    @Test
    void rendersStandaloneGenericOperationsWithoutTransactionFields() {
        assertThat(renderer.render("{{ eval(\"#emv_icc_mk_a(vars.key, '99012345678901234', '45')\") }}",
            Map.of("vars", Map.of("key", IMK)))).isEqualTo("67F8292358083E5EA7AB7FDA58D53B6B");
        assertThat(renderer.render("{{ eval(\"#emv_common_sk(vars.key, '1234567890123456')\") }}",
            Map.of("vars", Map.of("key", IMK)))).isEqualTo("DF68FBD68F162C68B6DA2A528FBA6719");
        assertThat(renderer.render("{{ eval(\"#iso9797_alg3_mac_m2(vars.key, '')\") }}",
            Map.of("vars", Map.of("key", "29B33180E567CE38EA4CBC9D753B0E61"))))
            .isEqualTo("8F2AD7871698F5FE");
    }

    @Test
    void rejectsMalformedDataThroughTheRenderingContract() {
        assertThatThrownBy(() -> renderer.render(TEMPLATE, context(IMK, "52BF4585", "not-hex")))
            .isInstanceOf(TemplateRenderingException.class)
            .hasRootCauseMessage("dataHex must be unseparated, even-length hex");
    }

    @Test
    void doesNotExposeContextKeyOrPanInFailureChain() {
        String invalidKey = IMK + "FF";
        Throwable failure = catchThrowable(() -> renderer.render(TEMPLATE, context(invalidKey, "52BF4585", DATA)));
        assertThat(failure).isInstanceOf(TemplateRenderingException.class);
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            assertThat(cause.getMessage()).doesNotContain(invalidKey, "1234567890123456", DATA);
        }
    }

    private static Map<String, Object> context(String key, String un, String data) {
        String payload = """
            {"pan":"1234567890123456","panSeqNo":"00",
             "emv":{"atc":"0FFF","un":"%s","arqcDataHex":"%s"}}
            """.formatted(un, data);
        return Map.of("vars", Map.of("imkAcHex", key), "payload", payload);
    }
}
