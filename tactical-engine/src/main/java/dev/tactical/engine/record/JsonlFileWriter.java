package dev.tactical.engine.record;

import java.io.IOException;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * One background thread that writes every match's recording file. The main thread only enqueues strings.
 * <ul>
 *   <li>Files are created with CREATE_NEW: an existing recording is never overwritten.</li>
 *   <li>CRITICAL lines trigger flush + fsync, so round/match results survive a crash.</li>
 *   <li>Under back-pressure (queue above {@code softLimit}) LOW-priority lines are dropped and counted;
 *       NORMAL/CRITICAL lines are never dropped.</li>
 *   <li>Other lines are flushed at least every {@code flushIntervalMillis}.</li>
 * </ul>
 */
public final class JsonlFileWriter implements AutoCloseable {

    private sealed interface Op permits Open, Line, Close, Stop {}
    private record Open(String key, Path path) implements Op {}
    private record Line(String key, String text, RecordingSink.Priority priority) implements Op {}
    private record Close(String key) implements Op {}
    private record Stop() implements Op {}

    private final LinkedBlockingQueue<Op> queue = new LinkedBlockingQueue<>();
    private final Map<String, Target> targets = new ConcurrentHashMap<>();
    private final int softLimit;
    private final long flushIntervalMillis;
    private final BiConsumer<String, Throwable> errorHandler;
    private final AtomicLong dropped = new AtomicLong();
    private final Thread thread;

    private static final class Target {
        final FileChannel channel;
        final Writer writer;
        boolean dirty;

        Target(FileChannel channel) {
            this.channel = channel;
            this.writer = Channels.newWriter(channel, StandardCharsets.UTF_8);
        }
    }

    public JsonlFileWriter(int softLimit, long flushIntervalMillis, BiConsumer<String, Throwable> errorHandler) {
        this.softLimit = softLimit;
        this.flushIntervalMillis = flushIntervalMillis;
        this.errorHandler = Objects.requireNonNull(errorHandler);
        this.thread = Thread.ofPlatform().name("Tactical-Recorder").daemon(false).unstarted(this::run);
        this.thread.start();
    }

    /** Creates a sink for a new recording file. The file is created on the writer thread. */
    public RecordingSink open(String key, Path path) {
        queue.add(new Open(key, path));
        return new RecordingSink() {
            @Override
            public void write(String line, Priority priority) {
                if (priority == Priority.LOW && queue.size() > softLimit) {
                    dropped.incrementAndGet();
                    return;
                }
                queue.add(new Line(key, line, priority));
            }

            @Override
            public void close() {
                queue.add(new Close(key));
            }
        };
    }

    public long droppedLines() {
        return dropped.get();
    }

    public int pending() {
        return queue.size();
    }

    /** Drains everything, closes all files and stops the thread (plugin disable). */
    @Override
    public void close() {
        queue.add(new Stop());
        try {
            thread.join(TimeUnit.SECONDS.toMillis(30));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        long lastFlush = System.currentTimeMillis();
        while (true) {
            Op op;
            try {
                op = queue.poll(flushIntervalMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                op = new Stop();
            }
            if (op instanceof Stop) {
                targets.keySet().forEach(this::closeTarget);
                return;
            }
            if (op != null) handle(op);
            long now = System.currentTimeMillis();
            if (now - lastFlush >= flushIntervalMillis) {
                for (Map.Entry<String, Target> e : targets.entrySet()) flush(e.getKey(), e.getValue(), false);
                lastFlush = now;
            }
        }
    }

    private void handle(Op op) {
        switch (op) {
            case Open(String key, Path path) -> {
                try {
                    Files.createDirectories(path.getParent());
                    FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    targets.put(key, new Target(ch));
                } catch (IOException e) {
                    errorHandler.accept(key, e);
                }
            }
            case Line(String key, String text, RecordingSink.Priority priority) -> {
                Target t = targets.get(key);
                if (t == null) return; // open failed; already reported
                try {
                    t.writer.write(text);
                    t.writer.write('\n');
                    t.dirty = true;
                    if (priority == RecordingSink.Priority.CRITICAL) flush(key, t, true);
                } catch (IOException e) {
                    errorHandler.accept(key, e);
                }
            }
            case Close(String key) -> closeTarget(key);
            case Stop() -> { }
        }
    }

    private void flush(String key, Target t, boolean sync) {
        if (!t.dirty && !sync) return;
        try {
            t.writer.flush();
            if (sync) t.channel.force(false);
            t.dirty = false;
        } catch (IOException e) {
            errorHandler.accept(key, e);
        }
    }

    private void closeTarget(String key) {
        Target t = targets.remove(key);
        if (t == null) return;
        try {
            t.writer.flush();
            t.channel.force(true);
            t.writer.close();
        } catch (IOException e) {
            errorHandler.accept(key, e);
        }
    }
}
