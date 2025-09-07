// Логика блока «Поиск». Вызовы и тексты статусов совпадают с образцом.
(function (global) {
    function SearchMode() {
        ApiClient.call(this);
        this.pollInterval = null;
    }

    SearchMode.prototype = Object.create(ApiClient.prototype);

    SearchMode.prototype.startSearch = async function () {
        var chats = (document.getElementById('searchChats').value || '')
            .split(/\n+/).map(function (s) {
                return s.trim();
            }).filter(Boolean);
        var keyword = document.getElementById('searchKeyword').value.trim();

        if (!chats.length)
            return this.setStatus('Ошибка: не указаны чаты для поиска', '#ffecec', '#e74c3c');
        if (!keyword)
            return this.setStatus('Ошибка: не указано ключевое слово', '#ffecec', '#e74c3c');

        var req = {
            chats: chats,
            keyword: keyword,
            caseSensitive: document.getElementById('caseSensitive').checked,
            useRegex: document.getElementById('regexMode').checked
        };

        document.getElementById('searchProgress-bar').classList.remove('green');
        this.setStatus('Начинаем поиск...', '#edf7ff', '#2c3e50');
        this.setProgress(0, 0);

        try {
            await this.post('/api/search/start', req);
            this.setStatus('Поиск выполняется...', '#edf7ff', '#2c3e50');
            var self = this;
            if (this.pollInterval) clearInterval(this.pollInterval);
            this.pollInterval = setInterval(function () {
                self.pollSearchProgress();
            }, 1000);
        } catch (e) {
            this.setStatus('Ошибка запуска: ' + e.message, '#ffecec', '#e74c3c');
        }
    };

    SearchMode.prototype.stopSearch = async function () {
        this.setStatus('Останавливаем поиск...', '#fff4e6', '#e67e22');
        try {
            await this.post('/api/search/stop', {});
            this.setStatus('Поиск остановлен', '#ffecec', '#e74c3c');
            if (this.pollInterval) clearInterval(this.pollInterval);
        } catch (e) {
            this.setStatus('Ошибка: ' + e.message, '#ffecec', '#e74c3c');
        }
    };

    SearchMode.prototype.pollSearchProgress = async function () {
        try {
            var data = await this.get('/api/search/progress');
            var processed = (data && data.processedMessages) || 0;
            var found = (data && data.foundMessages) || 0;
            this.setProgress(processed, found);

            if (data && data.running) {
                this.setStatus('Поиск выполняется... Обработано: ' + processed + ', Найдено: ' + found, '#edf7ff', '#2c3e50');
            } else {
                this.setProgress(processed, found, true);
                this.setStatus('Поиск завершен!', '#e7f6ec', '#27ae60');
                if (this.pollInterval) clearInterval(this.pollInterval);
            }
        } catch (e) {
            this.setStatus('Ошибка запроса прогресса: ' + e.message, '#ffecec', '#e74c3c');
            if (this.pollInterval) clearInterval(this.pollInterval);
        }
    };

    SearchMode.prototype.deleteSearchDatabase = async function () {
        try {
            await this.del('/api/search/database');
            this.setStatus('База данных поиска удалена', '#e7f6ec', '#27ae60');
        } catch (e) {
            this.setStatus('Ошибка удаления БД: ' + e.message, '#ffecec', '#e74c3c');
        }
    };

    SearchMode.prototype.setProgress = function (processed, found, complete) {
        document.getElementById('searchProcessedValue').textContent = processed;
        document.getElementById('searchFoundValue').textContent = found;
        var bar = document.getElementById('searchProgress-bar');
        if (complete) bar.classList.add('green'); else bar.classList.remove('green');
    };
    SearchMode.prototype.setStatus = function (text, bg, color) {
        var el = document.getElementById('searchStatus');
        el.textContent = text;
        el.style.backgroundColor = bg;
        el.style.color = color;
    };

    global.SearchMode = SearchMode;
})(window);
