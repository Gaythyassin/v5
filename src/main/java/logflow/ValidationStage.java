package logflow;

import java.util.ArrayList;
import java.util.List;

/**
 * Bildirimsel kurallarla kayıt doğrulama. Kurallar yapılandırmadan gelir ({@link ValidationRule});
 * geçen kayıt değişmeden iletilir, geçmeyen kayıt <b>bütün</b> ihlalleriyle birlikte
 * {@link StageException} olarak reddedilir ve aşamanın hata politikasına yönlendirilir.
 */
public class ValidationStage implements Stage<LogRecord, LogRecord>, Reportable {
    private final List<ValidationRule> rules;
    private long valid;
    private long invalid;

    public ValidationStage(List<ValidationRule> rules) {
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("at least one validation rule is required");
        }
        this.rules = List.copyOf(rules);
    }

    @Override
    public void open() {
        valid = 0;
        invalid = 0;
    }

    @Override
    public void process(LogRecord input, Emitter<LogRecord> out) throws StageException {
        List<String> violations = new ArrayList<>();
        for (ValidationRule rule : rules) {
            rule.check(input).ifPresent(violations::add);
        }
        if (!violations.isEmpty()) {
            invalid++;
            throw new StageException(String.join("; ", violations));
        }
        valid++;
        out.emit(input);
    }

    public int ruleCount() {
        return rules.size();
    }

    @Override
    public String report() {
        return "validate   rules: " + rules.size() + ", valid: " + valid + ", invalid: " + invalid;
    }
}
