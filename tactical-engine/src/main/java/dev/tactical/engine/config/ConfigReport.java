package dev.tactical.engine.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Collects every issue found while loading a file, so admins see ALL problems at once, not one per reload. */
public final class ConfigReport {

    private final List<ConfigIssue> issues = new ArrayList<>();

    public void error(String file, int line, String path, String message) {
        issues.add(new ConfigIssue(ConfigIssue.Severity.ERROR, file, line, path, message));
    }

    public void warning(String file, int line, String path, String message) {
        issues.add(new ConfigIssue(ConfigIssue.Severity.WARNING, file, line, path, message));
    }

    public boolean hasErrors() {
        return issues.stream().anyMatch(i -> i.severity() == ConfigIssue.Severity.ERROR);
    }

    public List<ConfigIssue> issues() {
        return Collections.unmodifiableList(issues);
    }

    public List<ConfigIssue> errors() {
        return issues.stream().filter(i -> i.severity() == ConfigIssue.Severity.ERROR).toList();
    }

    public void addAll(ConfigReport other) {
        issues.addAll(other.issues);
    }
}
