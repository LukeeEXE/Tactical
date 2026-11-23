package dev.tactical.engine.record;

/** Destination for one match's serialized recording lines. Implementations must never block the caller for long. */
public interface RecordingSink {

    /**
     * @param line     one complete JSON object (no trailing newline)
     * @param priority {@link Priority#LOW} lines (position samples) may be dropped under back-pressure;
     *                 {@link Priority#CRITICAL} lines are flushed and fsynced promptly
     */
    void write(String line, Priority priority);

    /** Writes any buffered data and closes the destination. The footer must already have been written. */
    void close();

    enum Priority { LOW, NORMAL, CRITICAL }
}
