package io.pockethive.templating;

import io.pockethive.templating.api.DisabledSequenceAccess;

import io.pockethive.templating.api.TemplateRenderingException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.extension.AbstractExtension;
import io.pebbletemplates.pebble.lexer.Token;
import io.pebbletemplates.pebble.node.RenderableNode;
import io.pebbletemplates.pebble.node.TextNode;
import io.pebbletemplates.pebble.parser.Parser;
import io.pebbletemplates.pebble.tokenParser.TokenParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PebbleTemplateRendererTest {
    private final PebbleTemplateRenderer renderer = new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE);

    @Test
    void acceptsRuntimePebbleAndEvalSyntaxWithoutRendering() {
        assertThatCode(() -> renderer.validateSyntax("""
            {% set pan = eval("#randLong('400000','499999')") %}
            {"pan":"{{ pan }}"}
            """)).doesNotThrowAnyException();
    }

    @Test
    void rejectsBrokenNestedQuotes() {
        assertThatThrownBy(() -> renderer.validateSyntax(
            "{{ eval(\"#base64_encode(\"\" ~ pan ~ \"\")\") }}"))
            .isInstanceOf(TemplateRenderingException.class);
    }

    @Test
    void rejectsInvalidEvalExpression() {
        assertThatThrownBy(() -> renderer.validateSyntax("{{ eval(\"#base64_encode(\") }}"))
            .isInstanceOf(TemplateRenderingException.class);
    }

    @Test
    void rejectsUnclosedPebbleExpression() {
        assertThatThrownBy(() -> renderer.validateSyntax("{{ payload "))
            .isInstanceOf(TemplateRenderingException.class);
    }


    @ParameterizedTest
    @ValueSource(strings = {
        "{% set vars = 'shadow' %}",
        "{% set vars = {'local':'x'} %}{{ vars['local'] }}",
        "{% for vars in ['shadow'] %}{{ vars }}{% endfor %}",
        "{% for vars in {'key': 'shadow'} %}{{ vars.value }}{% endfor %}",
        "{% macro show(vars) %}{{ vars }}{% endmacro %}",
        "{% macro show(vars = 'shadow') %}{{ vars }}{% endmacro %}",
        "{% macro vars(value) %}{{ value }}{% endmacro %}",
        "{% import 'macros' as vars %}",
        "{% from 'macros' import show as vars %}",
        "{% if false %}{% set vars = 'shadow' %}{% endif %}",
        "{% if false %}{% cache 'key' %}{% for vars in ['shadow'] %}x{% endfor %}{% endcache %}{% endif %}",
        "{% if false %}{% embed 'layout' %}{% block body %}{% set vars = 'shadow' %}{% endblock %}{% endembed %}{% endif %}",
        "{% block vars %}shadow{% endblock %}"
    })
    void varsCannotBeReboundInNativeTemplates(String source) {
        assertReserved(() -> renderer.validateSyntax(source));
        assertReserved(() -> TemplateSourceInspector.inspect(source));
        assertReserved(() -> renderer.render(source, Map.of("vars", Map.of("name", "profile"))));
    }

    @Test
    void namedScenarioVariableReadsRemainAvailable() {
        String source = "{{ vars.name }}|{{ vars['name'] }}";
        renderer.validateSyntax(source);
        assertThat(TemplateSourceInspector.inspect(source).variableNames()).containsExactly("name");
        assertThat(renderer.render(source, Map.of("vars", Map.of("name", "profile")))).isEqualTo("profile|profile");
    }

    @Test
    void ordinaryLocalBindingsRemainAvailable() {
        String source = "{% set local = 'value' %}{% for item in [local] %}{{ item }}{% endfor %}"
            + "{% macro show(value) %}{{ value }}{% endmacro %}{{ show('!') }}";
        renderer.validateSyntax(source);
        assertThat(TemplateSourceInspector.inspect(source).variableNames()).isEmpty();
        assertThat(renderer.render(source, Map.of())).isEqualTo("value!");
    }

    @Test
    void quotedVarsCommentsAndMapKeysRemainOrdinaryText() {
        String source = "{# {% set vars = 'shadow' %} #}{{ 'vars' }}"
            + "{% set values = {'vars': 'map'} %}{{ values.vars }}";
        renderer.validateSyntax(source);
        assertThat(TemplateSourceInspector.inspect(source).variableNames()).isEmpty();
        assertThat(renderer.render(source, Map.of())).isEqualTo("varsmap");
    }

    @Test
    void customEngineCannotReuseAnAlreadyCompiledReservedBinding() {
        String source = "{% set vars = 'shadow' %}{{ vars }}";
        PebbleEngine engine = new PebbleEngine.Builder().autoEscaping(false).build();
        engine.getLiteralTemplate(source);
        PebbleTemplateRenderer customRenderer = new PebbleTemplateRenderer(engine);
        assertReserved(() -> customRenderer.render(source, Map.of("vars", Map.of("name", "profile"))));
    }

    @Test
    void customTagsMayReadTheVarsNameWithoutBindingIt() {
        PebbleEngine engine = new PebbleEngine.Builder().extension(new AbstractExtension() {
            @Override
            public List<TokenParser> getTokenParsers() {
                return List.of(new TokenParser() {
                    @Override public String getTag() { return "readname"; }
                    @Override public RenderableNode parse(Token token, Parser parser) {
                        var stream = parser.getStream();
                        stream.next();
                        String name = stream.expect(Token.Type.NAME).getValue();
                        stream.expect(Token.Type.EXECUTE_END);
                        return new TextNode(name, token.getLineNumber());
                    }
                });
            }
        }).autoEscaping(false).build();
        assertThat(new PebbleTemplateRenderer(engine).render("{% readname vars %}", Map.of())).isEqualTo("vars");
    }

    private void assertReserved(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOf(TemplateRenderingException.class)
            .hasStackTraceContaining("vars").hasStackTraceContaining("reserved");
    }
}
