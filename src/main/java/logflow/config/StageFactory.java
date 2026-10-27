package logflow.config;

import java.util.List;
import logflow.Stage;

/**
 * Bir {@code stages[]} girdisini {@link Stage} nesnesine çevirir. Giriş/çıkış türleri yükleyicinin
 * aşama sırasını, veri işlenmeden önce denetlemesini sağlar (ör. {@code parse}'tan önce {@code filter}).
 */
public interface StageFactory extends ComponentFactory {
    Class<?> inputType();

    Class<?> outputType();

    /**
     * @param params şemaya göre doğrulanmış parametreler
     * @throws IllegalArgumentException parametreler birlikte anlamsızsa (mesaj kullanıcıya gösterilir)
     */
    Stage<?, ?> create(Params params);

    @Override
    default List<ParamSpec> params() {
        return List.of();
    }
}
