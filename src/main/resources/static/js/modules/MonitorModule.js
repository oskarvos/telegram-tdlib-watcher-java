import {ApiClient} from '../apiClient.js';
import {Notifier} from '../components/Notifier.js';

export class MonitorModule extends ApiClient {
    constructor() {
        super();
        this.notify = new Notifier();
        this.pollInterval = null;

        this.dom = {
            container: document.getElementById('monitorContainer'),
            chats: document.getElementById('monitorChats'),
            keyword: document.getElementById('monitorKeyword'),
            case: document.getElementById('monitorCaseSensitive'),
            regex: document.getElementById('monitorRegexMode'),
            btnStart: document.getElementById('startMonitorBtn'),
            btnStop: document.getElementById('stopMonitorBtn'),
            btnDelDb: document.getElementById('deleteMonitorDbBtn'),
            processed: document.getElementById('monitorProcessedValue'),
            found: document.getElementById('monitorFoundValue'),
            bar: document.getElementById('monitorProgress-bar'),
            status: document.getElementById('monitorStatus'),
            interval: document.getElementById('monitorInterval'),
        };

        this.dom.btnStart.addEventListener('click', () => this.start());
        this.dom.btnStop.addEventListener('click', () => this.stop());
        this.dom.btnDelDb.addEventListener('click', () => this.clearDb());
    }

    #collect() {
        const chats = (this.dom.chats.value || '').split(/\n+/).map(s => s.trim()).filter(Boolean);
        return {
            chats,
            keyword: this.dom.keyword.value.trim(),
            caseSensitive: this.dom.case.checked,
            useRegex: this.dom.regex.checked,
            // НОВОЕ: отправляем фиксированное значение из селекта
            pollInterval: this.dom.interval.value
        };

    }

    async start() {
        const req = this.#collect();
        if (!req.chats.length)
            return this.setStatus('Ошибка: не указаны чаты', '#ffecec', '#e74c3c');
        if (!req.keyword)
            return this.setStatus('Ошибка: не указано ключевое слово', '#ffecec', '#e74c3c');

        this.dom.bar.classList.remove('green');
        this.setStatus('Старт мониторинга...', '#edf7ff', '#2c3e50');
        this.setProgress(0, 0, false);
        try {
            await this.post('/api/monitor/start', req);
            this.setStatus('Мониторинг выполняется...', '#edf7ff', '#2c3e50');
            this.notify.info('Мониторинг запущен');
            this.#beginPolling();
        } catch (e) {
            this.setStatus('Ошибка запуска: ' + e.message, '#ffecec', '#e74c3c');
            this.notify.error(e.message);
        }
    }

    async stop() {
        this.setStatus('Останавливаем мониторинг...', '#fff4e6', '#e67e22');
        try {
            await this.post('/api/monitor/stop', {});
            this.setStatus('Мониторинг остановлен', '#ffecec', '#e74c3c');
            this.#endPolling();
        } catch (e) {
            this.setStatus('Ошибка: ' + e.message, '#ffecec', '#e74c3c');
        }
    }

    async clearDb() {
        try {
            await this.del('/api/monitor/database');
            this.setStatus('База мониторинга очищена', '#e7f6ec', '#27ae60');
            this.notify.ok('MONITOR-БД очищена, чекпоинты сброшены');
        } catch (e) {
            this.setStatus('Ошибка удаления БД: ' + e.message, '#ffecec', '#e74c3c');
        }
    }

    #beginPolling() {
        this.#endPolling();
        this.#tick();
        this.pollInterval = setInterval(() => this.#tick(), 1000);
    }

    #endPolling() {
        if (this.pollInterval) {
            clearInterval(this.pollInterval);
            this.pollInterval = null;
        }
    }

    async #tick() {
        try {
            const p = await this.get('/api/monitor/progress');
            const processed = p?.processedMessages || 0;
            const found = p?.foundMessages || 0;
            this.setProgress(processed, found, !p?.running);
            if (p?.running) {
                this.setStatus(`Мониторинг... Обработано: ${processed}, Найдено: ${found}`, '#edf7ff', '#2c3e50');
            } else {
                this.setStatus('Мониторинг остановлен', '#ffecec', '#e74c3c');
                this.#endPolling();
            }
        } catch (e) {
            this.setStatus('Ошибка запроса прогресса: ' + e.message, '#ffecec', '#e74c3c');
            this.#endPolling();
        }
    }

    setProgress(processed, found, complete = false) {
        this.dom.processed.textContent = processed;
        this.dom.found.textContent = found;
        this.dom.bar.classList.toggle('green', !!complete);
    }

    setStatus(text, bg, color) {
        this.dom.status.textContent = text;
        this.dom.status.style.backgroundColor = bg;
        this.dom.status.style.color = color;
    }
}
