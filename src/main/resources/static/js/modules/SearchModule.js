import {ApiClient} from '../apiClient.js';
import {Notifier} from '../components/Notifier.js';

export class SearchModule extends ApiClient {
    constructor() {
        super('/api/search');
        this.notify = new Notifier();
        this.pollHandle = null;
        this.MAX_KEYWORD_LEN = 30;
        this.storageKey = 'tdtools.search.v1';

        this.dom = {
            container: document.getElementById('searchContainer'),
            chats: document.getElementById('searchChats'),
            keyword: document.getElementById('searchKeyword'),
            case: document.getElementById('caseSensitive'),
            regex: document.getElementById('regexMode'),
            // опциональные (если добавишь режим по длине в верстку)
            lengthMode: document.getElementById('lengthMode'),
            lengthValue: document.getElementById('lengthValue'),

            kwLenHint: document.getElementById('kwLenHint'),

            btnStart: document.getElementById('startSearchBtn'),
            btnStop: document.getElementById('stopSearchBtn'),
            btnLoadRes: document.getElementById('loadSearchResultsBtn'),
            btnDelDb: document.getElementById('deleteSearchDbBtn'),

            processedValue: document.getElementById('searchProcessedValue'),
            foundValue: document.getElementById('searchFoundValue'),
            status: document.getElementById('searchStatus'),
            barWrap: document.getElementById('searchProgress'),
            bar: document.getElementById('searchProgress-bar'),

            resultsChat: document.getElementById('searchResultsChat'),
            resultsBox: document.getElementById('searchResults')
        };

        // maxlength на всякий случай
        if (this.dom.keyword && !this.dom.keyword.hasAttribute('maxlength')) {
            this.dom.keyword.setAttribute('maxlength', String(this.MAX_KEYWORD_LEN));
        }

        // События
        this.dom.btnStart?.addEventListener('click', (e) => {
            e.preventDefault();
            this.start();
        });
        this.dom.btnStop?.addEventListener('click', () => this.stop());
        this.dom.btnDelDb?.addEventListener('click', () => this.clearDb());
        this.dom.btnLoadRes?.addEventListener('click', () => this.loadResults());

        this.dom.keyword?.addEventListener('input', () => {
            this.#updateKwLen();
            this.saveState();
        });
        this.dom.chats?.addEventListener('input', () => this.saveState());
        this.dom.case?.addEventListener('change', () => this.saveState());
        this.dom.regex?.addEventListener('change', () => this.saveState());

        // «по длине» — если элементы есть
        const syncLengthUi = () => {
            const on = !!this.dom.lengthMode?.checked;
            if (this.dom.lengthValue) this.dom.lengthValue.disabled = !on;
            if (this.dom.keyword) this.dom.keyword.disabled = on;
            if (this.dom.regex) {
                this.dom.regex.checked = on ? true : this.dom.regex.checked;
                this.dom.regex.disabled = on;
            }
        };
        this.dom.lengthMode?.addEventListener('change', () => {
            syncLengthUi();
            this.saveState();
        });
        this.dom.lengthValue?.addEventListener('input', () => this.saveState());
        syncLengthUi();

        // Инициализация
        this.restoreState();
        this.#updateKwLen();
        this.setProgress(0, 0, false);
        this.setStatus('Ожидание…', '#e9f1ff', '#2c3e50');
    }

    // ======== ПУБЛИЧНЫЕ МЕТОДЫ ========

    async start() {
        let req;
        try {
            req = this.#collect();
        } catch (e) {
            return this.setStatus('Ошибка: ' + e.message, '#ffecec', '#e74c3c');
        }

        if (!req.chats.length) {
            return this.setStatus('Ошибка: не указаны чаты', '#ffecec', '#e74c3c');
        }
        if (!req.useRegex && req.keyword.length > this.MAX_KEYWORD_LEN) {
            return this.setStatus(`Ошибка: длина ключа не должна превышать ${this.MAX_KEYWORD_LEN} символов`, '#ffecec', '#e74c3c');
        }

        this.saveState();

        // UI
        this.dom.bar?.classList.remove('green');
        if (req.keyword.length === 0) {
            this.setStatus('Пустой ключ: индексируем все непустые сообщения/подписи', '#edf7ff', '#2c3e50');
        } else {
            this.setStatus('Запуск поиска…', '#edf7ff', '#2c3e50');
        }
        this.setProgress(0, 0, false);

        try {
            console.log('REQ', req, 'kw.length=', req.keyword.length);
            await this.post('/start', req); // POST /api/search/start
            this.notify.info('Поиск запущен');
            this.setStatus('Поиск выполняется…', '#edf7ff', '#2c3e50');
            this.#beginPolling();
        } catch (e) {
            this.setStatus('Ошибка запуска: ' + e.message, '#ffecec', '#e74c3c');
        }
    }

    async stop() {
        this.setStatus('Останавливаем поиск…', '#fff4e6', '#e67e22');
        try {
            await this.post('/stop', {}); // POST /api/search/stop
            this.#endPolling();
            this.setStatus('Поиск остановлен', '#fff4e6', '#e67e22');
        } catch (e) {
            this.setStatus('Ошибка остановки: ' + e.message, '#ffecec', '#e74c3c');
        }
    }

    async clearDb() {
        try {
            await this.del('/database'); // DELETE /api/search/database
            this.notify.ok('SEARCH-БД очищена');
            this.setStatus('База поиска очищена', '#e7f6ec', '#27ae60');
            if (this.dom.resultsBox) this.dom.resultsBox.innerHTML = '';
        } catch (e) {
            this.setStatus('Ошибка удаления БД: ' + e.message, '#ffecec', '#e74c3c');
        }
    }

    async loadResults() {
        const chat = (this.dom.resultsChat?.value || '').trim();
        if (!chat) {
            return this.setStatus('Укажите чат для показа результатов', '#fff4e6', '#e67e22');
        }
        try {
            const data = await this.get('/results?chat=' + encodeURIComponent(chat)); // GET /api/search/results
            this.#renderResults(data || []);
            this.setStatus('Результаты загружены', '#e7f6ec', '#27ae60');
        } catch (e) {
            this.setStatus('Ошибка загрузки результатов: ' + e.message, '#ffecec', '#e74c3c');
        }
    }

    // ======== ВНУТРЕННЕЕ ========

    #collect() {
        const chats = (this.dom.chats?.value || '')
            .split('\n').map(s => s.trim()).filter(Boolean);

        // режим "по длине" (если есть элементы в DOM и он включён)
        const lengthMode = !!this.dom.lengthMode?.checked;
        let useRegex = !!this.dom.regex?.checked;
        let keyword = (this.dom.keyword?.value || '').trim().slice(0, this.MAX_KEYWORD_LEN);

        if (lengthMode) {
            const L = Number(this.dom.lengthValue?.value) || 0;
            if (L < 1 || L > this.MAX_KEYWORD_LEN) {
                throw new Error(`Длина слова должна быть 1–${this.MAX_KEYWORD_LEN}`);
            }

            // Слово ровно L символов из [A-Za-z0-9_], без использования \b
            keyword = `(^|[^A-Za-z0-9_])[A-Za-z0-9_]{${L}}(?![A-Za-z0-9_])`;
            useRegex = true;

        }

        return {
            chats,
            keyword,
            caseSensitive: !!this.dom.case?.checked,
            useRegex
        };
    }

    #beginPolling() {
        this.#endPolling();
        this.pollHandle = setInterval(async () => {
            try {
                const p = await this.get('/progress'); // GET /api/search/progress
                const processed = Number(p?.processed || 0);
                const found = Number(p?.found || 0);
                const running = !!p?.running;

                this.setProgress(processed, found, !running);
                if (running) {
                    this.setStatus(`Поиск… Обработано: ${processed}, найдено: ${found}`, '#edf7ff', '#2c3e50');
                } else {
                    this.dom.bar?.classList.add('green');
                    this.setStatus(`Поиск завершён. Обработано: ${processed}, найдено: ${found}`, '#e7f6ec', '#27ae60');
                    this.#endPolling();
                }
            } catch (e) {
                this.setStatus('Ошибка опроса статуса: ' + e.message, '#ffecec', '#e74c3c');
                this.#endPolling();
            }
        }, 1000);
    }

    #endPolling() {
        if (this.pollHandle) {
            clearInterval(this.pollHandle);
            this.pollHandle = null;
        }
    }

    setStatus(text, bg, color) {
        if (!this.dom.status) return;
        this.dom.status.textContent = text;
        if (bg) this.dom.status.style.background = bg;
        if (color) this.dom.status.style.color = color;
    }

    setProgress(processed, found, complete) {
        if (this.dom.processedValue) this.dom.processedValue.textContent = String(processed);
        if (this.dom.foundValue) this.dom.foundValue.textContent = String(found);
        if (this.dom.bar) {
            // простой индикатор: 100% когда завершено, иначе пульс (0%)
            this.dom.bar.style.width = complete ? '100%' : '0%';
        }
    }

    #renderResults(list) {
        if (!this.dom.resultsBox) return;
        if (!Array.isArray(list) || list.length === 0) {
            this.dom.resultsBox.innerHTML = '<div class="muted">Нет данных</div>';
            return;
        }
        const esc = (s) => (s ?? '').toString()
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

        const items = list.map(r => `
      <div class="result-item">
        <div class="meta">#${esc(r.messageId)} • ${esc(r.chatTitle || '')} • ${esc(r.messageDate || '')}</div>
        <pre class="text">${esc(r.messageText || '')}</pre>
      </div>
    `).join('');
        this.dom.resultsBox.innerHTML = items;
    }

    saveState() {
        try {
            const s = {
                chats: (this.dom.chats?.value || ''),
                keyword: (this.dom.keyword?.value || ''),
                caseSensitive: !!this.dom.case?.checked,
                useRegex: !!this.dom.regex?.checked,
                lengthMode: !!this.dom.lengthMode?.checked,
                lengthValue: Number(this.dom.lengthValue?.value) || 0
            };
            localStorage.setItem(this.storageKey, JSON.stringify(s));
        } catch {
        }
    }

    restoreState() {
        try {
            const raw = localStorage.getItem(this.storageKey);
            if (!raw) return;
            const s = JSON.parse(raw);
            if (this.dom.chats) this.dom.chats.value = s.chats || '';
            if (this.dom.keyword) this.dom.keyword.value = (s.keyword || '').slice(0, this.MAX_KEYWORD_LEN);
            if (this.dom.case) this.dom.case.checked = !!s.caseSensitive;
            if (this.dom.regex) this.dom.regex.checked = !!s.useRegex;
            if (this.dom.lengthMode) this.dom.lengthMode.checked = !!s.lengthMode;
            if (this.dom.lengthValue) this.dom.lengthValue.value = String(s.lengthValue || 0);
        } catch {
        }
    }

    // helper
    #updateKwLen() {
        if (!this.dom.keyword || !this.dom.kwLenHint) return;
        const len = (this.dom.keyword.value || '').length;
        this.dom.kwLenHint.textContent = `${len}/${this.MAX_KEYWORD_LEN}`;
    }
}
