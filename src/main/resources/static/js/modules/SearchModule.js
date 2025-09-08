import {ApiClient} from '../apiClient.js';
import {Notifier} from '../components/Notifier.js';

export class SearchModule extends ApiClient {
    constructor() {
        super('/api/search');
        this.notify = new Notifier();
        this.pollInterval = null;

        // Константа длины ключа
        this.MAX_KEYWORD_LEN = 30;

        this.dom = {
            container: document.getElementById('searchContainer'),
            chats: document.getElementById('searchChats'),
            keyword: document.getElementById('searchKeyword'),
            case: document.getElementById('caseSensitive'),
            regex: document.getElementById('regexMode'),
            kwLenHint: document.getElementById('kwLenHint'),

            btnStart: document.getElementById('startSearchBtn'),
            btnStop: document.getElementById('stopSearchBtn'),
            btnDelDb: document.getElementById('deleteSearchDbBtn'),

            processed: document.getElementById('searchProcessedValue'),
            found: document.getElementById('searchFoundValue'),
            bar: document.getElementById('searchProgress-bar'),
            status: document.getElementById('searchStatus'),

            resultsChat: document.getElementById('searchResultsChat'),
            btnLoadRes: document.getElementById('loadSearchResultsBtn'),
            resultsBox: document.getElementById('searchResults'),
            lengthMode: document.getElementById('lengthMode'),
            lengthValue: document.getElementById('lengthValue'),
        };

        // Ограничим поле ввода и сразу подключим счетчик
        if (this.dom.keyword && !this.dom.keyword.hasAttribute('maxlength')) {
            this.dom.keyword.setAttribute('maxlength', String(this.MAX_KEYWORD_LEN));
        }

        this.dom.btnStart.addEventListener('click', () => this.start());
        this.dom.btnStop.addEventListener('click', () => this.stop());
        this.dom.btnDelDb.addEventListener('click', () => this.clearDb());
        this.dom.btnLoadRes.addEventListener('click', () => this.loadResults());

        // Сохранение состояния при изменении длины
        [this.dom.lengthMode, this.dom.lengthValue]
            .filter(Boolean)
            .forEach(el => el.addEventListener('input', () => this.saveState()));

// Переключение режима длины: включаем regex и блокируем поле ключа
        const syncLengthUi = () => {
            const on = !!this.dom.lengthMode?.checked;
            if (this.dom.lengthValue) this.dom.lengthValue.disabled = !on;
            if (this.dom.keyword) this.dom.keyword.disabled = on;
            if (this.dom.regex) {
                this.dom.regex.checked = on ? true : this.dom.regex.checked;
                this.dom.regex.disabled = on; // в режиме длины всегда regex
            }
        };
        this.dom.lengthMode?.addEventListener('change', syncLengthUi);
        syncLengthUi();


        // Persistence
        [this.dom.chats, this.dom.keyword]
            .forEach(el => el.addEventListener('input', () => {
                this.#updateKwLen();
                this.saveState();
            }));
        [this.dom.case, this.dom.regex]
            .forEach(el => el.addEventListener('change', () => this.saveState()));

        this.restoreState();
        this.#updateKwLen(); // первичная отрисовка счетчика
    }

    get storageKey() {
        return 'td.search.state';
    }

    saveState() {
        const s = this.#collect();
        localStorage.setItem(this.storageKey, JSON.stringify(s));
    }

    restoreState() {
        try {
            const raw = localStorage.getItem(this.storageKey);
            if (!raw) return;
            const s = JSON.parse(raw);
            this.dom.chats.value = (s.chats || []).join('\n');
            // Обрежем сохранённый ключ, если вдруг раньше сохранилось больше 30
            const kw = (s.keyword || '').slice(0, this.MAX_KEYWORD_LEN);
            this.dom.keyword.value = kw;
            this.dom.case.checked = !!s.caseSensitive;
            this.dom.regex.checked = !!s.useRegex;
        } catch {
            // ignore
        }
    }

    #collect() {
        const chats = (this.dom.chats.value || '')
            .split('\n')
            .map(s => s.trim())
            .filter(Boolean);

        let useRegex = !!this.dom.regex.checked;
        let keyword = (this.dom.keyword.value || '').slice(0, this.MAX_KEYWORD_LEN).trim();

        // Режим "по длине": строим Unicode-дружелюбный паттерн слова ровно N символов
        if (this.dom.lengthMode?.checked) {
            const L = Number(this.dom.lengthValue?.value) || 0;
            if (L < 1 || L > this.MAX_KEYWORD_LEN) {
                throw new Error(`Длина слова должна быть 1–${this.MAX_KEYWORD_LEN}`);
            }
            // слово = последовательность букв/цифр/подчёркивания ровно L, с «границами слова» для Unicode
            // (?<![\p{L}\p{N}_])  …  (?![\p{L}\p{N}_])
            keyword = `(?<![\\p{L}\\p{N}_])[\\p{L}\\p{N}_]{${L}}(?![\\p{L}\\p{N}_])`;
            useRegex = true; // принудительно
        }

        return {
            chats,
            keyword,
            caseSensitive: !!this.dom.case.checked,
            useRegex
        };
    }


    async start() {
        let req;
        try {
            req = this.#collect(); // собираем данные с формы
        } catch (e) {
            // Ошибка валидации (например, длина вне диапазона)
            return this.setStatus('Ошибка: ' + e.message, '#ffecec', '#e74c3c');
        }

        // Проверка: чаты не заданы
        if (!req.chats.length) {
            return this.setStatus('Ошибка: не указаны чаты', '#ffecec', '#e74c3c');
        }

        // Проверка: ключ слишком длинный (если это не regex)
        if (req.keyword.length > this.MAX_KEYWORD_LEN && !req.useRegex) {
            return this.setStatus(
                `Ошибка: длина ключа не должна превышать ${this.MAX_KEYWORD_LEN} символов`,
                '#ffecec',
                '#e74c3c'
            );
        }

        // Отображаем «Ожидание…» и блокируем кнопку
        this.setStatus('Ожидание…', '#eaf2ff', '#3498db');
        this.dom.start.disabled = true;

        try {
            // Отправляем POST на бэкенд
            const resp = await fetch('/api/search/start', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(req)
            });

            if (!resp.ok) {
                throw new Error('HTTP ' + resp.status);
            }

            const data = await resp.json();
            if (data.error) {
                throw new Error(data.error);
            }

            // Успешный запуск поиска
            this.setStatus('Поиск запущен', '#eaffea', '#27ae60');
            this.saveState();
        } catch (err) {
            // Ошибка сетевого или серверного уровня
            this.setStatus('Ошибка запуска: ' + err.message, '#ffecec', '#e74c3c');
        } finally {
            this.dom.start.disabled = false;
        }
    }


    async stop() {
        this.setStatus('Останавливаем поиск...', '#fff4e6', '#e67e22');
        try {
            await this.post('/stop', {});
            this.#endPolling();
            this.setStatus('Поиск остановлен', '#ffecec', '#e74c3c');
        } catch (e) {
            this.setStatus('Ошибка: ' + e.message, '#ffecec', '#e74c3c');
        }
    }

    async clearDb() {
        try {
            await this.del('/database');
            this.notify.ok('SEARCH-БД очищена');
            this.setStatus('База поиска очищена', '#e7f6ec', '#27ae60');
            this.dom.resultsBox.innerHTML = '';
        } catch (e) {
            this.setStatus('Ошибка удаления БД: ' + e.message, '#ffecec', '#e74c3c');
        }
    }

    async loadResults() {
        const chat = (this.dom.resultsChat.value || '').trim();
        if (!chat) return;
        try {
            const list = await this.get(`/results?chat=${encodeURIComponent(chat)}`);
            this.renderResults(list);
        } catch (e) {
            this.notify.error(e.message);
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
            const p = await this.get('/progress');
            const processed = p?.processedMessages || 0;
            const found = p?.foundMessages || 0;
            this.setProgress(processed, found, !p?.running);
            if (p?.running) {
                this.setStatus(`Поиск... Обработано: ${processed}, Найдено: ${found}`, '#edf7ff', '#2c3e50');
            } else {
                this.setStatus('Поиск завершён', '#e7f6ec', '#27ae60');
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

    renderResults(list) {
        const box = this.dom.resultsBox;
        if (!Array.isArray(list) || !list.length) {
            box.innerHTML = '<div style="padding:10px;color:#666;">Пусто</div>';
            return;
        }
        const escapeHtml = s => String(s || '').replace(/[&<>"']/g, m => ({
            '&': '&amp;',
            '<': '&lt;',
            '>': '&gt;',
            '"': '&quot;',
            '\'': '&#39;'
        }[m]));
        box.innerHTML = list.map(r => `
<div class="result-item">
  <div style="font-weight:700;margin-bottom:6px;">${escapeHtml(r.chatTitle || '')} • msg ${r.messageId}</div>
  <div style="white-space:pre-wrap;">${escapeHtml(r.messageText || '')}</div>
  <div class="search-stats">
    <div>${escapeHtml(r.keyword || '')}</div>
    <div>${escapeHtml(r.messageDate || '')}</div>
  </div>
</div>`).join('');
    }

    // --- private helpers ---

    #updateKwLen() {
        if (!this.dom.keyword) return;
        let val = this.dom.keyword.value || '';
        if (val.length > this.MAX_KEYWORD_LEN) {
            this.dom.keyword.value = val.slice(0, this.MAX_KEYWORD_LEN);
            val = this.dom.keyword.value;
        }
        if (this.dom.kwLenHint) {
            this.dom.kwLenHint.textContent = `${val.length}/${this.MAX_KEYWORD_LEN}`;
        }
    }

}

(function () {
    const MAX = 30;

    function init() {
        const kwInput = document.getElementById('searchKeyword');
        const hint = document.getElementById('kwLenHint');
        const startBtn = document.getElementById('startSearchBtn');
        if (!kwInput || !hint || !startBtn) return;

        function refresh() {
            const len = kwInput.value.length;
            hint.textContent = `${len}/${MAX}`;
            // Пустое значение (0) разрешено, >30 — блокируем кнопку и показываем нативную ошибку
            const ok = len <= MAX;
            kwInput.setCustomValidity(ok ? '' : `Ключевое слово должно быть длиной 0–${MAX} символов`);
            startBtn.disabled = !ok;
        }

        kwInput.addEventListener('input', refresh);
        kwInput.addEventListener('change', refresh);
        refresh();

        // Перехватываем клик, чтобы не улетел запрос при невалидном поле
        startBtn.addEventListener('click', (e) => {
            if (!kwInput.reportValidity()) {
                e.stopImmediatePropagation?.();
                e.preventDefault();
            }
        }, true); // capture: сработает раньше других обработчиков
    }

    if (document.readyState !== 'loading') init();
    else document.addEventListener('DOMContentLoaded', init);
})();
