package io.pockethive.scenarios.validation;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScenarioProtocol {
    public static final String CURRENT_VERSION = "2.0.0";
    public static final int CURRENT_MAJOR = 2;

    private static final Pattern SEMVER = Pattern.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$");

    private ScenarioProtocol() {
    }

    public static boolean isValid(String version) {
        return version != null && SEMVER.matcher(version).matches();
    }

    public static boolean isCompatible(String version) {
        Matcher matcher = version == null ? null : SEMVER.matcher(version);
        return matcher != null && matcher.matches() && Integer.parseInt(matcher.group(1)) == CURRENT_MAJOR;
    }
}
