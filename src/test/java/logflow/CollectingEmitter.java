package logflow;

import java.util.ArrayList;
import java.util.List;

/** Test ikizi: bir aşamanın yaydığı her şeyi bir listede toplar. */
public class CollectingEmitter<T> implements Emitter<T> {
    private final List<T> items = new ArrayList<>();

    @Override
    public void emit(T item) {
        items.add(item);
    }

    public List<T> items() {
        return items;
    }
}
