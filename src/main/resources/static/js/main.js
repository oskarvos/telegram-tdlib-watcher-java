// Точка входа фронта: создаём модули, настраиваем переключение режимов.
import { DumpModule } from './modules/DumpModule.js';
import { SearchModule } from './modules/SearchModule.js';
import { MonitorModule } from './modules/MonitorModule.js';

class App {
    constructor() {
        this.dumpModule = new DumpModule();
        this.searchModule = new SearchModule();
        this.monitorModule = new MonitorModule(); // <-- новое

        this.btnDump    = document.getElementById('dumpModeBtn');
        this.btnSearch  = document.getElementById('searchModeBtn');
        this.btnMonitor = document.getElementById('monitorModeBtn'); // <-- новое

        this.dumpContainer    = document.getElementById('dumpContainer');
        this.searchContainer  = document.getElementById('searchContainer');
        this.monitorContainer = document.getElementById('monitorContainer'); // <-- новое

        this.btnDump.addEventListener('click',    () => this.showDumpMode());
        this.btnSearch.addEventListener('click',  () => this.showSearchMode());
        this.btnMonitor.addEventListener('click', () => this.showMonitorMode()); // <-- новое

        this.showDumpMode();
    }

    #setActive(btnActive, contActive){
        for (const el of [this.dumpContainer, this.searchContainer, this.monitorContainer]) {
            el.classList.toggle('hidden', el !== contActive);
        }
        for (const b of [this.btnDump, this.btnSearch, this.btnMonitor]) {
            const active = b === btnActive;
            b.classList.toggle('active', active);
            b.setAttribute('aria-selected', active ? 'true' : 'false');
        }
    }

    showDumpMode()    { this.#setActive(this.btnDump,    this.dumpContainer); }
    showSearchMode()  { this.#setActive(this.btnSearch,  this.searchContainer); }
    showMonitorMode() { this.#setActive(this.btnMonitor, this.monitorContainer); }
}

document.addEventListener('DOMContentLoaded', () => { window.app = new App();

    // Также можно получить доступ к модулям:
    window.dumpMode = window.app.dumpModule;
    window.searchMode = window.app.searchModule;
    window.monitorModule = window.app.monitorModule;
});
