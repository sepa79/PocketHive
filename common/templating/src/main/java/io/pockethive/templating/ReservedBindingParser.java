package io.pockethive.templating;

import io.pebbletemplates.pebble.lexer.TokenStream;
import io.pebbletemplates.pebble.node.BodyNode;
import io.pebbletemplates.pebble.node.RootNode;
import io.pebbletemplates.pebble.parser.ExpressionParser;
import io.pebbletemplates.pebble.parser.Parser;
import io.pebbletemplates.pebble.parser.StoppingCondition;

/**
 * Responsibility: forward canonical tag parsing while checking declared block names and import identifiers.
 * Must not: implement grammar, replace expression parsing or advance an independent token stream.
 * Contract: RESP-TEMPLATE-RENDER — docs/architecture/runtime-responsibilities.md#resp-template-render.
 */
final class ReservedBindingParser implements Parser {
    private final Parser delegate;
    private final TokenStream stream;

    ReservedBindingParser(Parser delegate, boolean imports) {
        this.delegate = delegate;
        this.stream = imports ? new ReservedBindingTokenStream(delegate.getStream()) : delegate.getStream();
    }

    @Override public RootNode parse(TokenStream stream) { return delegate.parse(stream); }
    @Override public BodyNode subparse() { return delegate.subparse(); }
    @Override public BodyNode subparse(StoppingCondition condition) { return delegate.subparse(condition); }
    @Override public TokenStream getStream() { return stream; }
    @Override public ExpressionParser getExpressionParser() { return delegate.getExpressionParser(); }
    @Override public String peekBlockStack() { return delegate.peekBlockStack(); }
    @Override public String popBlockStack() { return delegate.popBlockStack(); }
    @Override public void pushBlockStack(String name) {
        ReservedTemplateBindings.requireAvailable(name, stream.current().getLineNumber(), stream.getFilename());
        delegate.pushBlockStack(name);
    }
}
