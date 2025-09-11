import {ApiClient} from '../apiClient.js';
import {Notifier} from '../components/Notifier.js';

export class SearchModule extends ApiClient {
    constructor() {
        super('/api/search');
        this.notify = new Notifier();

        // Хранилище и ограничения
        this.MAX_KEYWORD_LEN = 30;
        this.storageKey = 'tdtools.search.v1';
        this.baselineFoundTotal = 0;

        // Данные таблицы и состояние сортировки
        this.resultsData = [];
        this.sort = {key: 'messageDate', dir: 'desc'}; // по умолчанию — новые сверху

        // Ссылки на DOM
        this.dom = {
            container: document.getElementById('searchContainer'),
            chats: document.getElementById('searchChats'),
            keyword: document.getElementById('searchKeyword'),
            case: document.getElementById('caseSensitive'),
            regex: document.getElementById('regexMode'),
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
            bar: document.getElementById('searchProgress-bar'),

            resultsChat: document.getElementById('searchResultsChat'),
            resultsBox: document.getElementById('searchResults')
        };

        // В строку "Статистика ..." добавим кусочек ", новых: 0"
        const statsRight = this.dom.container?.querySelector('.progress-label span:last-child');
        if (statsRight) {
            const wrap = document.createElement('span');
            wrap.style.marginLeft = '6px';
            wrap.innerHTML = ', новых: <span class="num-new">0</span>';
            statsRight.appendChild(wrap);
            this.dom.newValue = wrap.querySelector('.num-new');
        }


        // safety: maxlength
        if (this.dom.keyword && !this.dom.keyword.hasAttribute('maxlength')) {
            this.dom.keyword.setAttribute('maxlength', String(this.MAX_KEYWORD_LEN));
        }

        // Слушатели
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

        // Режим «по длине»
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

        // Делегирование кликов по заголовкам таблицы для сортировки
        this.dom.resultsBox?.addEventListener('click', (e) => {
            const th = e.target.closest('th[data-key]');
            if (!th) return;
            const key = th.dataset.key;

            // Дата и Время используют единый сорт-ключ messageDate
            const newKey = key;

            if (this.sort.key === newKey) {
                this.sort.dir = this.sort.dir === 'asc' ? 'desc' : 'asc';
            } else {
                this.sort.key = newKey;
                this.sort.dir = newKey === 'messageDate' ? 'desc' : 'asc';
            }
            this.#renderResults(this.resultsDataRaw || []); // перерисуем из исходных данных
        });

        // Инициализация
        this.restoreState();
        this.#updateKwLen();
        this.setProgress(0, 0, false);
        this.setStatus('Ожидание…', '#e9f1ff', '#2c3e50');
    }

    // ================= Публичные методы =================

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
            // Считаем, сколько уже есть результатов в БД по всем выбранным чатам
            this.setStatus('Готовим статистику…', '#edf7ff', '#2c3e50');
            this.baselineFoundTotal = await this.#computeBaselineTotal(req.chats);

            await this.post('/start', req);
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
            if (this.dom.newValue) this.dom.newValue.textContent = '0';
            this.setProgress(0, 0, false);
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
            const data = await this.get('/results?chat=' + encodeURIComponent(chat));
            this.#renderResults(Array.isArray(data) ? data : []);
            this.setStatus('Результаты загружены', '#e7f6ec', '#27ae60');
        } catch (e) {
            this.setStatus('Ошибка загрузки результатов: ' + e.message, '#ffecec', '#e74c3c');
        }
    }

    // ================= Внутренние =================

    #collect() {
        const chats = (this.dom.chats?.value || '')
            .split('\n').map(s => s.trim()).filter(Boolean);

        const lengthMode = !!this.dom.lengthMode?.checked;
        let useRegex = !!this.dom.regex?.checked;
        let keyword = (this.dom.keyword?.value || '').trim().slice(0, this.MAX_KEYWORD_LEN);

        if (lengthMode) {
            const L = Number(this.dom.lengthValue?.value) || 0;
            if (L < 1 || L > this.MAX_KEYWORD_LEN) {
                throw new Error(`Длина слова должна быть 1–${this.MAX_KEYWORD_LEN}`);
            }
            // Ровно L символов [A-Za-z0-9_], без \b
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
        this._pollTimer = setInterval(async () => {
            try {
                const p = await this.get('/progress');
                const processed = Number(p?.processedMessages ?? p?.processed ?? 0);
                const found = Number(p?.foundMessages ?? p?.found ?? 0);
                const running = !!(p?.running ?? true);

                this.setProgress(processed, found, !running);
                if (running) {
                    const total = (this.baselineFoundTotal || 0) + found;
                    this.setStatus(`Поиск… Обработано: ${processed}, найдено: ${total}, новых: ${found}`, '#edf7ff', '#2c3e50');
                } else {
                    const total = (this.baselineFoundTotal || 0) + found;
                    this.dom.bar?.classList.add('green');
                    this.setStatus(`Поиск завершён. Обработано: ${processed}, найдено: ${total}, новых: ${found}`, '#e7f6ec', '#27ae60');
                }
            } catch (e) {
                this.setStatus('Ошибка опроса статуса: ' + e.message, '#ffecec', '#e74c3c');
                this.#endPolling();
            }
        }, 1000);
    }

    #endPolling() {
        if (this._pollTimer) {
            clearInterval(this._pollTimer);
            this._pollTimer = null;
        }
    }

    setStatus(text, bg, color) {
        if (!this.dom.status) return;
        this.dom.status.textContent = text;
        if (bg) this.dom.status.style.background = bg;
        if (color) this.dom.status.style.color = color;
    }

    setProgress(processed, newFound, complete) {
        if (this.dom.processedValue) this.dom.processedValue.textContent = String(processed);
        const total = (this.baselineFoundTotal || 0) + (newFound || 0);
        if (this.dom.foundValue) this.dom.foundValue.textContent = String(total);
        if (this.dom.newValue) this.dom.newValue.textContent = String(newFound || 0);
        if (this.dom.bar) this.dom.bar.style.width = complete ? '100%' : '0%';
    }

    // ===== Таблица результатов (нумерация + сортировка) =====
    #renderResults(list) {
        if (!this.dom.resultsBox) return;

        // Сырой массив запомним, чтобы сортировать без потерь
        this.resultsDataRaw = Array.isArray(list) ? list : [];

        if (!Array.isArray(list) || list.length === 0) {
            this.resultsData = [];
            this.dom.resultsBox.innerHTML = '<div class="muted">Нет данных</div>';
            return;
        }

        // Нормализуем поля
        this.resultsData = list.map((r) => {
            const iso = (r.messageDate ?? '').toString();
            const {date, time, key} = this.#splitDateTime(iso);
            return {
                chatTitle: r.chatTitle ?? '',
                messageDate: iso,     // исходная ISO-строка LocalDateTime
                _dtKey: key,          // yyyyMMddHHmmss — ключ сортировки
                _date: date,          // 31.12.2025
                _time: time,          // 23:59:59
                senderName: r.senderName ?? (r.senderId ?? ''),
                messageText: r.messageText ?? ''
            };
        });

        // Отрисуем
        const rows = this.#sortedData();
        this.dom.resultsBox.innerHTML = this.#tableHtml(rows);
    }

    #sortedData() {
        const key = this.sort.key; // 'chatTitle' | 'messageDate' | 'senderName' | 'messageText'
        const dir = this.sort.dir === 'asc' ? 1 : -1;

        const val = (row) => {
            if (key === 'messageDate') return row._dtKey || '';
            return (row[key] ?? '').toString().toLowerCase();
        };

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
        const esc = (s) => (s ?? '').toString()
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

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
        // ожидаем LocalDateTime.toString(): 'YYYY-MM-DDTHH:mm:SS[.nnn]'
        if (!iso || typeof iso !== 'string' || !iso.includes('T')) {
            return {date: '', time: '', key: ''};
        }
        const [d, tRaw = ''] = iso.split('T');
        const [y, m, d2] = (d || '').split('-');
        const time = (tRaw || '').slice(0, 8);
        const date = (y && m && d2) ? `${d2}.${m}.${y}` : iso;
        const key = `${y || ''}${m || ''}${d2 || ''}${time.replace(/:/g, '')}`; // yyyymmddHHMMSS
        return {date, time, key};
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

    #updateKwLen() {
        if (!this.dom.keyword || !this.dom.kwLenHint) return;
        const len = (this.dom.keyword.value || '').length;
        this.dom.kwLenHint.textContent = `${len}/${this.MAX_KEYWORD_LEN}`;
    }

    async #computeBaselineTotal(chats) {
        try {
            const lists = await Promise.all(
                chats.map(c =>
                    this.get('/results?chat=' + encodeURIComponent(c)).catch(() => [])
                )
            );
            return lists.reduce((sum, arr) => sum + (Array.isArray(arr) ? arr.length : 0), 0);
        } catch {
            return 0;
        }
    }

}
