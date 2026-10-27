package logflow.config;

import java.util.List;

/**
 * Yapılandırmadaki bir {@code type} adını gerçekleştirime bağlayan fabrika. Uygulamalar
 * {@link java.util.ServiceLoader} ile keşfedilir: yeni bir bileşen eklemek için sınıfı yazıp
 * {@code META-INF/services/} altındaki dosyaya bir satır eklemek yeterlidir; yükleyici ya da
 * {@code Main} değişmez.
 */
public interface ComponentFactory {
    /** Yapılandırmada kullanılan ad, ör. {@code "filter"}. */
    String type();

    /** {@code --list-stages} için kısa açıklama. */
    String description();

    /** Kabul edilen parametreler; bunların dışındaki her ad yapılandırma hatasıdır. */
    List<ParamSpec> params();
}
