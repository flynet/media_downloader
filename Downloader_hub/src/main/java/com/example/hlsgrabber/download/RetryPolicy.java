package com.example.hlsgrabber.download;

/**
 * Политика повторов: 3 попытки, экспоненциальная пауза (TZ 6.1); уже скачанные
 * сегменты пропускаются при повторе (resume, TZ 6.1).
 * Retry policy: 3 attempts, exponential pause (TZ 6.1); already-downloaded segments
 * are skipped at repeat (resume, TZ 6.1).
 */
public class RetryPolicy {

    private final int attempts;
    private final long baseMs;

    public RetryPolicy() {
        this(3, 1000);
    }

    public RetryPolicy(int attempts, long baseMs) {
        this.attempts = attempts;
        this.baseMs = baseMs;
    }

    /**
     * Выполняет действие с повторами. Действие бросает исключение при неудаче.
     * Возвращает true при успехе.
     * Run an action with retries. The action throws on failure. Returns true on success.
     */
    public boolean run(String what, Runnable action) throws InterruptedException {
        Exception last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                action.run();
                return true;
            } catch (Exception e) {
                last = e;
                long pause = baseMs * (1L << i);
                Thread.sleep(pause);
            }
        }
        throw new RuntimeException("failed after " + attempts + " attempts: " + what, last);
    }
}