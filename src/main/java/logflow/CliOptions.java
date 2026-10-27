package logflow;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Komut satırı argümanları. Filtreler komut satırında <b>yazıldıkları sırayla</b> uygulanır,
 * böylece sıralama deneyi doğrudan komut satırından da yapılabilir.
 *
 * @param input   okunacak log dosyası
 * @param filters uygulanacak filtreler, sırasıyla
 * @param fields  projeksiyon alanları; boşsa projeksiyon yapılmaz
 * @param output  çıktı dosyası; {@code null} ise konsola yazılır
 */
public record CliOptions(Path input, List<NamedFilter> filters, List<String> fields, Path output) {

    /** Açıklaması olan bir {@code LogRecord} koşulu (ör. {@code "status>=400"}). */
    public record NamedFilter(String description, Predicate<LogRecord> predicate) {
    }

    public static final String USAGE = String.join(System.lineSeparator(),
            "Usage: logflow <log-file> [options]",
            "  --min-status N         keep records with status >= N",
            "  --path-prefix P        keep records whose path starts with P",
            "  --from TIME            keep records at or after TIME",
            "  --to TIME              keep records before TIME",
            "  --fields a,b,c         project each record to these fields",
            "                         (" + String.join(",", LogRecord.FIELD_NAMES) + ")",
            "  --out FILE             write to FILE instead of the console",
            "Filters run in the order they are given. TIME is ISO-8601",
            "(2026-09-29T10:10:00+03:00) or CLF (29/Sep/2026:10:10:00 +0300).");

    public CliOptions {
        filters = List.copyOf(filters);
        fields = List.copyOf(fields);
    }

    /** @throws IllegalArgumentException argümanlar geçersizse (mesaj kullanıcıya gösterilir) */
    public static CliOptions parse(String... args) {
        Path input = null;
        Path output = null;
        List<String> fields = List.of();
        List<NamedFilter> filters = new ArrayList<>();
        OffsetDateTime from = null;
        OffsetDateTime to = null;
        int timeSlot = -1; // zaman filtresinin, ilk --from/--to'nun konumundaki yeri

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--min-status" -> {
                    int min = parseInt(arg, value(args, ++i, arg));
                    filters.add(new NamedFilter("status>=" + min, Filters.minStatus(min)));
                }
                case "--path-prefix" -> {
                    String prefix = value(args, ++i, arg);
                    filters.add(new NamedFilter("path^" + prefix, Filters.pathPrefix(prefix)));
                }
                case "--from", "--to" -> {
                    OffsetDateTime t = parseTime(arg, value(args, ++i, arg));
                    if (arg.equals("--from")) from = t; else to = t;
                    if (timeSlot < 0) {
                        timeSlot = filters.size();
                        filters.add(null); // yer tutucu; aşağıda doldurulur
                    }
                }
                case "--fields" -> fields = parseFields(value(args, ++i, arg));
                case "--out" -> output = Path.of(value(args, ++i, arg));
                default -> {
                    if (arg.startsWith("--")) {
                        throw new IllegalArgumentException("Unknown option: " + arg);
                    }
                    if (input != null) {
                        throw new IllegalArgumentException("Only one input file is allowed, got: " + input + ", " + arg);
                    }
                    input = Path.of(arg);
                }
            }
        }
        if (input == null) {
            throw new IllegalArgumentException("Missing input file");
        }
        if (timeSlot >= 0) {
            String desc = "time in [" + (from == null ? "-inf" : from) + ", " + (to == null ? "+inf" : to) + ")";
            filters.set(timeSlot, new NamedFilter(desc, Filters.between(from, to)));
        }
        return new CliOptions(input, filters, fields, output);
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length || args[i].startsWith("--")) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[i];
    }

    private static int parseInt(String option, String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " expects a number, got: " + text);
        }
    }

    static OffsetDateTime parseTime(String option, String text) {
        for (DateTimeFormatter f : List.of(DateTimeFormatter.ISO_OFFSET_DATE_TIME, ParserStage.CLF_TIME)) {
            try {
                return OffsetDateTime.parse(text, f);
            } catch (DateTimeParseException ignored) {
                // sıradaki biçimi dene
            }
        }
        throw new IllegalArgumentException(option + " expects a time like 2026-09-29T10:10:00+03:00, got: " + text);
    }

    private static List<String> parseFields(String text) {
        List<String> list = Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (list.isEmpty()) {
            throw new IllegalArgumentException("--fields needs at least one field name");
        }
        return list;
    }
}
