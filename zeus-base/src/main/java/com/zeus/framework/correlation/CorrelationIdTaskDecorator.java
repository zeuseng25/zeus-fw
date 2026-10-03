package com.zeus.framework.correlation;

import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * MDC bağlamını (dolayısıyla correlation ID'yi) worker thread'e taşır.
 *
 * <p><b>Neden gerekli:</b> MDC thread-local'dır. {@code @Async}, {@code CompletableFuture}
 * veya herhangi bir {@code TaskExecutor} kullanıldığında iş başka bir thread'e geçer ve
 * correlation ID <b>sessizce kaybolur</b> — hata vermez, log satırı kimliksiz basılır.
 * İzlenebilirlikte en sık düşülen tuzak budur.
 *
 * <p>Decorator, görevi <i>oluşturan</i> thread'in bağlamını kopyalar ve görev çalışırken
 * worker thread'e yükler; iş bitince worker'ın önceki bağlamını geri koyar (havuzdaki
 * thread bir sonraki göreve kirli bağlamla girmesin).
 */
public class CorrelationIdTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // Görevi oluşturan (çağıran) thread'in bağlamı — decorate() o thread'de çalışır.
        Map<String, String> submitterContext = CorrelationId.capture();
        return () -> {
            Map<String, String> workerPrevious = CorrelationId.capture();
            try {
                CorrelationId.restore(submitterContext);
                runnable.run();
            } finally {
                CorrelationId.restore(workerPrevious);
            }
        };
    }
}
