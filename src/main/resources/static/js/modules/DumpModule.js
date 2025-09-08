import { ApiClient } from '../apiClient.js';
import { Notifier }   from '../components/Notifier.js';

export class DumpModule extends ApiClient {
    constructor() {
        super('/api/dump');
        this.notify = new Notifier();
        this.pollInterval = null;

        this.dom = {
            container: document.getElementById('dumpContainer'),
            chkTextDocs: document.getElementById('textDocuments'),
            extBlock: document.querySelector('.text-document-extensions'),
            extInput: document.getElementById('textExtensions'),

            chats:    document.getElementById('chats'),
            photos:   document.getElementById('photos'),
            videos:   document.getElementById('videos'),
            links:    document.getElementById('links'),
            messages: document.getElementById('messages'),
            audio:    document.getElementById('audio'),

            btnStart: document.getElementById('startBtn'),
            btnStop:  document.getElementById('stopBtn'),

            bar:      document.getElementById('progress-bar'),
            label:    document.getElementById('progress-value'),
            status:   document.getElementById('status'),
        };

        this.dom.chkTextDocs.addEventListener('change', () => this.toggleTextDocumentExtensions());
        this.dom.btnStart.addEventListener('click', () => this.start());
        this.dom.btnStop .addEventListener('click', () => this.stop());

        this.toggleTextDocumentExtensions();
    }

    toggleTextDocumentExtensions() {
        const visible = this.dom.chkTextDocs.checked;
        this.dom.extBlock.classList.toggle('hidden', !visible);
    }

    #collect() {
        const chats = (this.dom.chats.value || '').split(/\r?\n|,|;/g).map(s => s.trim()).filter(Boolean);
        return {
            chats,
            photos: this.dom.photos.checked,
            videos: this.dom.videos.checked,
            links: this.dom.links.checked,
            messages: this.dom.messages.checked,
            textDocuments: this.dom.chkTextDocs.checked,
            audio: this.dom.audio.checked,
            textDocumentExtensions: this.dom.extInput.value.trim() || null
        };
    }

    async start() {
        const req = this.#collect();

        if (!req.chats.length)   return this.setStatus('Ошибка: не указаны чаты', '#ffecec', '#e74c3c');
        if (!req.photos && !req.videos && !req.links && !req.messages && !req.textDocuments && !req.audio)
            return this.setStatus('Ошибка: не выбран ни один тип контента', '#ffecec', '#e74c3c');

        this.dom.bar.classList.remove('green');
        this.setStatus('Запуск дампа...', '#edf7ff', '#2c3e50');
        this.setProgress(0);

        try {
            await this.post('/start', req);
            this.notify.info('Дамп запущен');
            this.setStatus('Обработка...', '#edf7ff', '#2c3e50');
            this.#beginPolling();
        } catch (e) {
            this.setStatus('Ошибка запуска: ' + e.message, '#ffecec', '#e74c3c');
            this.notify.error(e.message);
        }
    }

    async stop() {
        this.setStatus('Останавливаем...', '#fff4e6', '#e67e22');
        try {
            await this.post('/stop', {});
            this.#endPolling();
            this.setStatus('Остановлено', '#ffecec', '#e74c3c');
        } catch (e) {
            this.setStatus('Ошибка: ' + e.message, '#ffecec', '#e74c3c');
        }
    }

    #beginPolling(){ this.#endPolling(); this.#pollOnce(); this.pollInterval = setInterval(() => this.#pollOnce(), 1000); }
    #endPolling(){ if (this.pollInterval) { clearInterval(this.pollInterval); this.pollInterval = null; } }

    async #pollOnce() {
        try {
            const p = await this.get('/progress');
            const pct = Math.max(0, Math.min(100, p?.percent || 0));
            this.setProgress(pct);
            if (p?.running) {
                this.setStatus(`Обработка... ${pct}%`, '#edf7ff', '#2c3e50');
            } else {
                this.setProgress(100, true);
                this.setStatus('Успешно завершено!', '#e7f6ec', '#27ae60');
                this.#endPolling();
            }
        } catch (e) {
            this.setStatus('Ошибка запроса прогресса: ' + e.message, '#ffecec', '#e74c3c');
            this.#endPolling();
        }
    }

    setProgress(percent, complete=false){
        this.dom.bar.style.width = percent + '%';
        this.dom.label.textContent = percent + '%';
        this.dom.bar.classList.toggle('green', !!complete);
    }
    setStatus(text, bg, color){
        this.dom.status.textContent = text;
        this.dom.status.style.backgroundColor = bg;
        this.dom.status.style.color = color;
    }
}
