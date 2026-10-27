package logflow;

/**
 * Bir aşamanın tek çalıştırmadaki sayaçları. İşlem hattı tarafından, aşamanın kodundan bağımsız
 * olarak toplanır; böylece her aşama aynı ölçülerle karşılaştırılabilir.
 *
 * @param in           aşamaya giren kayıt
 * @param out          aşamanın yaydığı kayıt ({@code finish} dahil)
 * @param dropped      hatasız işlenip hiç çıktı üretmeyen kayıt (ör. filtrenin elediği)
 * @param failed       {@link StageException} ile reddedilen kayıt (yeniden denemeler sonrası)
 * @param skipped      reddedilip {@code SKIP} ile atılan
 * @param deadLettered reddedilip dead-letter kanalına gönderilen
 * @param retries      yapılan ek deneme sayısı
 * @param selfNanos    aşamanın kendi kodunda geçen süre (sonraki aşamalar hariç)
 * @param oneToOne     her girdi en fazla bir çıktı üretti ve {@code finish} hiçbir şey yaymadı;
 *                     yalnızca böyle aşamalarda kayıt sayıları uçtan uca toplanabilir
 */
public record StageMetrics(String name, String policy, long in, long out, long dropped, long failed,
                           long skipped, long deadLettered, long retries, long selfNanos, boolean oneToOne) {

    /** İşlem hattının içindeki değiştirilebilir sayaç; anlık görüntüsü {@link StageMetrics}. */
    static final class Counter {
        final String name;
        final String policy;
        long in;
        long out;
        long dropped;
        long failed;
        long skipped;
        long deadLettered;
        long retries;
        long selfNanos;
        boolean oneToOne = true;

        Counter(String name, String policy) {
            this.name = name;
            this.policy = policy;
        }

        StageMetrics snapshot() {
            return new StageMetrics(name, policy, in, out, dropped, failed, skipped, deadLettered, retries, selfNanos, oneToOne);
        }
    }
}
