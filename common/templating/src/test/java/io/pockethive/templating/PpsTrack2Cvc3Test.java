package io.pockethive.templating;

import static org.assertj.core.api.Assertions.*;

import io.pockethive.templating.api.DisabledSequenceAccess;
import io.pockethive.templating.api.TemplateRenderingException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PpsTrack2Cvc3Test {
    // Published synthetic key only. Expected values generated with Thales SDK 2.9.1,
    // generateCVC3(..., un-as-decimal-int, atc-as-binary-int, false).
    private static final String KEY = "0123456789ABCDEFFEDCBA9876543210";
    private static final String TEMPLATE = """
        {{ eval("#pps_cvc3_track2_no_pin(vars.cvkHex, #json_path(payload, '/pan'), #json_path(payload, '/expiryDate'), #json_path(payload, '/serviceCode'), #json_path(payload, '/atcHex'), #json_path(payload, '/track2Un'))") }}""";
    private final PebbleTemplateRenderer renderer = new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE);

    @ParameterizedTest
    @CsvSource({
        "0000000000000001,0000,00,916", "0000000000000001,0001,00,144",
        "0000000000000001,0001,99,600", "0000000000000001,0010,04,388",
        "0000000000000001,270F,42,586", "0000000000000001,FFFF,99,141",
        "0000000000000001,0001,25,056",
        "1234567890123456,0000,00,471", "1234567890123456,0001,00,255",
        "1234567890123456,0001,99,499", "1234567890123456,0010,04,176",
        "1234567890123456,270F,42,404", "1234567890123456,FFFF,99,825"
    })
    void matchesReferenceSdkThroughActualRendering(String pan, String atc, String un, String expected) {
        assertThat(renderer.render(TEMPLATE, context(KEY, pan, "9912", "221", atc, un))).isEqualTo(expected);
    }

    @Test
    void validatesSyntaxWithNoKeysOrPayload() {
        assertThatCode(() -> renderer.validateSyntax(TEMPLATE)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"2702,221,235", "9912,201,667"})
    void includesExpiryAndServiceCodeInStaticTrackMac(String expiry, String service, String expected) {
        assertThat(renderer.render(TEMPLATE, context(KEY,"0000000000000001",expiry,service,"0001","42")))
            .isEqualTo(expected);
    }

    @Test
    void usesPerCallStateAndAcceptsLowercaseHex() {
        assertThat(CryptoSpelFunctions.ppsCvc3Track2NoPin(KEY.toLowerCase(java.util.Locale.ROOT),
            "0000000000000001", "9912", "221", "270f", "42")).isEqualTo("586");
        assertThat(renderer.render(TEMPLATE, context(KEY,"0000000000000001","9912","221","0001","99"))).isEqualTo("600");
        assertThat(renderer.render(TEMPLATE, context(KEY,"0000000000000001","9912","221","0010","04"))).isEqualTo("388");
        assertThat(renderer.render(TEMPLATE, context(KEY,"0000000000000001","9912","221","0001","99"))).isEqualTo("600");
    }

    @ParameterizedTest
    @CsvSource({"key,not-a-clear-key", "pan,123", "expiry,9913", "service,22X", "atc,xyz1", "un,100", "un,0X"})
    void rejectsInvalidInputsWithoutValuesInDiagnostic(String argument, String invalid) {
        var inputs = new java.util.HashMap<>(Map.of("key",KEY,"pan","0000000000000001",
            "expiry","9912","service","221","atc","0001","un","42"));
        inputs.put(argument, invalid);
        Throwable failure=catchThrowable(() -> renderer.render(TEMPLATE, context(inputs.get("key"),inputs.get("pan"),
            inputs.get("expiry"),inputs.get("service"),inputs.get("atc"),inputs.get("un"))));
        assertThat(failure).isInstanceOf(TemplateRenderingException.class);
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            assertThat(cause.getMessage()).doesNotContain(KEY,invalid);
        }
    }

    @Test
    void rejectsMissingKeyAndUnWithoutDefaulting() {
        assertThatThrownBy(() -> CryptoSpelFunctions.ppsCvc3Track2NoPin(null,"0000000000000001","9912","221","0001","42"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("cvkHex must encode 16 bytes");
        assertThatThrownBy(() -> CryptoSpelFunctions.ppsCvc3Track2NoPin(KEY,"0000000000000001","9912","221","0001",null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("unDecimal must contain two ASCII decimal digits");
    }

    private static Map<String,Object> context(String key,String pan,String expiry,String service,String atc,String un) {
        String payload="{\"pan\":\"%s\",\"expiryDate\":\"%s\",\"serviceCode\":\"%s\",\"atcHex\":\"%s\",\"track2Un\":\"%s\"}"
            .formatted(pan,expiry,service,atc,un);
        return Map.of("vars",Map.of("cvkHex",key),"payload",payload);
    }
}
