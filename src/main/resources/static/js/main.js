// /js/main.js
/** Точка входа TDLib Tools: проверка авторизации и надёжное переключение режимов. */

import { DumpModule }   from './modules/DumpModule.js';
import { SearchModule } from './modules/SearchModule.js';
import { MonitorModule } from './modules/MonitorModule.js';
import './utils.js';

// пути и «готовые» статусы авторизации
const PATHS = { APP: '/index.html', AUTH: '/auth.html' };
const READY_STATES = new Set(['READY', 'AUTHORIZED', 'LOGGED_IN']);
let isRedirecting = false;

function safeRedirect(url){ if(isRedirecting) return; isRedirecting = true; setTimeout(()=>{ try{window.location.replace(url);}catch{ try{window.location.href=url;}catch{}} },300); }

async function ensureAuthorizedOrRedirect() {
    await new Promise(r=>setTimeout(r, 250));
    try{
        const r = await fetch('/api/webauth/status', {headers:{'Content-Type':'application/json'}});
        const st = await r.json();
        if (!(st?.ok && READY_STATES.has(st.state))) { safeRedirect(PATHS.AUTH); return false; }
        return true;
    }catch{ safeRedirect(PATHS.AUTH); return false; }
}

class App {
    /** Управляет вкладками и лениво инициализирует модули. */
    constructor() {
        // кнопки
        this.btns = {
            dump:    document.getElementById('dumpModeBtn'),
            search:  document.getElementById('searchModeBtn'),
            monitor: document.getElementById('monitorModeBtn')
        };

        // контейнеры
        this.containers = {
            dump:    document.getElementById('dumpContainer'),
            search:  document.getElementById('searchContainer'),
            monitor: document.getElementById('monitorContainer')
        };

        // модули (ленивая инициализация)
        this.modules = { dump: null, search: null, monitor: null };

        // делегирование кликов по панели
        const switcher = document.querySelector('.mode-switcher');
        if (switcher) {
            switcher.addEventListener('click', (e) => {
                const btn = e.target.closest('button[id$="ModeBtn"]');
                if (!btn) return;
                e.preventDefault();
                btn.blur();

                const id = btn.id; // dumpModeBtn | searchModeBtn | monitorModeBtn
                const mode = id.startsWith('dump') ? 'dump'
                    : id.startsWith('search') ? 'search'
                        : 'monitor';
                this.show(mode);
            });
        }

        // режим по умолчанию
        this.show('dump');
    }

    // показать выбранный режим
    show(mode) {
        Object.entries(this.containers).forEach(([k, el]) => {
            if (!el) return;
            el.classList.toggle('hidden', k !== mode);
        });

        Object.entries(this.btns).forEach(([k, b]) => {
            if (!b) return;
            const active = k === mode;
            b.classList.toggle('active', active);
            b.setAttribute('aria-selected', active ? 'true' : 'false');
        });

        if (!this.modules[mode]) {
            try {
                if (mode === 'dump')    this.modules.dump    = new DumpModule();
                if (mode === 'search')  this.modules.search  = new SearchModule();
                if (mode === 'monitor') this.modules.monitor = new MonitorModule();
            } catch (e) {
                const box = this.containers[mode]?.querySelector('.statusbar, #status, #searchStatus, #monitorStatus');
                if (box) {
                    box.textContent = 'Ошибка инициализации: ' + (e?.message || e);
                    box.style.backgroundColor = '#ffecec';
                    box.style.color = '#e74c3c';
                }
                console.error('Ошибка инициализации модуля', mode, e);
            }
        }
    }
}

document.addEventListener('DOMContentLoaded', async () => {
    const ok = await ensureAuthorizedOrRedirect();
    if (!ok) return;
    window.app = new App();
});
