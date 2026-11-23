package dev.tactical.engine.pack;

public record PackIssue(boolean error, String path, String message) {
    @Override
    public String toString() {
        return (error ? "ERROR " : "WARNING ") + path + ": " + message;
    }
}
