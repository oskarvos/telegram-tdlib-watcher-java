// Точка входа. Делаем всё глобальным, чтобы inline onclick из HTML работал 1в1 как в образце.
(function (global) {
    // «Приложение»: только переключение видимости блоков
    function App(dumpMode, searchMode) {
        this.dumpMode = dumpMode;
        this.searchMode = searchMode;

        this.dumpContainer = document.getElementById('dumpContainer');
        this.searchContainer = document.getElementById('searchContainer');
        this.dumpBtn = document.getElementById('dumpModeBtn');
        this.searchBtn = document.getElementById('searchModeBtn');
    }

    // Показ «Дамп»
    App.prototype.showDumpMode = function () {
        this.dumpContainer.style.display = 'block';
        this.searchContainer.style.display = 'none';
        this.dumpBtn.classList.add('active');
        this.searchBtn.classList.remove('active');
    };

    // Показ «Поиск»
    App.prototype.showSearchMode = function () {
        this.dumpContainer.style.display = 'none';
        this.searchContainer.style.display = 'block';
        this.dumpBtn.classList.remove('active');
        this.searchBtn.classList.add('active');
    };

    // Инициализация после загрузки DOM
    document.addEventListener('DOMContentLoaded', function () {
        var dm = new DumpMode();
        var sm = new SearchMode();
        var app = new App(dm, sm);

        // Экспорт в глобал: для inline onclick вида app.showDumpMode() / dumpMode.startDump()
        global.app = app;
        global.dumpMode = dm;
        global.searchMode = sm;

        // По умолчанию — как в образце: показываем «Дамп»
        app.showDumpMode();
    });
})(window);
