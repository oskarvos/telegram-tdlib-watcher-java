import {ApiClient} from '../apiClient.js';
import {Notifier}  from '../components/Notifier.js';
import {Logger}    from '../components/Logger.js';
import { splitChats, normalizeChat } from '../utils.js';

/** Модуль «Мониторинг» для слежения за новыми сообщениями. */
export class MonitorModule extends ApiClient {
    constructor() {
        super('/api/monitor');
        this.notify = new Notifier(); // тосты
        this.log = new Logger();      // логгер
        this._timer = null;           // таймер опроса
        this.MAX_KEYWORD_LEN = 50;    // максимум символов ключа

        // DOM
        this.dom = {
            container:   document.getElementById('monitorContainer'),
            chats:       document.getElementById('monitorChats'),
            keyword:     document.getElementById('monitorKeyword'),
            case:        document.getElementById('monitorCaseSensitive'),
            regex:       document.getElementById('monitorRegexMode'),
            interval:    document.getElementById('monitorInterval'),
            presetBar:   document.querySelector('#monitorContainer .preset-bar'),
            btnStart:    document.getElementById('startMonitorBtn'),
            btnStop:     document.getElementById('stopMonitorBtn'),
            btnDelDb:    document.getElementById('deleteMonitorDbBtn'),
            processed:   document.getElementById('monitorProcessedValue'),
            found:       document.getElementById('monitorFoundValue'),
            bar:         document.getElementById('monitorProgress-bar'),
            status:      document.getElementById('monitorStatus'),
            resultsChat: document.getElementById('monitorResultsChat'),
            btnLoadRes:  document.getElementById('loadMonitorResultsBtn'),
            resultsBox:  document.getElementById('monitorResults')
        };

        this.#bind();
        this.restoreState();

        // safety: maxlength
        if (this.dom.keyword && !this.dom.keyword.hasAttribute('maxlength')) {
            this.dom.keyword.setAttribute('maxlength', String(this.MAX_KEYWORD_LEN));
        }
    }

    get storageKey() { return 'td.monitor.state'; } // ключ хранилища

    // привязка обработчиков
    #bind() {
        this.dom.btnStart.addEventListener('click', () => this.start());
        this.dom.btnStop.addEventListener('click',  () => this.stop());
        this.dom.btnDelDb.addEventListener('click', () => this.clearDb());
        this.dom.btnLoadRes.addEventListener('click', () => this.loadResults());

        // пресеты Regex
        this.dom.presetBar?.addEventListener('click', (e) => {
            const btn = e.target.closest('[data-preset-regex]');
            if (!btn) return;
            const patt = btn.getAttribute('data-preset-regex') || '';
            if (!patt) return;
            if (this.dom.keyword) {
                this.dom.keyword.value = String(patt).slice(0, this.MAX_KEYWORD_LEN);
                this.dom.keyword.setAttribute('maxlength', String(this.MAX_KEYWORD_LEN));
            }
            if (this.dom.regex) this.dom.regex.checked = true;
            this.notify.info(`Пресет: ${btn.textContent.trim()}`);
            this.saveState();
            this.dom.keyword?.focus();
        });

        // Persistence
        [this.dom.chats, this.dom.keyword].forEach(el => el.addEventListener('input', () => this.saveState()));
        [this.dom.case, this.dom.regex, this.dom.interval].forEach(el => el.addEventListener('change', () => this.saveState()));
    }

    // ===== состояние =====
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
            this.dom.keyword.value = (s.keyword || '').slice(0, this.MAX_KEYWORD_LEN);
            this.dom.case.checked = !!s.caseSensitive;
            this.dom.regex.checked = !!s.useRegex;
            this.dom.interval.value = s.pollInterval || '1m';
        } catch {/* ignore */}
    }

    // сбор формы
    #collect() {
        const chats = splitChats(this.dom.chats.value || '');
        return {
            chats,
            keyword: (this.dom.keyword.value || '').trim().slice(0, this.MAX_KEYWORD_LEN),
            caseSensitive: this.dom.case.checked,
            useRegex: this.dom.regex.checked,
            pollInterval: this.dom.interval.value
        };
    }

    // запуск процесса мониторинга
    async start() {
        const req = this.#collect();
        if (!req.chats.length) { this.#setStatus('Ошибка: не указаны чаты', '#ffecec', '#e74c3c'); return; }
        if (!req.keyword)      { this.#setStatus('Ошибка: не указано ключевое слово', '#ffecec', '#e74c3c'); return; }

        this.saveState();
        this.dom.bar.classList.remove('green');
        this.#setStatus('Старт мониторинга...', '#edf7ff', '#2c3e50');
        this.#setProgress(0, 0, false);

        try {
            await this.post('/start', req);
            this.notify.info('Мониторинг запущен');
            this.#setStatus('Мониторинг выполняется...', '#edf7ff', '#2c3e50');
            this.#beginPolling();
            this.log.info('Запущен мониторинг для чатов:', req.chats);
        } catch (e) {
            this.#setStatus('Ошибка запуска: ' + e.message, '#ffecec', '#e74c3c');
            this.notify.error(e.message);
        }
    }

    // остановка
    async stop() {
        this.#setStatus('Останавливаем мониторинг...', '#fff4e6', '#e67e22');
        try {
            await this.post('/stop', {});
            this.#endPolling();
            this.#setStatus('Мониторинг остановлен', '#ffecec', '#e74c3c');
        } catch (e) {
            this.#setStatus('Ошибка: ' + e.message, '#ffecec', '#e74c3c');
        }
    }

    // очистка БД
    async clearDb() {
        try {
            await this.del('/database');
            this.notify.ok('MONITOR-БД очищена, чекпоинты сброшены');
            this.#setStatus('База мониторинга очищена', '#e7f6ec', '#27ae60');
            this.dom.resultsBox.innerHTML = '';
        } catch (e) {
            this.#setStatus('Ошибка удаления БД: ' + e.message, '#ffecec', '#e74c3c');
        }
    }

    // загрузка результатов
    async loadResults() {
        const chat = normalizeChat(this.dom.resultsChat.value || '');
        if (!chat) return;
        try {
            const list = await this.get(`/results?chat=${encodeURIComponent(chat)}&limit=200`);
            this.renderResults(list);
        } catch (e) {
            this.notify.error(e.message);
        }
    }

    // ===== опрос =====
    #beginPolling() {
        this.#endPolling();
        this.#tick();
        this._timer = setInterval(() => this.#tick(), 1000);
    }
    #endPolling() { if (this._timer) { clearInterval(this._timer); this._timer = null; } }

    async #tick() {
        try {
            const p = await this.get('/progress');
            const processed = p?.processedMessages || 0;
            const found = p?.foundMessages || 0;
            this.#setProgress(processed, found, !p?.running);
            if (p?.running) {
                this.#setStatus(`Мониторинг... Обработано: ${processed}, Найдено: ${found}`, '#edf7ff', '#2c3e50');
            } else {
                this.#setStatus('Мониторинг завершён', '#e7f6ec', '#27ae60');
                this.#endPolling();
            }
        } catch (e) {
            this.#setStatus('Ошибка запроса прогресса: ' + e.message, '#ffecec', '#e74c3c');
            this.#endPolling();
        }
    }

    // ===== отрисовка =====
    #setProgress(processed, found, complete = false) {
        this.dom.processed.textContent = processed;
        this.dom.found.textContent = found;
        this.dom.bar.classList.toggle('green', !!complete);
    }
    #setStatus(text, bg, color) {
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

        const escapeHtml = s => String(s ?? '').replace(/[&<>"']/g,
            m => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]));

        const fmtParts = iso => {
            try {
                const d = new Date(iso);
                if (!isNaN(d)) {
                    const pad = n => String(n).padStart(2, '0');
                    const dd = pad(d.getDate()), mm = pad(d.getMonth()+1), yyyy = d.getFullYear();
                    const hh = pad(d.getHours()), mi = pad(d.getMinutes()), ss = pad(d.getSeconds());
                    return {date: `${dd}.${mm}.${yyyy}`, time: `${hh}:${mi}:${ss}`};
                }
            } catch {}
            return {date: escapeHtml(iso || ''), time: ''};
        };

        const rows = list.map((r, i) => {
            const p = fmtParts(r.messageDate);
            const sender = escapeHtml(r.senderName || r.senderId || '');
            const msg = escapeHtml(String(r.messageText || '')).slice(0, 2000);
            return `
<tr>
  <td class="num">${i + 1}</td>
  <td class="chat">${escapeHtml(r.chatTitle || '')}</td>
  <td class="date">${p.date}</td>
  <td class="time">${p.time}</td>
  <td class="from">${sender}</td>
  <td class="text">${msg}</td>
</tr>`;
        }).join('');

        box.innerHTML = `
<div class="table-wrapper">
  <table class="results-table">
    <thead>
      <tr>
        <th style="width:56px;">№</th>
        <th>Чат</th>
        <th style="width:130px;">Дата &#9662;</th>
        <th style="width:110px;">Время &#9662;</th>
        <th style="width:180px;">Отправитель</th>
        <th>Сообщение</th>
      </tr>
    </thead>
    <tbody>${rows}</tbody>
  </table>
</div>`;
    }
}
