package io.pockethive.worker.sdk.templating;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.worker.sdk.api.WorkItem;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PebbleTemplateRendererTest {

    private final TemplateRenderer renderer = new PebbleTemplateRenderer();

    @Test
    void rendersStaticTemplate() {
        String result = renderer.render("hello", Map.of());
        assertThat(result).isEqualTo("hello");
    }

    @Test
    void rendersTemplateWithVariables() {
        String template = "Hello {{ name }}, count={{ count }}";
        String result = renderer.render(template, Map.of("name", "PocketHive", "count", 3));
        assertThat(result).isEqualTo("Hello PocketHive, count=3");
    }

    @Test
    void evaluatesSpelWithHelpers() {
        String payload = "{\"foo\":\"bar\",\"num\":123}";
        WorkItem item = WorkItem.text(payload).build();
        String template = String.join("|",
            "{{ eval(\"payload + '!'\") }}",
            "{{ eval(\"#base64_encode(payload)\") }}",
            "{{ eval(\"#base64_decode(#base64_encode(payload))\") }}",
            "{{ eval(\"#regex_match(payload,'foo')\") }}",
            "{{ eval(\"#regex_extract(payload,'bar',0)\") }}",
            "{{ eval(\"#json_path(payload,'/foo')\") }}",
            "{{ eval(\"#md5_hex(payload)\") }}",
            "{{ eval(\"#sha256_hex(payload)\") }}",
            "{{ eval(\"#hmac_sha256_hex('k',payload)\") }}",
            "{{ eval(\"#date_format(now,'yyyy')\") }}",
            "{{ eval(\"#uuid()\") }}",
            "{{ eval(\"#randLong('4000000000000000','4000000000000000')\") }}",
            "{{ eval(\"#randInt(7,7)\") }}"
        );

        String result = renderer.render(template, Map.of(
            "workItem", item,
            "payload", item.payload(),
            "headers", item.headers()
        ));

        String[] parts = result.trim().split("\\|");
        assertThat(parts).hasSize(13);
        assertThat(parts[0]).isEqualTo(payload + "!");
        assertThat(parts[1]).isEqualTo("eyJmb28iOiJiYXIiLCJudW0iOjEyM30=");
        assertThat(parts[2]).isEqualTo(payload);
        assertThat(parts[3]).isEqualTo("true");
        assertThat(parts[4]).isEqualTo("bar");
        assertThat(parts[5]).isEqualTo("bar");
        assertThat(parts[6]).isEqualTo("bd70b353e8d59e2c27a04810fbe841fb");
        assertThat(parts[7]).isEqualTo("249a1ac83c8e094a7961e3693c1bbe475952c83362d1a4f0019d1f727c14788c");
        assertThat(parts[8]).isEqualTo("24be938f28e68f76c942e94fe98c364a2f3118a0ea9aa8828ce90c6e243b6068");
        assertThat(parts[9]).hasSize(4).containsOnlyDigits();
        assertThat(parts[10]).hasSize(36);
        assertThat(parts[11]).isEqualTo("4000000000000000");
        assertThat(parts[12]).isEqualTo("7");
    }

    @Test
    void pickWeightedIgnoresZeroWeights() {
        String template = "{{ pickWeighted('a', 0, 'b', 5) }}";
        for (int i = 0; i < 20; i++) {
            assertThat(renderer.render(template, Map.of())).isEqualTo("b");
        }
    }

    @Test
    void pickWeightedSeededRepeatsSequenceAcrossRestarts() {
        String template = "{{ pickWeightedSeeded('callId', 'seed-001', 'a', 50, 'b', 30, 'c', 20) }}";
        java.util.List<String> seq1 = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            seq1.add(renderer.render(template, Map.of()));
        }
        TemplateRenderer second = new PebbleTemplateRenderer();
        java.util.List<String> seq2 = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            seq2.add(second.render(template, Map.of()));
        }
        assertThat(seq2).isEqualTo(seq1);
    }

    @Test
    void resetSeededSelectionsRestartsSequence() {
        String template = "{{ pickWeightedSeeded('callId', 'seed-xyz', 'a', 50, 'b', 30, 'c', 20) }}";
        java.util.List<String> seq1 = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            seq1.add(renderer.render(template, Map.of()));
        }
        renderer.resetSeededSelections();
        java.util.List<String> seq2 = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            seq2.add(renderer.render(template, Map.of()));
        }
        assertThat(seq2).isEqualTo(seq1);
    }

    @Test
    void evictsOldEntriesBeyondCacheSize() throws Exception {
        PebbleTemplateRenderer renderer = new PebbleTemplateRenderer();
        for (int i = 0; i < 11; i++) {
            renderer.render("template-" + i, Map.of());
        }

        Map<String, ?> cache = readTemplateCache(renderer);
        assertThat(cache).hasSize(10);
        assertThat(cache).doesNotContainKey("template-0");
        assertThat(cache).containsKey("template-10");

        renderer.render("template-0", Map.of());
        Map<String, ?> refreshed = readTemplateCache(renderer);
        assertThat(refreshed).hasSize(10);
        assertThat(refreshed).containsKey("template-0");
        assertThat(refreshed).doesNotContainKey("template-1");
    }

    @SuppressWarnings("unchecked")
    private Map<String, ?> readTemplateCache(PebbleTemplateRenderer renderer) throws Exception {
        Field field = PebbleTemplateRenderer.class.getDeclaredField("templateCache");
        field.setAccessible(true);
        return (Map<String, ?>) field.get(renderer);
    }
}
