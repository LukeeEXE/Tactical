package dev.tactical.paper.record;

import java.nio.file.Path;

/** Summary of one recording file, for /game logs. */
public record RecordingInfo(String matchId, Path file, long sizeBytes, long lines, boolean complete,
                            String mode, String startedAt, String endReason, String score, long errors, boolean compressed) {
}
