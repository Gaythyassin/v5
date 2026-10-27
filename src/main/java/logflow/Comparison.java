package logflow;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** {@code filter} aşamasının karşılaştırma işleçleri; yapılandırmada sembolleriyle yazılır. */
public enum Comparison {
    EQ("=="), NE("!="), GT(">"), GE(">="), LT("<"), LE("<="),
    STARTS_WITH("startsWith"), ENDS_WITH("endsWith"), CONTAINS("contains");

    private final String symbol;

    Comparison(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    /** Sıralama gerektiren işleç mi ({@code > >= < <=})? */
    public boolean isOrdering() {
        return this == GT || this == GE || this == LT || this == LE;
    }

    /** Yalnızca metin üzerinde anlamlı işleç mi ({@code startsWith, endsWith, contains})? */
    public boolean isTextual() {
        return this == STARTS_WITH || this == ENDS_WITH || this == CONTAINS;
    }

    /** {@code compareTo} sonucu bu işleci sağlıyor mu? (metinsel işleçler için kullanılmaz) */
    public boolean test(int cmp) {
        return switch (this) {
            case EQ -> cmp == 0;
            case NE -> cmp != 0;
            case GT -> cmp > 0;
            case GE -> cmp >= 0;
            case LT -> cmp < 0;
            case LE -> cmp <= 0;
            default -> throw new IllegalStateException(symbol + " is not an ordering operator");
        };
    }

    public boolean testText(String actual, String expected) {
        return switch (this) {
            case STARTS_WITH -> actual.startsWith(expected);
            case ENDS_WITH -> actual.endsWith(expected);
            case CONTAINS -> actual.contains(expected);
            default -> test(actual.compareTo(expected));
        };
    }

    public static Optional<Comparison> bySymbol(String symbol) {
        return Arrays.stream(values()).filter(c -> c.symbol.equals(symbol)).findFirst();
    }

    public static List<String> symbols() {
        return Arrays.stream(values()).map(Comparison::symbol).toList();
    }

    @Override
    public String toString() {
        return symbol;
    }
}
