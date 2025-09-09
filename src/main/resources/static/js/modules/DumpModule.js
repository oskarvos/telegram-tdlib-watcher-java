import {ApiClient} from '../apiClient.js';
import {Notifier} from '../components/Notifier.js';

export class DumpModule extends ApiClient {
    constructor() {
        super('/api/dump');
        this.notify = new Notifier();
        this.pollInterval = null;

        this.dom = {
            container:    document.getElementById('dumpContainer'),
            chkTextDocs:  document.getElementById('textDocuments'),
            extBlock:     document.querySelector('.text-document-extensions'),
            extInput:     document.getElementById('textExtensions'),

            chats:        document.getElementById('chats'),
            photos:       document.getElementById('photos'),
            videos:       document.getElementById('videos'),
            links:        document.getElementById('links'),
            messages:     document.getElementById('messages'),
            audio:        document.getElementById('audio'),

            btnStart:     document.getElementById('startBtn'),
            btnStop:      document.getElementById('stopBtn'),

            bar:          document.getElementById('progress-bar'),
            label:        document.getElementById('progress-value'),
            status:       document.getElementById('status'),
        };

        // UI events
        this.dom.chkTextDocs.addEventListener('change', () => {
            this.toggleTextDocumentExtensions();
            this.saveState();
        });
        this.dom.btnStart.addEventListener('click', () => this.start());
        this.dom.btnStop.addEventListener('click',  () => this.stop());

        // Persist small set of fields that реально существуют на вкладке "Дамп"
        [this.dom.chats, this.dom.extInput]
            .forEach(el => el.addEventListener('input', () => this.saveState()));
        [this.dom.photos, this.dom.videos, this.dom.links, this.dom.messages, this.dom.audio, this.dom.chkTextDocs]
            .forEach(el => el.addEventListener('change', () => this.saveState()));

        this.restoreState();
        this.toggleTextDocumentExtensions();
    }

    // === Persistence ===
    get storageKey() { return 'td.dump.state'; }

    saveState() {
        try {
            const s = {
                chats: (this.dom.chats.value || '')
                    .split(/\r?\n|,|;/g).map(v => v.trim()).filter(Boolean),
                photos:   !!this.dom.photos.checked,
                videos:   !!this.dom.videos.checked,
                links:    !!this.dom.links.checked,
                messages: !!this.dom.messages.checked,
                audio:    !!this.dom.audio.checked,
                textDocuments: !!this.dom.chkTextDocs.checked,
                textDocumentExtensions: this.dom.extInput.value || ''
            };
            localStorage.setItem(this.storageKey, JSON.stringify(s));
        } catch { /* ignore */ }
    }

    restoreState() {
        try {
            const raw = localStorage.getItem(this.storageKey);
            if (!raw) return;
            const s = JSON.parse(raw);

            this.dom.chats.value = (s.chats || []).join('\n');

            this.dom.photos.checked   = !!s.photos;
            this.dom.videos.checked   = !!s.videos;
            this.dom.links.checked    = !!s.links;
            this.dom.messages.checked = s.messages !== false; // по умолчанию true
            this.dom.audio.checked    = !!s.audio;

            this.dom.chkTextDocs.checked = !!s.textDocuments;
            this.dom.extInput.value = s.textDocumentExtensions || '';
        } catch { /* ignore */ }
    }

    toggleTextDocumentExtensions() {
        const visible = this.dom.chkTextDocs.checked;
        this.dom.extBlock.classList.toggle('hidden', !visible);
    }

    #collect() {
        const chats = (this.dom.chats.value || '')
            .split(/\r?\n|,|;/g)
            .map(s => s.trim())
            .filter(Boolean);

        return {
            chats,
            photos:        this.dom.photos.checked,
            videos:        this.dom.videos.checked,
            links:         this.dom.links.checked,
            messages:      this.dom.messages.checked,
            textDocuments: this.dom.chkTextDocs.checked,
            audio:         this.dom.audio.checked,
            textDocumentExtensions: this.dom.extInput.value.trim() || null
        };
    }

    async start() {
        const req = this.#collect();

        if (!req.chats.length) {
            return this.setStatus('Ошибка: не указаны чаты', '#ffecec', '#e74c3c');
        }
        if (!req.photos && !req.videos && !req.links && !req.messages && !req.textDocuments && !req.audio) {
            return this.setStatus('Ошибка: не выбран ни один тип контента', '#ffecec', '#e74c3c');
        }

        this.saveState();
        this.dom.bar.classList.remove('green');
        this.setStatus('Запуск дампа...', '#edf7ff', '#2c3e50');
        this.setProgress(0);

        // Блокируем повторный старт
        this.dom.btnStart.disabled = true;
        this.dom.btnStop.disabled  = false;

        // Начинаем polling сразу — серверный /start долгий и синхронный
        this.#beginPolling();

        // Не ждём завершения — отправляем и реагируем на результат через промис
        this.post('/start', req)
            .then(() => {
                // Когда сервер вернул ответ — дамп уже завершён.
                // Polling сам догонит и покажет 100%.
            })
            .catch(e => {
                this.#endPolling();
                this.setStatus('Ошибка запуска: ' + (e?.message || e), '#ffecec', '#e74c3c');
                this.notify.error(e?.message || String(e));
                this.dom.btnStart.disabled = false;
                this.dom.btnStop.disabled  = true;
            });
    }

    async stop() {
        this.setStatus('Останавливаем...', '#fff4e6', '#e67e22');
        try {
            await this.post('/stop', {});
            this.#endPolling();
            this.setStatus('Остановлено', '#ffecec', '#e74c3c');
        } catch (e) {
            this.setStatus('Ошибка: ' + (e?.message || e), '#ffecec', '#e74c3c');
        } finally {
            this.dom.btnStart.disabled = false;
            this.dom.btnStop.disabled  = true;
        }
    }

    #beginPolling() {
        this.#endPolling();
        this.#pollOnce();
        this.pollInterval = setInterval(() => this.#pollOnce(), 1000);
    }

    #endPolling() {
        if (this.pollInterval) {
            clearInterval(this.pollInterval);
            this.pollInterval = null;
        }
    }

    async #pollOnce() {
        try {
            const p = await this.get('/progress');
            const pct = Math.max(0, Math.min(100, p?.percent ?? 0));
            this.setProgress(pct);

            if (p?.running) {
                this.setStatus(`Обработка... ${pct}%`, '#edf7ff', '#2c3e50');
            } else {
                this.setProgress(100, true);
                this.setStatus('Успешно завершено!', '#e7f6ec', '#27ae60');
                this.#endPolling();
                this.dom.btnStart.disabled = false;
                this.dom.btnStop.disabled  = true;
            }
        } catch (e) {
            this.setStatus('Ошибка запроса прогресса: ' + (e?.message || e), '#ffecec', '#e74c3c');
            this.#endPolling();
            this.dom.btnStart.disabled = false;
            this.dom.btnStop.disabled  = true;
        }
    }

    setProgress(percent, complete = false) {
        this.dom.bar.style.width = percent + '%';
        this.dom.label.textContent = percent + '%';
        this.dom.bar.classList.toggle('green', !!complete);
    }

    setStatus(text, bg, color) {
        this.dom.status.textContent = text;
        this.dom.status.style.backgroundColor = bg;
        this.dom.status.style.color = color;
    }
}
