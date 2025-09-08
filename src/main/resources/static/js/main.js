// Точка входа фронта: создаём модули, настраиваем переключение режимов.
import { DumpModule } from './modules/DumpModule.js';
import { SearchModule } from './modules/SearchModule.js';

class App {
    constructor() {
        this.dumpModule = new DumpModule();
        this.searchModule = new SearchModule();

        // Кнопки переключателя
        this.btnDump   = document.getElementById('dumpModeBtn');
        this.btnSearch = document.getElementById('searchModeBtn');

        // Контейнеры
        this.dumpContainer   = document.getElementById('dumpContainer');
        this.searchContainer = document.getElementById('searchContainer');

        // Навесим обработчики кликов
        this.btnDump.addEventListener('click',   () => this.showDumpMode());
        this.btnSearch.addEventListener('click', () => this.showSearchMode());

        // Начальный экран — «Дамп»
        this.showDumpMode();
    }

    showDumpMode() {
        // показать дамп, скрыть поиск
        this.dumpContainer.classList.remove('hidden');
        this.searchContainer.classList.add('hidden');

        // визуально подсветить активную кнопку
        this.btnDump.classList.add('active');
        this.btnDump.setAttribute('aria-selected', 'true');

        this.btnSearch.classList.remove('active');
        this.btnSearch.setAttribute('aria-selected', 'false');
    }

    showSearchMode() {
        // показать поиск, скрыть дамп
        this.dumpContainer.classList.add('hidden');
        this.searchContainer.classList.remove('hidden');

        // визуально подсветить активную кнопку
        this.btnSearch.classList.add('active');
        this.btnSearch.setAttribute('aria-selected', 'true');

        this.btnDump.classList.remove('active');
        this.btnDump.setAttribute('aria-selected', 'false');
    }
}

// Инициализация
document.addEventListener('DOMContentLoaded', () => {
    // Экземпляр приложения — если вдруг захочешь дергать методы из консоли
    window.app = new App();
    // Также можно получить доступ к модулям:
    window.dumpMode = window.app.dumpModule;
    window.searchMode = window.app.searchModule;
});
