// Логика блока «Дамп». Строго как в образце (показ блока расширений и пр.)
(function (global) {
    function DumpMode() {
        ApiClient.call(this);
        this.notify = new Notifier();
        this.pollInterval = null;
    }

    DumpMode.prototype = Object.create(ApiClient.prototype);

    // Показать/скрыть поле расширений (как в образце: через style.display)
    DumpMode.prototype.toggleTextDocumentExtensions = function () {
        var textDocs = document.getElementById('textDocuments');
        var ext = document.querySelector('.text-document-extensions');
        ext.style.display = textDocs.checked ? 'block' : 'none';
    };

    // Старт дампа
    DumpMode.prototype.startDump = async function () {
        var chats = (document.getElementById('chats').value || '')
            .split(/\n+/).map(function (s) {
                return s.trim();
            }).filter(Boolean);

        var req = {
            chats: chats,
            photos: document.getElementById('photos').checked,
            videos: document.getElementById('videos').checked,
            links: document.getElementById('links').checked,
            messages: document.getElementById('messages').checked,
            textDocuments: document.getElementById('textDocuments').checked,
            audio: document.getElementById('audio').checked,
            textDocumentExtensions: document.getElementById('textExtensions').value.trim()
        };

        if (!req.chats.length)
            return this.setStatus('Ошибка: не указаны чаты для обработки', '#ffecec', '#e74c3c');

        if (!req.photos && !req.videos && !req.links && !req.messages && !req.textDocuments && !req.audio)
            return this.setStatus('Ошибка: не выбран ни один тип контента', '#ffecec', '#e74c3c');

        document.getElementById('progress-bar').classList.remove('green');
        this.setStatus('Начинаем процесс дампа...', '#edf7ff', '#2c3e50');
        this.setProgress(0);

        try {
            await this.post('/start', req);
            this.setStatus('Обработка...', '#edf7ff', '#2c3e50');
            var self = this;
            if (this.pollInterval) clearInterval(this.pollInterval);
            this.pollInterval = setInterval(function () {
                self.pollDumpProgress();
            }, 1000);
        } catch (e) {
            this.setStatus('Ошибка запуска: ' + e.message, '#ffecec', '#e74c3c');
        }
    };

    // Останов дампа
    DumpMode.prototype.stopDump = async function () {
        this.setStatus('Останавливаем...', '#fff4e6', '#e67e22');
        try {
            await this.post('/stop', {});
            this.setStatus('Остановлено', '#ffecec', '#e74c3c');
            if (this.pollInterval) clearInterval(this.pollInterval);
        } catch (e) {
            this.setStatus('Ошибка: ' + e.message, '#ffecec', '#e74c3c');
        }
    };

    // Опрос прогресса
    DumpMode.prototype.pollDumpProgress = async function () {
        try {
            var data = await this.get('/progress');
            var p = Math.max(0, Math.min(100, (data && data.percent) || 0));
            this.setProgress(p);

            if (data && data.running) {
                this.setStatus('Обработка... ' + p + '%', '#edf7ff', '#2c3e50');
            } else {
                this.setProgress(100, true);
                this.setStatus('Успешно завершено!', '#e7f6ec', '#27ae60');
                if (this.pollInterval) clearInterval(this.pollInterval);
            }
        } catch (e) {
            this.setStatus('Ошибка запроса прогресса: ' + e.message, '#ffecec', '#e74c3c');
            if (this.pollInterval) clearInterval(this.pollInterval);
        }
    };

    // UI-хелперы (один в один с образцом)
    DumpMode.prototype.setProgress = function (percent, complete) {
        var bar = document.getElementById('progress-bar');
        var lbl = document.getElementById('progress-value');
        bar.style.width = percent + '%';
        lbl.textContent = percent + '%';
        if (complete) bar.classList.add('green'); else bar.classList.remove('green');
    };
    DumpMode.prototype.setStatus = function (text, bg, color) {
        var el = document.getElementById('status');
        el.textContent = text;
        el.style.backgroundColor = bg;
        el.style.color = color;
    };

    global.DumpMode = DumpMode;
})(window);
