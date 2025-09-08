// Точка входа приложения
import { DumpModule }    from './modules/DumpModule.js';
import { SearchModule }  from './modules/SearchModule.js';
import { MonitorModule } from './modules/MonitorModule.js';

class App {
    constructor() {
        this.dump    = new DumpModule();
        this.search  = new SearchModule();
        this.monitor = new MonitorModule();

        this.btnDump    = document.getElementById('dumpModeBtn');
        this.btnSearch  = document.getElementById('searchModeBtn');
        this.btnMonitor = document.getElementById('monitorModeBtn');

        this.dumpContainer    = document.getElementById('dumpContainer');
        this.searchContainer  = document.getElementById('searchContainer');
        this.monitorContainer = document.getElementById('monitorContainer');

        this.btnDump   .addEventListener('click', () => this.show(this.btnDump,   this.dumpContainer));
        this.btnSearch .addEventListener('click', () => this.show(this.btnSearch, this.searchContainer));
        this.btnMonitor.addEventListener('click', () => this.show(this.btnMonitor,this.monitorContainer));

        // По умолчанию — «Дамп»
        this.show(this.btnDump, this.dumpContainer);
    }

    show(activeBtn, activeContainer){
        // панели
        [this.dumpContainer, this.searchContainer, this.monitorContainer]
            .forEach(c => c.classList.toggle('hidden', c !== activeContainer));
        // кнопки
        [this.btnDump, this.btnSearch, this.btnMonitor].forEach(b => {
            const active = b === activeBtn;
            b.classList.toggle('active', active);
            b.setAttribute('aria-selected', active ? 'true' : 'false');
        });
    }
}

function normalizeChat(s){
    s = (s || '').trim();

    // если по ошибке написали @перед URL — уберём @
    if (/^@https?:\/\//i.test(s) || /^@t\.me\//i.test(s)) s = s.replace(/^@+/, '');

    // t.me ссылки -> оставляем как есть (их умеет резолвить бэкенд)
    if (/^https?:\/\/t\.me\//i.test(s) || /^t\.me\//i.test(s)) return s;

    // id: ... -> убираем префикс
    s = s.replace(/^id:\s*/i, '');

    // допускаем @username или чистый username
    if (s.startsWith('@')) return s;
    if (/^[a-z0-9_]{5,}$/i.test(s)) return '@' + s; // «оглавлиним» чистый username

    return s; // вернём как есть (например, -100…)
}

function splitChats(text){
    return (text || '')
        .split(/\r?\n|,|;/g)
        .map(normalizeChat)
        .map(s => s.trim())
        .filter(Boolean);
}

document.addEventListener('DOMContentLoaded', () => { window.app = new App(); });
