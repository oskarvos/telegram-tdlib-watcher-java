import { ApiClient } from '../apiClient.js';
import { Notifier } from '../components/Notifier.js';


export class MonitorModule extends ApiClient {
    constructor() {
        super('/api/monitor');
        this.notify = new Notifier();
        this.pollInterval = null;


        this.dom = {
            container: document.getElementById('monitorContainer'),
            chats: document.getElementById('monitorChats'),
            keyword: document.getElementById('monitorKeyword'),
            case: document.getElementById('monitorCaseSensitive'),
            regex: document.getElementById('monitorRegexMode'),
            interval: document.getElementById('monitorInterval'),


            btnStart: document.getElementById('startMonitorBtn'),
            btnStop: document.getElementById('stopMonitorBtn'),
            btnDelDb: document.getElementById('deleteMonitorDbBtn'),


            processed: document.getElementById('monitorProcessedValue'),
            found: document.getElementById('monitorFoundValue'),
            bar: document.getElementById('monitorProgress-bar'),
            status: document.getElementById('monitorStatus'),


            resultsChat: document.getElementById('monitorResultsChat'),
            btnLoadRes: document.getElementById('loadMonitorResultsBtn'),
            resultsBox: document.getElementById('monitorResults')
        };


        this.dom.btnStart .addEventListener('click', () => this.start());
        this.dom.btnStop .addEventListener('click', () => this.stop());
        this.dom.btnDelDb .addEventListener('click', () => this.clearDb());
        this.dom.btnLoadRes.addEventListener('click', () => this.loadResults());


// Persistence
        [this.dom.chats, this.dom.keyword]
            .forEach(el => el.addEventListener('input', () => this.saveState()));
        [this.dom.case, this.dom.regex, this.dom.interval]
            .forEach(el => el.addEventListener('change', () => this.saveState()));
        this.restoreState();
    }


    get storageKey() { return 'td.monitor.state'; }
    saveState(){
        const s = this.#collect();
        localStorage.setItem(this.storageKey, JSON.stringify(s));
    }
    restoreState(){
        try {
            const raw = localStorage.getItem(this.storageKey);
            if (!raw) return;
            const s = JSON.parse(raw);
            this.dom.chats.value = (s.chats || []).join('\n');
            this.dom.keyword.value = s.keyword || '';
            this.dom.case.checked = !!s.caseSensitive;
            this.dom.regex.checked = !!s.useRegex;
            this.dom.interval.value = s.pollInterval || '1m';
        } catch {}
    }


    #collect(){
        const chats = (this.dom.chats.value || '').split(/\r?\n|,|;/g).map(s => s.trim()).filter(Boolean);
        return {
            chats,
            keyword: this.dom.keyword.value.trim(),
            caseSensitive: this.dom.case.checked,
            useRegex: this.dom.regex.checked,
            pollInterval: this.dom.interval.value
        };
    }


    async start(){
        const req = this.#collect();
        if (!req.chats.length) return this.setStatus('Ошибка: не указаны чаты', '#ffecec', '#e74c3c');
        if (!req.keyword) return this.setStatus('Ошибка: не указано ключевое слово', '#ffecec', '#e74c3c');


        this.saveState();
        this.dom.bar.classList.remove('green');
        this.setStatus('Старт мониторинга...', '#edf7ff', '#2c3e50');
        this.setProgress(0,0,false);


        try {
            await this.post('/start', req);
            this.notify.info('Мониторинг запущен');
            this.setStatus('Мониторинг выполняется...', '#edf7ff', '#2c3e50');
            this.#beginPolling();
        } catch (e) {
            this.setStatus('Ошибка запуска: ' + e.message, '#ffecec', '#e74c3c');
            this.notify.error(e.message);
        }
    }
    async stop(){
        this.setStatus('Останавливаем мониторинг...', '#fff4e6', '#e67e22');
        try {
            await this.post('/stop', {});
            this.#endPolling();
            this.setStatus('Мониторинг остановлен', '#ffecec', '#e74c3c');
        } catch (e) {
            this.setStatus('Ошибка: ' + e.message, '#ffecec', '#e74c3c');
        }
    }


    async clearDb(){
        try {
            await this.del('/database');
            this.notify.ok('MONITOR-БД очищена, чекпоинты сброшены');
            this.setStatus('База мониторинга очищена', '#e7f6ec', '#27ae60');
            this.dom.resultsBox.innerHTML = '';
        } catch (e) {
            this.setStatus('Ошибка удаления БД: ' + e.message, '#ffecec', '#e74c3c');
        }
    }


    async loadResults(){
        const chat = (this.dom.resultsChat.value || '').trim();
        if (!chat) return;
        try {
            const list = await this.get(`/results?chat=${encodeURIComponent(chat)}&limit=200`);
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
            const found = p?.foundMessages || 0;
            this.setProgress(processed, found, !p?.running);
            if (p?.running) {
                this.setStatus(`Мониторинг... Обработано: ${processed}, Найдено: ${found}`, '#edf7ff', '#2c3e50');
            } else {
                this.setStatus('Мониторинг завершён', '#e7f6ec', '#27ae60');
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
        const escapeHtml = s => String(s||'').replace(/[&<>"']/g, m => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;','\'':'&#39;'}[m]));
        box.innerHTML = list.map(r => `
<div class="result-item">
<div style="font-weight:700;margin-bottom:6px;">${escapeHtml(r.chatTitle || '')} • msg ${r.messageId}</div>
<div style="white-space:pre-wrap;">${escapeHtml(r.messageText || '')}</div>
<div class="search-stats"><div>${escapeHtml(r.keyword || '')}</div><div>${escapeHtml(r.messageDate || '')}</div></div>
</div>
`).join('');
    }
}