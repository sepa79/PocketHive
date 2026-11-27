package io.pockethive.templating;

import io.pebbletemplates.pebble.extension.AbstractNodeVisitor;
import io.pebbletemplates.pebble.node.*;
import io.pebbletemplates.pebble.node.expression.*;
import io.pebbletemplates.pebble.template.PebbleTemplateImpl;
import java.util.Set;

/**
 * Responsibility: traverse Pebble's parsed nodes for static variable-name reads.
 * Must not: parse source syntax or treat string literals/function arguments as variable reads.
 * Contract: RESP-TEMPLATE-RENDER — docs/architecture/runtime-responsibilities.md#resp-template-render.
 */
final class TemplateVariableVisitor extends AbstractNodeVisitor {
    private final Set<String> variables;
    private final StringBuilder text;
    private final Runnable dynamic;
    private final Runnable deferredRead;

    TemplateVariableVisitor(PebbleTemplateImpl template, Set<String> variables,
                            StringBuilder text, Runnable dynamic, Runnable deferredRead) {
        super(template);
        this.variables = variables;
        this.text = text;
        this.dynamic = dynamic;
        this.deferredRead = deferredRead;
    }

    @Override public void visit(TextNode node) { text.append(node.getData()); }
    @Override public void visit(PrintNode node) { dynamic.run(); super.visit(node); }
    @Override public void visit(SetNode node) { dynamic.run(); super.visit(node); }
    @Override public void visit(IfNode node) { dynamic.run(); super.visit(node); }
    @Override public void visit(ForNode node) { dynamic.run(); super.visit(node); }
    @Override public void visit(IncludeNode node) {
        dynamic.run();
        // Pebble exposes the include expression, but not its optional with-map.
        deferredRead.run();
        super.visit(node);
    }
    @Override public void visit(ExtendsNode node) { dynamic.run(); super.visit(node); }
    @Override public void visit(BlockNode node) { dynamic.run(); super.visit(node); }
    @Override public void visit(MacroNode node) { dynamic.run(); super.visit(node); }
    @Override public void visit(ImportNode node) { dynamic.run(); super.visit(node); }
    @Override public void visit(FlushNode node) { dynamic.run(); super.visit(node); }

    @Override public void visit(Node node) {
        dynamic.run();
        if (node instanceof GetAttributeExpression access) {
            if (access.getNode() instanceof ContextVariableExpression root && "vars".equals(root.getName())) {
                if (access.getArgumentsNode() == null && access.getAttributeNameExpression() instanceof LiteralStringExpression name)
                    variables.add(name.getValue());
                else deferredRead.run();
            }
            access.getNode().accept(this);
            access.getAttributeNameExpression().accept(this);
            if (access.getArgumentsNode() != null) access.getArgumentsNode().accept(this);
        } else if (node instanceof FunctionOrMacroInvocationExpression invocation) {
            ArgumentsNode args = invocation.getArguments();
            if ("eval".equals(invocation.getFunctionName())) {
                if (args.getPositionalArgs() != null && !args.getPositionalArgs().isEmpty()) {
                    var value = args.getPositionalArgs().getFirst().getValueExpression();
                    if (value instanceof LiteralStringExpression expression) {
                        variables.addAll(SpelTemplateEvaluator.variableNames(expression.getValue()));
                    }
                }
                if (args.getNamedArgs() != null) args.getNamedArgs().stream()
                    .filter(arg -> "expression".equals(arg.getName()))
                    .filter(arg -> arg.getValueExpression() instanceof LiteralStringExpression)
                    .forEach(arg -> variables.addAll(SpelTemplateEvaluator.variableNames(
                        ((LiteralStringExpression) arg.getValueExpression()).getValue())));
            }
            args.accept(this);
        } else if (node instanceof BlockFunctionExpression block) {
            block.getBlockNameExpression().accept(this);
        } else if (node instanceof CacheNode || node instanceof EmbedNode || node instanceof FromNode) {
            // These Pebble nodes expose no public child accessors. Do not guess private/source contents.
            deferredRead.run();
        } else if (node instanceof BinaryExpression<?> binary) {
            binary.getLeftExpression().accept(this);
            binary.getRightExpression().accept(this);
        } else if (node instanceof RenderableNodeExpression renderable) {
            renderable.getNode().accept(this);
        } else if (node instanceof UnaryExpression unary) {
            unary.getChildExpression().accept(this);
        } else if (node instanceof TernaryExpression ternary) {
            ternary.getExpression1().accept(this);
            ternary.getExpression2().accept(this);
            if (ternary.getExpression3() != null) ternary.getExpression3().accept(this);
        } else if (node instanceof MapExpression map) {
            map.getEntries().forEach((key, value) -> { key.accept(this); value.accept(this); });
        } else if (node instanceof ArrayExpression array) {
            array.getValues().forEach(value -> value.accept(this));
        } else if (node instanceof FilterInvocationExpression filter) {
            filter.getArgs().accept(this);
        } else if (node instanceof TestInvocationExpression test) {
            test.getArgs().accept(this);
        }
    }
}
