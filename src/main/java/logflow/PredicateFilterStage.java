package logflow;

import java.util.Objects;
import java.util.function.Predicate;

/** Bir kaydı yalnızca koşul sağlanıyorsa, değiştirmeden iletir. */
public class PredicateFilterStage<T> implements Stage<T, T>, Reportable {
    private final Predicate<? super T> predicate;
    private final String description;
    private long evaluated;
    private long passed;

    public PredicateFilterStage(Predicate<? super T> predicate) {
        this(predicate, "predicate");
    }

    public PredicateFilterStage(Predicate<? super T> predicate, String description) {
        this.predicate = Objects.requireNonNull(predicate, "predicate");
        this.description = Objects.requireNonNull(description, "description");
    }

    @Override
    public void process(T input, Emitter<T> out) {
        evaluated++;
        if (predicate.test(input)) {
            passed++;
            out.emit(input);
        }
    }

    public long evaluatedCount() { return evaluated; }

    public long passedCount() { return passed; }

    public String description() { return description; }

    @Override
    public String report() {
        return String.format("filter     %-30s in: %d, passed: %d", description, evaluated, passed);
    }

    @Override
    public String toString() { return "Filter[" + description + "]"; }
}
