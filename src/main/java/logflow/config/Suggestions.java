package logflow.config;

import java.util.Collection;
import java.util.Optional;

/** "Bunu mu demek istediniz?" önerileri için en yakın adı bulur (Levenshtein uzaklığı). */
final class Suggestions {
    private Suggestions() {
    }

    static Optional<String> closest(String input, Collection<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String c : candidates) {
            int d = distance(input.toLowerCase(), c.toLowerCase());
            if (d < bestDistance) {
                best = c;
                bestDistance = d;
            }
        }
        int tolerance = Math.max(1, Math.min(3, input.length() / 3));
        return bestDistance <= tolerance ? Optional.of(best) : Optional.empty();
    }

    /** {@code " Did you mean 'filter'?"} ya da boş metin. */
    static String didYouMean(String input, Collection<String> candidates) {
        return closest(input, candidates).map(s -> " Did you mean '" + s + "'?").orElse("");
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
