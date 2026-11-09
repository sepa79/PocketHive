package io.pockethive.templating;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.pockethive.templating.api.DisabledSequenceAccess;

class TemplateSourceInspectorTest {
    @Test void distinguishesLiteralTextFromActualReadsIncludingEval() {
        assertThat(TemplateSourceInspector.inspect("vars.literal").variableNames()).isEmpty();
        assertThat(TemplateSourceInspector.inspect("{{ 'vars.literal' }}").variableNames()).isEmpty();
        assertThat(TemplateSourceInspector.inspect("{{ eval(\"'vars.literal'\") }}").variableNames()).isEmpty();
        assertThat(TemplateSourceInspector.inspect("{{ vars.customer.name }} {{ vars['other'] }} {{ eval('vars.count + 1') }}")
            .variableNames()).containsExactlyInAnyOrder("customer", "other", "count");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "{{ eval('') }}", "{{ eval('   ') }}", "{% if false %}{{ eval('') }}{% endif %}"
    })
    void blankEvalKeepsCanonicalValidationAndRendering(String source) {
        var renderer = new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE);
        renderer.validateSyntax(source);
        assertThat(renderer.render(source, Map.of())).isEmpty();
        var result = TemplateSourceInspector.inspect(source);
        assertThat(result.variableNames()).isEmpty();
        assertThat(result.deferredVariableReads()).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "vars.foo", "#root.vars.foo", "#this.vars.foo",
        "vars['foo']", "#root.vars['foo']", "#this.vars['foo']"
    })
    void staticSpelRootReadsMatchTheRenderer(String expression) {
        String source = "{{ eval(\"" + expression + "\") }}";
        var renderer = new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE);
        assertThat(renderer.render(source, Map.of("vars", Map.of("foo", "x")))).isEqualTo("x");
        assertThat(TemplateSourceInspector.inspect(source).variableNames()).containsExactly("foo");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "{{'vars': {'business': 'value'}}}.![#this.vars['business']][0]",
        "{{'vars': {'business': 'value'}}}.?[#this.vars['business'] == 'value'][0].vars['business']"
    })
    void localSpelElementsDoNotBecomeScenarioVariables(String expression) {
        String source = "{{ eval(\"" + expression + "\") }}";
        var renderer = new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE);
        assertThat(renderer.render(source, Map.of())).isEqualTo("value");
        assertThat(TemplateSourceInspector.inspect(source).variableNames()).isEmpty();
    }

    @Test void explicitSpelRootKeepsItsIdentityInsideAProjection() {
        String source = "{{ eval(\"{{'vars': {'business': 'value'}}}.![#root.vars.foo][0]\") }}";
        var renderer = new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE);
        assertThat(renderer.render(source, Map.of("vars", Map.of("foo", "x")))).isEqualTo("x");
        assertThat(TemplateSourceInspector.inspect(source).variableNames()).containsExactly("foo");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "{{'x': 'value'}}.![#this[vars.foo]][0]",
        "{{'x': 'value'}}.?[#this[vars.foo] == 'value'][0]['x']"
    })
    void spelIndexesRestoreTheRootInsideLocalElementContexts(String expression) {
        String source = "{{ eval(\"" + expression + "\") }}";
        var renderer = new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE);
        assertThat(renderer.render(source, Map.of("vars", Map.of("foo", "x")))).isEqualTo("value");
        assertThat(TemplateSourceInspector.inspect(source).variableNames()).containsExactly("foo");
    }

    @Test void visitsInactiveBranchesAndDoesNotEnumerateDynamicNames() {
        assertThat(TemplateSourceInspector.inspect("{% if false %}{{ vars.missing }}{% endif %}{{ vars[headers.key] }}")
            .variableNames()).containsExactly("missing");
    }

    @Test void compiledLiteralAndDynamicSelectorsUseTheActualGrammar() {
        assertThat(TemplateSourceInspector.inspect("create").literal()).isEqualTo("create");
        assertThat(TemplateSourceInspector.inspect("{{ pickWeightedSeeded('label', 'seed', 'create', 1) }}").literal()).isNull();
        assertThat(TemplateSourceInspector.inspect("{{ eval('1 + 1') }}").literal()).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "{% filter upper %}{{ vars.foo }}{% endfilter %}",
        "{% autoescape false %}{{ vars.foo }}{% endautoescape %}",
        "{% block sample %}{{ vars.foo }}{% endblock %}",
        "{{ vars.foo | default('x') }}", "{{ vars.foo is defined }}",
        "{{ [vars.foo] | join(',') }}", "{% set data = {'key': vars.foo} %}{{ data.key }}"
    })
    void acceptsRuntimeTemplateNodesAndRetainsTheirVariableReads(String source) {
        var renderer = new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE);
        assertThat(renderer.render(source, Map.of("vars", Map.of("foo", "x")))).isNotBlank();
        assertThat(TemplateSourceInspector.inspect(source).variableNames()).containsExactly("foo");
        assertThat(TemplateSourceInspector.inspect(source).literal()).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "{{ 'ok' | nonexistentFilter }}", "{{ nonexistentFunction() }}", "{{ 'ok' is nonexistentTest }}",
        "{{ eval('1', '2') }}", "{{ eval(wrong='1') }}"
    })
    void retainsCanonicalRegistryAndArgumentChecks(String source) {
        var renderer = new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> renderer.validateSyntax(source))
            .isInstanceOf(io.pockethive.templating.api.TemplateRenderingException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TemplateSourceInspector.inspect(source))
            .isInstanceOf(io.pockethive.templating.api.TemplateRenderingException.class);
    }

    @Test void methodCallsAreDeferredRatherThanGuessedToBeVariableNames() {
        var renderer = new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE);
        String source = "{{ vars.get('foo') }}";
        assertThat(renderer.render(source, Map.of("vars", Map.of("foo", "x")))).isEqualTo("x");
        var result = TemplateSourceInspector.inspect(source);
        assertThat(result.variableNames()).isEmpty();
        assertThat(result.deferredVariableReads()).isTrue();
    }

    @Test void traversesPublicBlockAndIncludeNameExpressions() {
        String source = "{% block x %}A{% endblock %}{{ block(vars.foo) }}";
        var renderer = new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE);
        assertThat(renderer.render(source, Map.of("vars", Map.of("foo", "x")))).isEqualTo("AA");
        assertThat(TemplateSourceInspector.inspect(source).variableNames()).containsExactly("foo");
        assertThat(TemplateSourceInspector.inspect("{% if false %}{% include vars.foo %}{% endif %}")
            .variableNames()).containsExactly("foo");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "{% if false %}{% cache 'key' %}{{ vars.foo }}{% endcache %}{% endif %}",
        "{% if false %}{% include 'literal' with {'value': vars.foo} %}{% endif %}"
    })
    void explicitlyDefersNodesWhoseChildrenTheCompilerDoesNotExpose(String source) {
        var renderer = new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE);
        renderer.validateSyntax(source);
        var result = TemplateSourceInspector.inspect(source);
        assertThat(result.variableNames()).isEmpty();
        assertThat(result.deferredVariableReads()).isTrue();
    }

    @Test void inspectionDoesNotChangeOptionalRuntimeRendering() {
        var renderer = new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE);
        String source = "{{ vars.optional | default('fallback') }}";
        assertThat(TemplateSourceInspector.inspect(source).variableNames()).containsExactly("optional");
        assertThat(renderer.render(source, Map.of("vars", Map.of()))).isEqualTo("fallback");
        assertThat(renderer.render("{{ vars.optional }}", Map.of("vars", Map.of()))).isEmpty();
    }
}
