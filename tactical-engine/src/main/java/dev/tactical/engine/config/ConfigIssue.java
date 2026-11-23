package dev.tactical.engine.config;

/**
 * One validation problem, always traceable to a file, line and YAML key path.
 * Rendered as {@code maps/ascent.yml:14: spawns.attackers[2].yaw - must be a number}.
 */
public record ConfigIssue(Severity severity, String file, int line, String path, String message) {

    public enum Severity { ERROR, WARNING }

    @Override
    public String toString() {
        String loc = line > 0 ? file + ":" + line : file;
        return severity + " " + loc + ": " + (path.isEmpty() ? "" : path + " - ") + message;
    }
}
