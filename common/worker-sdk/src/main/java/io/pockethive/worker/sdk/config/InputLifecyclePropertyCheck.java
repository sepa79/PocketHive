package io.pockethive.worker.sdk.config;

import io.pockethive.work.config.WorkConfigurationProblem;
import io.pockethive.work.config.policy.InputLifecyclePolicy;
import java.util.List;
import org.springframework.boot.context.properties.bind.BindContext;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;

/**
 * Responsibility: supply Spring property presence to the canonical input lifecycle policy.
 * Must not: bind values, expand placeholders, repeat the removed-field list or read process settings.
 * Contract: RESP-WORK-INPUT-LIFECYCLE-POLICY — docs/architecture/runtime-responsibilities.md#resp-work-input-lifecycle-policy.
 */
final class InputLifecyclePropertyCheck implements BindHandler {
    private List<WorkConfigurationProblem> problems = List.of();

    List<WorkConfigurationProblem> check(Binder binder) {
        binder.bind("pockethive.inputs", Bindable.of(Object.class), this);
        return problems;
    }

    @Override
    public <T> Bindable<T> onStart(ConfigurationPropertyName name, Bindable<T> target, BindContext context) {
        problems = new InputLifecyclePolicy().propertyProblems(path -> {
            var propertyName = ConfigurationPropertyName.of(path);
            for (var source : context.getSources()) {
                if (source.getConfigurationProperty(propertyName) != null
                    || source.containsDescendantOf(propertyName) == ConfigurationPropertyState.PRESENT) {
                    return true;
                }
            }
            return false;
        });
        // A null target stops this presence-only pass before value binding or placeholder expansion.
        return null;
    }
}
