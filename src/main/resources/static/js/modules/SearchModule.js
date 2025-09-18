// /js/modules/SearchModule.js
import {ApiClient} from '../apiClient.js';
import {Notifier}  from '../components/Notifier.js';
import {Logger}    from '../components/Logger.js';
import { splitChats, normalizeChat } from '../utils.js';

/** Модуль «Поиск» для индексации и поиска сообщений. */
export class SearchModule extends ApiClient {
    constructor() {
        super('/api/search');
        this.notify = new Notifier(); // тосты
        this.log = new Logger();      // логгер

        // настройки
        this.MAX_KEYWORD_LEN = 50; // максимально допустимая длина ключа

        // сортировка/данные результатов
        this.resultsData = [];                          // подготовленные строки
        this.resultsDataRaw = [];                       // как пришло с сервера
        this.sort = { key: 'messageDate', dir: 'desc' };// текущая сортировка
        this.baselineFoundTotal = 0;                    // базовый found до старта

        this._lastNewCount = 0;

        this.dom = {
            container:      document.getElementById('searchContainer'),
            chats:          document.getElementById('searchChats'),
            keyword:        document.getElementById('searchKeyword'),
            case:           document.getElementById('caseSensitive'),
            wholeWord:      document.getElementById('wholeWord'),
            regex:          document.getElementById('regexMode'),
            lengthMode:     document.getElementById('lengthMode'),
            lengthValue:    document.getElementById('lengthValue'),
            kwLenHint:      document.getElementById('kwLenHint'),
            btnStart:       document.getElementById('startSearchBtn'),
            btnStop:        document.getElementById('stopSearchBtn'),
            btnLoadRes:     document.getElementById('loadSearchResultsBtn'),
            btnDelDb:       document.getElementById('deleteSearchDbBtn'),
            processedValue: document.getElementById('searchProcessedValue'),
            foundValue:     document.getElementById('searchFoundValue'),
            status:         document.getElementById('searchStatus'),
            bar:            document.getElementById('searchProgress-bar'),
            resultsChat:    document.getElementById('searchResultsChat'),
            resultsBox:     document.getElementById('searchResults'),
            presetBar:      document.querySelector('#searchContainer .preset-bar')
        };

        // добавить «новых: 0», если нет
        const statsRight = this.dom.container?.querySelector('.progress-label span:last-child');
        if (statsRight && !statsRight.querySelector('.num-new')) {
            const wrap = document.createElement('span');
            wrap.style.marginLeft = '6px';
            wrap.innerHTML = ', новых: <span class="num-new">0</span>';
            statsRight.appendChild(wrap);
            this.dom.newValue = wrap.querySelector('.num-new');
        } else {
            this.dom.newValue = statsRight?.querySelector('.num-new') || null;
        }

        // защита на maxlength
        if (this.dom.keyword && !this.dom.keyword.hasAttribute('maxlength')) {
            this.dom.keyword.setAttribute('maxlength', String(this.MAX_KEYWORD_LEN));
        }

        this.#bind();
        this.restoreState();
        this.#updateKwLen();
        this.setProgress(0, 0, false);
        this.setStatus('Ожидание…', '#e9f1ff', '#2c3e50');
    }

    get storageKey() { return 'tdtools.search.v1'; } // ключ хранилища

    // привязка событий
    #bind() {
        this.dom.btnStart?.addEventListener('click', (e) => { e.preventDefault(); this.start(); });
        this.dom.btnStop?.addEventListener('click', () => this.stop());
        this.dom.btnDelDb?.addEventListener('click', () => this.clearDb());
        this.dom.btnLoadRes?.addEventListener('click', () => this.loadResults());

        this.dom.keyword?.addEventListener('input', () => { this.#updateKwLen(); this.saveState(); });
        this.dom.chats?.addEventListener('input', () => this.saveState());
        this.dom.case?.addEventListener('change', () => this.saveState());
        this.dom.wholeWord?.addEventListener('change', () => this.saveState());
        this.dom.regex?.addEventListener('change', () => { this.#syncRegexWholeWordUi(); this.saveState(); });

        // режим «по длине»: синхронизация зависимостей
        const syncLengthUi = () => {
            const on = !!this.dom.lengthMode?.checked;
            if (this.dom.lengthValue) this.dom.lengthValue.disabled = !on;
            if (this.dom.keyword) this.dom.keyword.disabled = on;

            // при поиске по длине — включаем regex, отключаем wholeWord
            if (this.dom.regex) {
                this.dom.regex.checked = on ? true : this.dom.regex.checked;
                this.dom.regex.disabled = on;
            }
            if (this.dom.wholeWord) {
                this.dom.wholeWord.checked = on ? false : this.dom.wholeWord.checked;
                this.dom.wholeWord.disabled = on || !!this.dom.regex?.checked;
            }
        };
        this.dom.lengthMode?.addEventListener('change', () => { syncLengthUi(); this.saveState(); });
        this.dom.lengthValue?.addEventListener('input', () => this.saveState());
        syncLengthUi();

        // пресеты Regex
        this.dom.presetBar?.addEventListener('click', (e) => {
            const btn = e.target.closest('[data-preset-regex]');
            if (!btn) return;
            const patt = btn.getAttribute('data-preset-regex') || '';
            if (!patt) return;

            // подставляем паттерн, включаем regex, выключаем wholeWord/length
            if (this.dom.keyword) this.dom.keyword.value = String(patt).slice(0, this.MAX_KEYWORD_LEN);
            if (this.dom.regex) this.dom.regex.checked = true;
            if (this.dom.wholeWord) this.dom.wholeWord.checked = false;
            if (this.dom.lengthMode) this.dom.lengthMode.checked = false;

            // снять блокировки и обновить UI
            syncLengthUi();
            this.#syncRegexWholeWordUi();
            this.#updateKwLen();
            this.saveState();
            this.dom.keyword?.focus();
            this.notify.info(`Пресет: ${btn.textContent.trim()}`);
        });
    }

    // ===== публичные =====
    async start() {
        let req;
        try { req = this.#collect(); }
        catch (e) {
            this.setStatus('Ошибка: ' + e.message, '#ffecec', '#e74c3c');
            return;
        }

        if (!req.chat) {
            this.setStatus('Ошибка: не указан чат', '#ffecec', '#e74c3c');
            return;
        }
        if (!req.useRegex && req.keyword.length > this.MAX_KEYWORD_LEN) {
            // проверяет длину строки
            this.setStatus(`Ошибка: длина ключа не должна превышать ${this.MAX_KEYWORD_LEN} символов`, '#ffecec', '#e74c3c');
            return;
        }

        this.saveState();
        this.dom.bar?.classList.remove('green');
        // сбрасываем «новых» для нового запуска
        this._lastNewCount = 0;

        if (req.keyword.length === 0 && !req.useRegex) {
            this.setStatus('Пустой ключ: индексируем все непустые сообщения/подписи', '#edf7ff', '#2c3e50');
        } else {
            this.setStatus('Запуск поиска…', '#edf7ff', '#2c3e50');
        }
        this.setProgress(0, 0, false);

        try {
            this.setStatus('Готовим статистику…', '#edf7ff', '#2c3e50');
            this.baselineFoundTotal = await this.#computeBaselineTotal(req.chat);

            await this.post('/start', req);
            this.notify.info('Поиск запущен');
            this.setStatus('Поиск выполняется…', '#edf7ff', '#2c3e50');
            this.#beginPolling();
            this.log.info('Запущен поиск для чатов:', req.chats);
        } catch (e) {
            this.setStatus('Ошибка запуска: ' + e.message, '#ffecec', '#e74c3c');
        }
    }

    async stop() {
        this.setStatus('Останавливаем поиск…', '#fff4e6', '#e67e22');
        try {
            await this.post('/stop', {});
            this.#endPolling();
            this.setStatus('Поиск остановлен', '#fff4e6', '#e67e22');
        } catch (e) {
            this.setStatus('Ошибка остановки: ' + e.message, '#ffecec', '#e74c3c');
        }
    }

    async clearDb() {
        try {
            await this.del('/database');
            this.notify.ok('SEARCH-БД очищена');
            this.setStatus('База поиска очищена', '#e7f6ec', '#27ae60');
            if (this.dom.resultsBox) this.dom.resultsBox.innerHTML = '';
            this.resultsData = [];
            this.resultsDataRaw = [];
            this.baselineFoundTotal = 0;
            this._lastNewCount = 0;
            if (this.dom.newValue) this.dom.newValue.textContent = '0';
            this.setProgress(0, 0, false);
        } catch (e) {
            this.setStatus('Ошибка удаления БД: ' + e.message, '#ffecec', '#e74c3c');
        }
    }

    async loadResults() {
        const chat = normalizeChat(this.dom.resultsChat?.value || '');
        if (!chat) {
            this.setStatus('Укажите чат для показа результатов', '#fff4e6', '#e67e22');
            return;
        }
        try {
            const data = await this.get('/results?chat=' + encodeURIComponent(chat));
            this.#renderResults(Array.isArray(data) ? data : []);
            this.setStatus('Результаты загружены', '#e7f6ec', '#27ae60');
        } catch (e) {
            this.setStatus('Ошибка загрузки результатов: ' + e.message, '#ffecec', '#e74c3c');
        }
    }

    // ===== внутренние =====
    #collect() {
        const chat = normalizeChat(this.dom.chats?.value || '');
        const lengthMode = !!this.dom.lengthMode?.checked;
        let useRegex = !!this.dom.regex?.checked;
        let wholeWord = !!this.dom.wholeWord?.checked;
        let keyword = (this.dom.keyword?.value || '').trim().slice(0, this.MAX_KEYWORD_LEN);

        if (lengthMode) {
            const L = Number(this.dom.lengthValue?.value) || 0;
            if (L < 1 || L > 30) throw new Error(`Длина слова должна быть 1–30`);
            keyword = `(^|[^A-Za-z0-9_])[A-Za-z0-9_]{${L}}(?![A-Za-z0-9_])`;
            useRegex = true;
            wholeWord = false;
        }
        if (useRegex) wholeWord = false;

        return {
            chat,
            keyword,
            caseSensitive: !!this.dom.case?.checked,
            useRegex,
            wholeWord
        };
    }

    #beginPolling() {
        this.#endPolling();
        this._pollTimer = setInterval(async () => {
            try {
                const p = await this.get('/progress');
                const processed = Number(p?.processedMessages ?? p?.processed ?? 0);
                const found = Number(p?.foundMessages ?? p?.found ?? 0);
                const running = !!(p?.running ?? true);

                // верхняя строка статистики и прогресс-бар
                this.setProgress(processed, found, !running);

                // total — это база (что было до старта) + найденные в этом запуске
                const total = (this.baselineFoundTotal || 0) + found;

                if (running) {
                    // Пока идёт: «новых» == found
                    this._lastNewCount = found; // поддерживаем актуальным
                    this.setStatus(`Поиск… Обработано: ${processed}, найдено: ${total}, новых: ${found}`, '#edf7ff', '#2c3e50');
                } else {
                    // Завершено: фиксируем итоговое Z (новых) так же, как в нижнем сообщении
                    this._lastNewCount = found;
                    this.dom.bar?.classList.add('green');
                    this.setStatus(`Поиск завершён. Обработано: ${processed}, найдено: ${total}, новых: ${found}`, '#e7f6ec', '#27ae60');
                }
            } catch (e) {
                this.setStatus('Ошибка опроса статуса: ' + e.message, '#ffecec', '#e74c3c');
                this.#endPolling();
            }
        }, 1000);
    }
    #endPolling() { if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; } }

    setStatus(text, bg, color) {
        if (!this.dom.status) return;
        this.dom.status.textContent = text;
        if (bg) this.dom.status.style.background = bg;
        if (color) this.dom.status.style.color = color;
    }

    /** Верхняя «Статистика …»: processed / найдено: total, новых: fresh */
    setProgress(processed, newFound, complete) {
        const fresh = Number(newFound || 0);
        const total = (this.baselineFoundTotal || 0) + fresh;

        if (this.dom.processedValue) this.dom.processedValue.textContent = String(processed ?? 0);
        if (this.dom.foundValue)     this.dom.foundValue.textContent     = String(total);
        if (this.dom.newValue)       this.dom.newValue.textContent       = String(fresh);

        if (this.dom.bar) this.dom.bar.style.width = complete ? '100%' : '0%';
    }

    #renderResults(list) {
        if (!this.dom.resultsBox) return;
        this.resultsDataRaw = Array.isArray(list) ? list : [];

        if (!Array.isArray(list) || list.length === 0) {
            this.resultsData = [];
            this.dom.resultsBox.innerHTML = '<div class="muted">Нет данных</div>';
            return;
        }

        this.resultsData = list.map((r) => {
            const iso = (r.messageDate ?? '').toString();
            const {date, time, key} = this.#splitDateTime(iso);
            return {
                chatTitle: r.chatTitle ?? '',
                messageDate: iso,
                _dtKey: key,
                _date: date,
                _time: time,
                senderName: r.senderName ?? (r.senderId ?? ''),
                messageText: r.messageText ?? ''
            };
        });

        const rows = this.#sortedData();
        this.dom.resultsBox.innerHTML = this.#tableHtml(rows);
    }

    #sortedData() {
        const key = this.sort.key;
        const dir = this.sort.dir === 'asc' ? 1 : -1;
        const val = (row) => key === 'messageDate' ? (row._dtKey || '') : (row[key] ?? '').toString().toLowerCase();

        const arr = [...this.resultsData];
        arr.sort((a, b) => {
            const va = val(a), vb = val(b);
            if (va < vb) return -1 * dir;
            if (va > vb) return 1 * dir;
            return 0;
        });
        return arr;
    }

    #tableHtml(rows) {
        const esc = (s) => (s ?? '').toString().replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
        const arrow = this.sort.dir === 'asc' ? '▲' : '▼';
        const th = (label, key, extraCls = '') =>
            `<th data-key="${key}" class="sortable${this.sort.key === key ? ' active' : ''} ${extraCls}">
        ${label}${this.sort.key === key ? ` <span class="sort-indicator">${arrow}</span>` : ''}
       </th>`;

        const head = `
      <thead>
        <tr>
          <th class="ncol">№</th>
          ${th('Чат', 'chatTitle')}
          ${th('Дата', 'messageDate')}
          ${th('Время', 'messageDate')}
          ${th('Отправитель', 'senderName')}
          ${th('Сообщение', 'messageText')}
        </tr>
      </thead>`;

        const body = rows.map((r, idx) => {
            const msgHtml = esc(r.messageText).replace(/\n/g, '<br>');
            return `<tr>
        <td class="ncol">${idx + 1}</td>
        <td class="mono">${esc(r.chatTitle)}</td>
        <td>${esc(r._date)}</td>
        <td>${esc(r._time)}</td>
        <td>${esc(r.senderName)}</td>
        <td class="msg">${msgHtml}</td>
      </tr>`;
        }).join('');

        return `<div class="results-table-wrap">
      <table class="results-table" aria-label="Результаты поиска">
        ${head}
        <tbody>${body}</tbody>
      </table>
    </div>`;
    }

    #splitDateTime(iso) {
        if (!iso || typeof iso !== 'string' || !iso.includes('T')) {
            return {date: '', time: '', key: ''};
        }
        const [d, tRaw = ''] = iso.split('T');
        const [y, m, d2] = (d || '').split('-');
        const time = (tRaw || '').slice(0, 8);
        const date = (y && m && d2) ? `${d2}.${m}.${y}` : iso;
        const key = `${y || ''}${m || ''}${d2 || ''}${time.replace(/:/g, '')}`;
        return {date, time, key};
    }

    saveState() {
        try {
            const s = {
                chats: (this.dom.chats?.value || ''),
                keyword: (this.dom.keyword?.value || ''),
                caseSensitive: !!this.dom.case?.checked,
                useRegex: !!this.dom.regex?.checked,
                wholeWord: !!this.dom.wholeWord?.checked,
                lengthMode: !!this.dom.lengthMode?.checked,
                lengthValue: Number(this.dom.lengthValue?.value) || 0
            };
            localStorage.setItem(this.storageKey, JSON.stringify(s));
        } catch { /* noop */ }
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
            if (this.dom.wholeWord) this.dom.wholeWord.checked = !!s.wholeWord;
            if (this.dom.lengthMode) this.dom.lengthMode.checked = !!s.lengthMode;
            if (this.dom.lengthValue) this.dom.lengthValue.value = String(s.lengthValue || 0);
            this.#syncRegexWholeWordUi();
        } catch { /* noop */ }
    }

    #updateKwLen() {
        if (!this.dom.keyword || !this.dom.kwLenHint) return;
        const len = (this.dom.keyword.value || '').length;
        this.dom.kwLenHint.textContent = `${len}/${this.MAX_KEYWORD_LEN}`;
    }

    async #computeBaselineTotal(chat) {
        try {
            const arr = await this.get('/results?chat=' + encodeURIComponent(chat)).catch(() => []);
            return Array.isArray(arr) ? arr.length : 0;
        } catch { return 0; }
    }

    #syncRegexWholeWordUi() {
        const rx = !!this.dom.regex?.checked;
        if (this.dom.wholeWord) {
            if (rx) this.dom.wholeWord.checked = false;
            this.dom.wholeWord.disabled = rx || !!this.dom.lengthMode?.checked;
        }
    }
}
