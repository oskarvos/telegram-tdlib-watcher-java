// Логика блока «Поиск»: запуск/стоп/очистка БД и опрос прогресса.
import { ApiClient } from '../apiClient.js';
import { Notifier } from '../components/Notifier.js';

export class SearchModule extends ApiClient {
    constructor() {
        super();
        this.notify = new Notifier();
        this.pollInterval = null;

        // DOM
        this.dom = {
            searchContainer: document.getElementById('searchContainer'),

            chats:   document.getElementById('searchChats'),
            keyword: document.getElementById('searchKeyword'),
            case:    document.getElementById('caseSensitive'),
            regex:   document.getElementById('regexMode'),

            btnStart: document.getElementById('startSearchBtn'),
            btnStop:  document.getElementById('stopSearchBtn'),
            btnDelDb: document.getElementById('deleteSearchDbBtn'),

            processed: document.getElementById('searchProcessedValue'),
            found:     document.getElementById('searchFoundValue'),
            bar:       document.getElementById('searchProgress-bar'),
            status:    document.getElementById('searchStatus'),
        };

        // Навешиваем обработчики
        this.dom.btnStart.addEventListener('click', () => this.startSearch());
        this.dom.btnStop .addEventListener('click', () => this.stopSearch());
        this.dom.btnDelDb.addEventListener('click', () => this.deleteSearchDatabase());
    }

    #collectRequest() {
        const chats = (this.dom.chats.value || '')
            .split(/\n+/).map(s => s.trim()).filter(Boolean);

        return {
            chats,
            keyword: this.dom.keyword.value.trim(),
            caseSensitive: this.dom.case.checked,
            useRegex: this.dom.regex.checked
        };
    }

    async startSearch() {
        const req = this.#collectRequest();

        if (!req.chats.length)
            return this.setStatus('Ошибка: не указаны чаты для поиска', '#ffecec', '#e74c3c');

        if (!req.keyword)
            return this.setStatus('Ошибка: не указано ключевое слово', '#ffecec', '#e74c3c');

        this.dom.bar.classList.remove('green');
        this.setStatus('Начинаем поиск...', '#edf7ff', '#2c3e50');
        this.setProgress(0, 0, false);

        try {
            await this.post('/api/search/start', req);
            this.setStatus('Поиск выполняется...', '#edf7ff', '#2c3e50');
            this.notify.info('Поиск запущен');
            this.#beginPolling();
        } catch (e) {
            this.setStatus('Ошибка запуска: ' + e.message, '#ffecec', '#e74c3c');
            this.notify.error(e.message);
        }
    }

    async stopSearch() {
        this.setStatus('Останавливаем поиск...', '#fff4e6', '#e67e22');
        try {
            await this.post('/api/search/stop', {});
            this.setStatus('Поиск остановлен', '#ffecec', '#e74c3c');
            this.#endPolling();
        } catch (e) {
            this.setStatus('Ошибка: ' + e.message, '#ffecec', '#e74c3c');
        }
    }

    async deleteSearchDatabase() {
        try {
            await this.del('/api/search/database');
            this.setStatus('База данных поиска удалена', '#e7f6ec', '#27ae60');
            this.notify.ok('SEARCH-БД очищена');
        } catch (e) {
            this.setStatus('Ошибка удаления БД: ' + e.message, '#ffecec', '#e74c3c');
        }
    }

    #beginPolling() { this.#endPolling(); this.#pollOnce(); this.pollInterval = setInterval(() => this.#pollOnce(), 1000); }
    #endPolling()   { if (this.pollInterval) { clearInterval(this.pollInterval); this.pollInterval = null; } }

    async #pollOnce() {
        try {
            const p = await this.get('/api/search/progress');
            const processed = p?.processedMessages || 0;
            const found     = p?.foundMessages || 0;
            this.setProgress(processed, found, !p?.running);

            if (p?.running) {
                this.setStatus(`Поиск выполняется... Обработано: ${processed}, Найдено: ${found}`, '#edf7ff', '#2c3e50');
            } else {
                this.setStatus('Поиск завершен!', '#e7f6ec', '#27ae60');
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
