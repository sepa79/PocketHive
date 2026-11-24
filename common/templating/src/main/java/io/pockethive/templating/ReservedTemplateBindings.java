package io.pockethive.templating;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.error.ParserException;
import io.pebbletemplates.pebble.lexer.Token;
import io.pebbletemplates.pebble.node.BlockNode;
import io.pebbletemplates.pebble.node.ForNode;
import io.pebbletemplates.pebble.node.MacroNode;
import io.pebbletemplates.pebble.node.RenderableNode;
import io.pebbletemplates.pebble.node.SetNode;
import io.pebbletemplates.pebble.parser.Parser;
import io.pebbletemplates.pebble.tokenParser.FromTokenParser;
import io.pebbletemplates.pebble.tokenParser.ImportTokenParser;
import io.pebbletemplates.pebble.tokenParser.TokenParser;

/**
 * Responsibility: reject reserved context names in declarations produced by the canonical Pebble parsers.
 * Must not: parse grammar, scan source, evaluate templates or restrict ordinary context reads/data.
 * Contract: RESP-TEMPLATE-RENDER — docs/architecture/runtime-responsibilities.md#resp-template-render.
 */
final class ReservedTemplateBindings implements TokenParser {
    static final String VARIABLES = "vars";
    private final TokenParser delegate;

    private ReservedTemplateBindings(TokenParser delegate) { this.delegate = delegate; }

    static PebbleEngine install(PebbleEngine engine) {
        boolean installed = false;
        for (var entry : engine.getExtensionRegistry().getTokenParsers().entrySet()) {
            if (!(entry.getValue() instanceof ReservedTemplateBindings)) {
                entry.setValue(new ReservedTemplateBindings(entry.getValue()));
                installed = true;
            }
        }
        if (installed) engine.getTemplateCache().invalidateAll();
        return engine;
    }

    @Override public String getTag() { return delegate.getTag(); }

    @Override public RenderableNode parse(Token token, Parser parser) {
        // Only the canonical import parsers consume alias identifiers directly from the stream.
        Parser guarded = new ReservedBindingParser(parser,
            delegate instanceof ImportTokenParser || delegate instanceof FromTokenParser);
        RenderableNode node = delegate.parse(token, guarded);
        if (node instanceof SetNode set) requireAvailable(set.getName(), token, parser);
        else if (node instanceof ForNode loop) requireAvailable(loop.getIterationVariable(), token, parser);
        else if (node instanceof BlockNode block) requireAvailable(block.getName(), token, parser);
        else if (node instanceof MacroNode macro) {
            requireAvailable(macro.getName(), token, parser);
            if (macro.getArgs().getNamedArgs() != null) {
                macro.getArgs().getNamedArgs().forEach(argument -> requireAvailable(argument.getName(), token, parser));
            }
        }
        return node;
    }

    static void requireAvailable(String name, Token token, Parser parser) {
        requireAvailable(name, token.getLineNumber(), parser.getStream().getFilename());
    }

    static void requireAvailable(String name, int line, String filename) {
        if (VARIABLES.equals(name)) throw new ParserException(null,
            "The name '" + VARIABLES + "' is reserved for Scenario Variables and cannot be declared.", line, filename);
    }
}
