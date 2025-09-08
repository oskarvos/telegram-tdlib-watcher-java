import { ApiClient } from '../apiClient.js';
import { Notifier }   from '../components/Notifier.js';

export class SearchModule extends ApiClient {
    constructor() {
        super('/api/search');
        this.notify = new Notifier();
        this.pollInterval = null;

        this.dom = {
            container: document.getElementById('searchContainer'),
            chats:     document.getElementById('searchChats'),
            keyword:   document.getElementById('searchKeyword'),
            case:      document.getElementById('caseSensitive'),
            regex:     document.getElementById('regexMode'),

            btnStart:  document.getElementById('startSearchBtn'),
            btnStop:   document.getElementById('stopSearchBtn'),
            btnDelDb:  document.getElementById('deleteSearchDbBtn'),

            processed: document.getElementById('searchProcessedValue'),
            found:     document.getElementById('searchFoundValue'),
            bar:       document.getElementById('searchProgress-bar'),
            status:    document.getElementById('searchStatus'),

            resultsChat: document.getElementById('searchResultsChat'),
            btnLoadRes:  document.getElementById('loadSearchResultsBtn'),
            resultsBox:  document.getElementById('searchResults')
        };

        this.dom.btnStart .addEventListener('click', () => this.start());
        this.dom.btnStop  .addEventListener('click', () => this.stop());
        this.dom.btnDelDb .addEventListener('click', () => this.clearDb());
        this.dom.btnLoadRes.addEventListener('click', () => this.loadResults());
    }

    #collect(){
        const chats = (this.dom.chats.value || '').split(/\r?\n|,|;/g).map(s => s.trim()).filter(Boolean);
        return {
            chats,
            keyword: this.dom.keyword.value.trim(),
            caseSensitive: this.dom.case.checked,
            useRegex: this.dom.regex.checked
        };
    }

    async start(){
        const req = this.#collect();
        if (!req.chats.length) return this.setStatus('Ошибка: не указаны чаты', '#ffecec', '#e74c3c');
        if (!req.keyword)      return this.setStatus('Ошибка: не указано ключевое слово', '#ffecec', '#e74c3c');

        this.dom.bar.classList.remove('green');
        this.setStatus('Запуск поиска...', '#edf7ff', '#2c3e50');
        this.setProgress(0, 0, false);

        try {
            await this.post('/start', req);
            this.notify.info('Поиск запущен');
            this.setStatus('Поиск выполняется...', '#edf7ff', '#2c3e50');
            this.#beginPolling();
        } catch (e) {
            this.setStatus('Ошибка запуска: ' + e.message, '#ffecec', '#e74c3c');
            this.notify.error(e.message);
        }
    }

    async stop(){
        this.setStatus('Останавливаем поиск...', '#fff4e6', '#e67e22');
        try {
            await this.post('/stop', {});
            this.#endPolling();
            this.setStatus('Поиск остановлен', '#ffecec', '#e74c3c');
        } catch (e) {
            this.setStatus('Ошибка: ' + e.message, '#ffecec', '#e74c3c');
        }
    }

    async clearDb(){
        try {
            await this.del('/database');
            this.notify.ok('SEARCH-БД очищена');
            this.setStatus('База поиска очищена', '#e7f6ec', '#27ae60');
            this.dom.resultsBox.innerHTML = '';
        } catch (e) {
            this.setStatus('Ошибка удаления БД: ' + e.message, '#ffecec', '#e74c3c');
        }
    }

    async loadResults(){
        const chat = (this.dom.resultsChat.value || '').trim();
        if (!chat) return;
        try {
            const list = await this.get(`/results?chat=${encodeURIComponent(chat)}`);
            this.renderResults(list);
        } catch (e) {
            this.notify.error(e.message);
        }
    }

    #beginPolling(){ this.#endPolling(); this.#tick(); this.pollInterval = setInterval(() => this.#tick(), 1000); }
    #endPolling(){ if (this.pollInterval) { clearInterval(this.pollInterval); this.pollInterval = null; } }

    async #tick(){
        try {
            const p = await this.get('/progress');
            const processed = p?.processedMessages || 0;
            const found     = p?.foundMessages || 0;
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

    setProgress(processed, found, complete=false){
        this.dom.processed.textContent = processed;
        this.dom.found.textContent = found;
        this.dom.bar.classList.toggle('green', !!complete);
    }
    setStatus(text, bg, color){
        this.dom.status.textContent = text;
        this.dom.status.style.backgroundColor = bg;
        this.dom.status.style.color = color;
    }

    renderResults(list){
        const box = this.dom.resultsBox;
        if (!Array.isArray(list) || !list.length){
            box.innerHTML = '<div style="padding:10px;color:#666;">Пусто</div>';
            return;
        }
        const escapeHtml = s => String(s||'').replace(/[&<>"']/g, m => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]));
        box.innerHTML = list.map(r => `
      <div class="result-item">
        <div style="font-weight:700;margin-bottom:6px;">${escapeHtml(r.chatTitle || '')} • msg ${r.messageId}</div>
        <div style="white-space:pre-wrap;">${escapeHtml(r.messageText || '')}</div>
        <div class="search-stats"><div>${escapeHtml(r.keyword || '')}</div><div>${escapeHtml(r.messageDate || '')}</div></div>
      </div>
    `).join('');
    }
}
