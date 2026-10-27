package logflow;

import java.io.PrintStream;

/** {@link LogRecord} nesnesini okunabilir, tek satırlık biçimde yazdırır. */
public class ConsoleSink implements Sink<LogRecord> {
    private final PrintStream out;

    public ConsoleSink() {
        this(System.out);
    }

    /** Test edilebilirlik için çıktı akışı dışarıdan verilebilir. */
    public ConsoleSink(PrintStream out) {
        this.out = out;
    }

    @Override
    public void consume(LogRecord r) {
        out.println(format(r));
    }

    static String format(LogRecord r) {
        String line = String.format("[%s] %s %s %s -> %d (%d B)",
                r.timestamp(), r.clientIp(), r.method(), r.path(), r.status(), r.bytes());
        return r.userAgent().isEmpty() ? line : line + " ua=\"" + r.userAgent() + "\"";
    }
}
