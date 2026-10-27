package logflow.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Yapılandırmadaki bir bileşenin tanımı (mimari tanım): yalnızca veri, gerçekleştirim yok.
 *
 * @param location hata mesajları için konum, ör. {@code pipeline.stages[1]}
 * @param name     yalnızca aşamalar için, isteğe bağlı görünen ad ({@code null} = tür adı)
 * @param onError  yalnızca aşamalar için, isteğe bağlı hata politikası ({@code null} = işlem hattının varsayılanı)
 */
public record ComponentSpec(String location, String type, Map<String, Object> params, String name, String onError) {

    public ComponentSpec {
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    public ComponentSpec(String location, String type, Map<String, Object> params) {
        this(location, type, params, null, null);
    }

    /** {@code pipeline.stages[1] (filter)} */
    public String label() {
        return location + " (" + type + ")";
    }
}
