package com.oleg.td;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Сервис-обёртка: старт/стоп дампа и прогресс.
 */
@Service
public class DumpService {
    private static final Logger log = LoggerFactory.getLogger(DumpService.class);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private int progress = 0;

    private final AuthFlow authFlow;
    private final ChatDumpCoordinator coordinator;
    private final ChatResolver resolver;
    private final DatabaseManager db;

    public DumpService(AuthFlow authFlow, ChatDumpCoordinator coordinator, ChatResolver resolver, DatabaseManager db) {
        this.authFlow = authFlow;
        this.coordinator = coordinator;
        this.resolver = resolver;
        this.db = db;
    }

    /**
     * Старт дампа:
     * 1) Подключаемся к TDLib (авторизация)
     * 2) Для каждого чата создаём схему в БД
     * 3) Запускаем координатор
     */
    public synchronized void startDump(DumpRequest request) {
        if (running.get()) {
            log.warn("Дамп уже выполняется");
            return;
        }
        running.set(true);
        progress = 0;

        try {
            log.info("Инициализация авторизации TDLib...");
            authFlow.wireInto();
            authFlow.authorizeBlocking();

            if (!authFlow.isAuthorized()) {
                log.error("Авторизация не удалась — дамп прерван");
                return;
            }

            // Создать схемы сразу при старте
            for (String ref : request.getChats()) {
                try {
                    long chatId = resolver.resolveFlexible(ref); // <--- обновлено
                    db.prepareSchema(chatId);
                } catch (Exception ex) {
                    log.error("Не удалось подготовить схему для '{}': {}", ref, ex.getMessage(), ex);
                }
            }

            log.info("Запуск дампа чатов...");
            coordinator.dumpChats(request, this::incrementProgress);
            log.info("Дамп завершён");
        } catch (Exception e) {
            log.error("Ошибка при выполнении дампа: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    /**
     * Остановить дамп.
     */
    public void stopDump() {
        coordinator.stop();
        log.info("Получен сигнал остановки дампа");
    }

    /**
     * Текущий прогресс.
     */
    public DumpProgress getProgress() {
        return new DumpProgress(progress, running.get());
    }

    /**
     * Инкремент — вызывается координатором на каждое обработанное сообщение.
     */
    private synchronized void incrementProgress() {
        progress++;
    }
}
