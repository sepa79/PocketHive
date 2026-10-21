package io.pockethive.templating;

import io.pebbletemplates.pebble.extension.AbstractNodeVisitor;
import io.pebbletemplates.pebble.extension.Function;
import io.pebbletemplates.pebble.node.*;
import io.pebbletemplates.pebble.node.expression.*;
import io.pebbletemplates.pebble.template.PebbleTemplateImpl;
import io.pockethive.templatedata.TemplateDataError;
import io.pockethive.templatedata.TemplateDataException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Responsibility: admit the portable expression subset on Pebble's compiled AST.
 * Must not: tokenize source, parse language syntax, evaluate expressions or perform IO.
 * Contract: RESP-TEMPLATE-RENDER — docs/architecture/runtime-responsibilities.md#resp-template-render.
 */
final class PortableSyntaxVisitor extends AbstractNodeVisitor {
    private final Map<String, Function> functions;
    PortableSyntaxVisitor(PebbleTemplateImpl template, Map<String, Function> functions) { super(template); this.functions = functions; }
    @Override public void visit(Node node) {
        if (node instanceof FunctionOrMacroInvocationExpression invocation) {
            Function function = functions.get(invocation.getFunctionName());
            if (function == null) throw unsupported();
            ArgumentsNode arguments = invocation.getArguments();
            Set<String> supplied = new HashSet<>();
            int positional = arguments.getPositionalArgs() == null ? 0 : arguments.getPositionalArgs().size();
            if (positional > function.getArgumentNames().size()) throw unsupported();
            supplied.addAll(function.getArgumentNames().subList(0, positional));
            if (arguments.getNamedArgs() != null) for (NamedArgumentNode argument : arguments.getNamedArgs()) {
                if (!function.getArgumentNames().contains(argument.getName()) || !supplied.add(argument.getName())) throw unsupported();
            }
            if (supplied.size() != function.getArgumentNames().size()) throw unsupported();
            arguments.accept(this);
        } else if (node instanceof GetAttributeExpression access) {
            if (access.getArgumentsNode() != null) throw unsupported();
            access.getNode().accept(this);
            access.getAttributeNameExpression().accept(this);
        } else if (node instanceof ContextVariableExpression variable) {
            if (!Set.of("record", "params", "endpoint").contains(variable.getName())) throw unsupported();
        } else if (node instanceof MapExpression map) {
            map.getEntries().forEach((key, value) -> { key.accept(this); value.accept(this); });
        } else if (node instanceof ArrayExpression array) {
            array.getValues().forEach(value -> value.accept(this));
        } else if (!(node instanceof LiteralStringExpression || node instanceof LiteralIntegerExpression
            || node instanceof LiteralLongExpression || node instanceof LiteralDoubleExpression || node instanceof LiteralBigDecimalExpression
            || node instanceof LiteralBooleanExpression || node instanceof LiteralNullExpression)) {
            throw unsupported();
        }
    }
    @Override public void visit(AutoEscapeNode node) { throw unsupported(); }
    @Override public void visit(BlockNode node) { throw unsupported(); }
    @Override public void visit(ExtendsNode node) { throw unsupported(); }
    @Override public void visit(FlushNode node) { throw unsupported(); }
    @Override public void visit(ForNode node) { throw unsupported(); }
    @Override public void visit(IfNode node) { throw unsupported(); }
    @Override public void visit(ImportNode node) { throw unsupported(); }
    @Override public void visit(IncludeNode node) { throw unsupported(); }
    @Override public void visit(MacroNode node) { throw unsupported(); }
    @Override public void visit(ParallelNode node) { throw unsupported(); }
    @Override public void visit(SetNode node) { throw unsupported(); }
    private static TemplateDataException unsupported() {
        return new TemplateDataException(TemplateDataError.INVALID_VALUE, "Unsupported portable expression, binding or function arguments.");
    }
}
