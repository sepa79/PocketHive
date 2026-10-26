package io.pockethive.templating;

import io.pebbletemplates.pebble.lexer.Token;
import io.pebbletemplates.pebble.lexer.TokenStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Responsibility: observe identifier declarations consumed by Pebble's existing import parsers.
 * Must not: tokenize source, inspect token lists, interpret grammar or move a separate stream.
 * Contract: RESP-TEMPLATE-RENDER — docs/architecture/runtime-responsibilities.md#resp-template-render.
 */
final class ReservedBindingTokenStream extends TokenStream {
    private final TokenStream delegate;

    ReservedBindingTokenStream(TokenStream delegate) {
        super(List.of(), delegate.getFilename());
        this.delegate = delegate;
    }

    @Override public Token expect(Token.Type type) { return checked(delegate.expect(type)); }
    @Override public Token expect(Token.Type type, String value) { return checked(delegate.expect(type, value)); }

    private Token checked(Token token) {
        if (token.getType() == Token.Type.NAME)
            ReservedTemplateBindings.requireAvailable(token.getValue(), token.getLineNumber(), delegate.getFilename());
        return token;
    }

    @Override public Token next() { return delegate.next(); }
    @Override public Token peek() { return delegate.peek(); }
    @Override public Token peek(int offset) { return delegate.peek(offset); }
    @Override public Token current() { return delegate.current(); }
    @Override public boolean isEOF() { return delegate.isEOF(); }
    @Override public String getFilename() { return delegate.getFilename(); }
    @Override public ArrayList<Token> getTokens() { return delegate.getTokens(); }
    @Override public String toString() { return delegate.toString(); }
}
