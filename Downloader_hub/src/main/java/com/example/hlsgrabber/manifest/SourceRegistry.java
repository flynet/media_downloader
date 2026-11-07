package com.example.hlsgrabber.manifest;

import com.example.hlsgrabber.util.UrlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Реестр обнаруженных источников (TZ 6.7): дедупликация по канонизированному URL,
 * фильтр по минимальному размеру, слушатели изменений (GUI + очередь), вызываемые
 * вне Swing EDT; слушатели должны коалесцировать.
 *
 * Registry of detected sources (TZ 6.7): dedup by canonicalized URL, min-size filter,
 * change listeners (GUI + queue) invoked off the Swing EDT; listeners must coalesce.
 */
public final class SourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(SourceRegistry.class);

    private final Map<String, SourceRecord> byUrl = new ConcurrentHashMap<>();
    private final List<Consumer<SourceRecord>> listeners = new CopyOnWriteArrayList<>();
    private final long minSizeBytes;

    public SourceRegistry(long minSizeBytes) {
        this.minSizeBytes = minSizeBytes;
    }

    public void addListener(Consumer<SourceRecord> l) { listeners.add(l); }

    /** Зарегистрировать обнаруженный источник (из sniffing). Возвращает true, если новый.
     *  Register a detected source (from sniffing). Returns true if new. */
    public boolean add(String rawUrl, SourceRecord.Kind kind, String pageUrl, String pageTitle, long sizeHint) {
        String url = UrlUtils.canonicalize(rawUrl);
        return add(new SourceRecord(url, kind, pageUrl, pageTitle, sizeHint));
    }

    /** Зарегистрировать готовую запись (sniffer даёт masterUrl + заголовки запроса). Возвращает true, если новый.
     *  Register a pre-built record (sniffer supplies masterUrl + fetch headers). Returns true if new. */
    public boolean add(SourceRecord rec) {
        // Фильтр по минимальному размеру (TZ 6.7): применяется только к прямым медиа-файлам,
        // не к манифестам
        // min-size filter (TZ 6.7): applies to direct media files only, not to manifests
        if (rec.kind == SourceRecord.Kind.MP4 && rec.sizeBytes() > 0 && rec.sizeBytes() < minSizeBytes) {
            log.debug("Skip by size filter ({} B < {} B): {}", rec.sizeBytes(), minSizeBytes, rec.canonicalUrl);
            return false;
        }
        if (byUrl.putIfAbsent(rec.canonicalUrl, rec) != null) {
            // повторный запрос манифеста по тому же URL: хранить первую запись
            // (состояние сохраняется как есть)
            // manifest re-requested for the same URL: keep first record (state kept as-is)
            return false;
        }
        log.info("Detected {} source: {}", rec.kind, rec.canonicalUrl);
        notifyChanged(rec);
        return true;
    }

    /** Существующая запись для канонизированного URL или null.
     *  Existing record for a canonicalized URL, or null. */
    public SourceRecord get(String canonicalUrl) {
        return byUrl.get(canonicalUrl);
    }

    /** Уведомить слушателей об изменении состояния/прогресса существующей записи.
     *  Notify listeners of a state/progress change of an existing record. */
    public void notifyChanged(SourceRecord rec) {
        for (Consumer<SourceRecord> l : listeners) {
            l.accept(rec);
        }
    }

    public List<SourceRecord> records() {
        return List.copyOf(byUrl.values());
    }
}
