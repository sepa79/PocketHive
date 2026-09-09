package io.pockethive.swarmcontroller.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PlaceholdersResolver;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.util.PropertyPlaceholderHelper;
import org.springframework.util.SystemPropertyUtils;

/**
 * Responsibility: bind supplied connection environment snapshots with Spring naming and explicit placeholder modes.
 * Must not: read process settings, compose environment values, validate connections or expose failed input values.
 * Contract: RESP-WORK-CONNECTION-ENVIRONMENT — docs/architecture/runtime-responsibilities.md#resp-work-connection-environment.
 */
public final class SpringConnectionEnvironment {
    private SpringConnectionEnvironment() { }

    public static Function<String, String> raw(Map<String, String> environment) {
        return lookup(new SystemEnvironmentPropertySource("systemEnvironment", new LinkedHashMap<>(environment)),
            PlaceholdersResolver.NONE);
    }

    public static Function<String, String> resolved(Map<String, String> environment) {
        var source = new SystemEnvironmentPropertySource("systemEnvironment", new LinkedHashMap<>(environment));
        var helper = new PropertyPlaceholderHelper(SystemPropertyUtils.PLACEHOLDER_PREFIX,
            SystemPropertyUtils.PLACEHOLDER_SUFFIX, SystemPropertyUtils.VALUE_SEPARATOR,
            SystemPropertyUtils.ESCAPE_CHARACTER, false);
        return lookup(source, new PropertySourcesPlaceholdersResolver(List.of(source), helper));
    }

    private static Function<String, String> lookup(SystemEnvironmentPropertySource source, PlaceholdersResolver placeholders) {
        var binder = new Binder(ConfigurationPropertySources.from(source), placeholders);
        return name -> {
            try {
                return binder.bind(name, String.class).orElse(null);
            } catch (BindException invalid) {
                throw new IllegalArgumentException("Cannot bind connection environment property " + name);
            }
        };
    }
}
