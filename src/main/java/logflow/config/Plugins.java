package logflow.config;

/** Yükleyicinin kullandığı üç kayıt: kaynaklar, aşamalar, hedefler. */
public record Plugins(Registry<SourceFactory> sources, StageRegistry stages, Registry<SinkFactory> sinks) {

    /** Sınıf yolunda ilan edilen bütün bileşenler. */
    public static Plugins discover() {
        return new Plugins(Registry.discover("source", SourceFactory.class), StageRegistry.discover(),
                Registry.discover("sink", SinkFactory.class));
    }
}
