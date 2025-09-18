import {ApiClient} from '../apiClient.js';
import {Notifier} from '../components/Notifier.js';
import {Logger} from '../components/Logger.js';
import {splitChats} from '../utils.js';


/** Модуль «Дамп» для выгрузки контента из чатов. */
export class DumpModule extends ApiClient {
    constructor() {
        super('/api/dump');
        this.notify = new Notifier(); // тосты
        this.log = new Logger();      // логгер
        this._timer = null;           // таймер опроса
        this._lastRequest = null;     // последний запрос — для сводки

        // DOM-ссылки
        this.dom = {
            container: document.getElementById('dumpContainer'),
            chkTextDocs: document.getElementById('textDocuments'),
            extBlock: document.querySelector('.text-document-extensions'),
            extInput: document.getElementById('textExtensions'),

            chats: document.getElementById('chats'),
            photos: document.getElementById('photos'),
            videos: document.getElementById('videos'),
            links: document.getElementById('links'),
            messages: document.getElementById('messages'),
            audio: document.getElementById('audio'),

            btnStart: document.getElementById('startBtn'),
            btnStop: document.getElementById('stopBtn'),
            btnDelDb: document.getElementById('deleteDumpDbBtn'),

            bar: document.getElementById('progress-bar'),
            label: document.getElementById('progress-value'),
            status: document.getElementById('status'),
        };

        this.#bind();
        this.restoreState();
        this.#toggleTextDocumentExtensions();
    }

    // ключ хранилища
    get storageKey() {
        return 'td.dump.state.v2';
    }

    // привязка обработчиков
    #bind() {
        this.dom.chkTextDocs.addEventListener('change', () => {
            this.#toggleTextDocumentExtensions();
            this.saveState();
        });
        this.dom.btnStart.addEventListener('click', () => this.start());
        this.dom.btnStop.addEventListener('click', () => this.stop());
        this.dom.btnDelDb?.addEventListener('click', () => this.clearDbAndFiles());

        [this.dom.chats, this.dom.extInput].forEach(el => el.addEventListener('input', () => this.saveState()));
        [this.dom.photos, this.dom.videos, this.dom.links, this.dom.messages, this.dom.audio, this.dom.chkTextDocs]
            .forEach(el => el.addEventListener('change', () => this.saveState()));
    }

    // показать/скрыть расширения
    #toggleTextDocumentExtensions() {
        const visible = this.dom.chkTextDocs.checked;
        this.dom.extBlock.classList.toggle('hidden', !visible);
    }

    // собрать форму
    #collect() {
        const chats = splitChats(this.dom.chats.value || '');
        return {
            chats,
            photos: this.dom.photos.checked,
            videos: this.dom.videos.checked,
            links: this.dom.links.checked,
            messages: this.dom.messages.checked,
            textDocuments: this.dom.chkTextDocs.checked,
            audio: this.dom.audio.checked,
            textDocumentExtensions: (this.dom.extInput.value || '').trim() || null
        };
    }

    // запуск процесса дампа по указанным параметрам
    async start() {
        const req = this.#collect();
        if (!req.chats.length) {
            this.#setStatus('Ошибка: не указаны чаты', '#ffecec', '#e74c3c');
            return;
        }
        if (!req.photos && !req.videos && !req.links && !req.messages && !req.textDocuments && !req.audio) {
            this.#setStatus('Ошибка: не выбран ни один тип контента', '#ffecec', '#e74c3c');
            return;
        }

        this._lastRequest = req; // сохраняем для итоговой сводки
        this.saveState();
        this.dom.bar.classList.remove('green');
        this.#setStatus('Запуск дампа...', '#edf7ff', '#2c3e50');
        this.#setProgress(0);

        try {
            await this.post('/start', req);
            this.notify.info('Дамп запущен');
            this.#setStatus('Обработка...', '#edf7ff', '#2c3e50');
            this.#beginPolling();
            this.log.info('Запущен дамп для чатов:', req.chats);
        } catch (e) {
            this.#setStatus('Ошибка запуска: ' + e.message, '#ffecec', '#e74c3c');
            this.notify.error(e.message);
            this.log.error('Ошибка старта дампа:', e.message);
        }
    }

    // остановка
    async stop() {
        this.#setStatus('Останавливаем...', '#fff4e6', '#e67e22');
        try {
            await this.post('/stop', {});
            this.#setStatus('Жду завершения фонового потока...', '#fff4e6', '#e67e22');
            if (!this._timer) this.#beginPolling();
        } catch (e) {
            this.#setStatus('Ошибка: ' + e.message, '#ffecec', '#e74c3c');
        }
    }

    // очистка БД + файлов
    async clearDbAndFiles() {
        this.#setStatus('Удаляем DUMP-БД и файлы...', '#fff4e6', '#e67e22');
        try {
            await this.del('/database');
            this.#setStatus('DUMP-БД очищены, файлы удалены', '#e7f6ec', '#27ae60');
            this.notify.ok('DUMP-БД очищены, файлы удалены');
            this.#setProgress(0, true);
        } catch (e) {
            this.#setStatus('Ошибка удаления: ' + e.message, '#ffecec', '#e74c3c');
            this.notify.error(e.message);
        }
    }

    // ===== опрос прогресса =====
    #beginPolling() {
        this.#endPolling();
        this.#pollOnce();
        this._timer = setInterval(() => this.#pollOnce(), 1000);
    }

    #endPolling() {
        if (this._timer) {
            clearInterval(this._timer);
            this._timer = null;
        }
    }

    async #pollOnce() {
        try {
            const p = await this.get('/progress');
            const processed = Math.max(0, p?.processed || 0);
            const running = !!p?.running;

            this.#setProgress(processed, !running);
            if (running) {
                this.#setStatus(`Обработано сообщений: ${processed}`, '#edf7ff', '#2c3e50');
            } else {
                this.#renderSummary(p);
                this.dom.bar.classList.add('green');
                this.#endPolling();
            }
        } catch (e) {
            this.#setStatus('Ошибка запроса прогресса: ' + e.message, '#ffecec', '#e74c3c');
            this.#endPolling();
        }
    }

    // сводка по завершении
    #renderSummary(p) {
        const req = this._lastRequest || {};
        const rows = [];
        rows.push(`<div>Обработано сообщений: <b>${p?.processed ?? 0}</b></div>`);
        if (req.messages) rows.push(`<div>Сообщения: <b>${p?.savedMessages ?? 0}</b></div>`);
        if (req.photos) rows.push(`<div>Фото: <b>${p?.savedPhotos ?? 0}</b></div>`);
        if (req.videos) rows.push(`<div>Видео: <b>${p?.savedVideos ?? 0}</b></div>`);
        if (req.audio) rows.push(`<div>Аудио: <b>${p?.savedAudio ?? 0}</b></div>`);
        if (req.links) rows.push(`<div>Ссылки: <b>${p?.savedLinks ?? 0}</b></div>`);

        if (req.textDocuments) {
            const totalDocs = p?.savedDocuments ?? 0;
            rows.push(`<div>Текстовые документы: <b>${totalDocs}</b></div>`);
            const byExt = p?.documentsByExtension || {};
            const entries = Object.entries(byExt).sort((a, b) => a[0].localeCompare(b[0]));
            if (entries.length) {
                for (const [ext, cnt] of entries) {
                    rows.push(`<div style="padding-left:12px;">— <span class="mono">.${ext}</span>: <b>${cnt}</b></div>`);
                }
            }
        }
        const html = `<div class="dump-summary">${rows.join('')}</div>`;
        this.#setStatusHtml(html, '#e7f6ec', '#27ae60');
    }

    // ===== состояние/хранение =====
    saveState() {
        const s = {
            chats: (this.dom.chats.value || '').split(/\r?\n|,|;/g).map(v => v.trim()).filter(Boolean),
            photos: !!this.dom.photos.checked,
            videos: !!this.dom.videos.checked,
            links: !!this.dom.links.checked,
            messages: !!this.dom.messages.checked,
            audio: !!this.dom.audio.checked,
            textDocuments: !!this.dom.chkTextDocs.checked,
            textDocumentExtensions: (this.dom.extInput.value || '').trim()
        };
        localStorage.setItem(this.storageKey, JSON.stringify(s));
    }

    restoreState() {
        try {
            const raw = localStorage.getItem(this.storageKey);
            if (!raw) return;
            const s = JSON.parse(raw);
            this.dom.chats.value = (s.chats || []).join('\n');
            this.dom.photos.checked = !!s.photos;
            this.dom.videos.checked = !!s.videos;
            this.dom.links.checked = !!s.links;
            this.dom.messages.checked = !!s.messages;
            this.dom.audio.checked = !!s.audio;
            this.dom.chkTextDocs.checked = !!s.textDocuments;
            this.dom.extInput.value = s.textDocumentExtensions || '';
        } catch {/* ignore */
        }
    }

    // ===== low-level UI =====
    #setProgress(processed, complete = false) {
        this.dom.bar.style.width = complete ? '100%' : '0%';
        this.dom.label.textContent = String(processed);
        this.dom.bar.classList.toggle('green', !!complete);
    }

    #setStatus(text, bg, color) {
        this.dom.status.textContent = text;
        this.dom.status.style.backgroundColor = bg;
        this.dom.status.style.color = color;
    }

    #setStatusHtml(html, bg, color) {
        this.dom.status.innerHTML = html;
        this.dom.status.style.backgroundColor = bg;
        this.dom.status.style.color = color;
    }
}
