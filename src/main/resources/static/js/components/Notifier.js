// Простой тост-уведомитель. Делаем глобальным.
(function (global) {
    function Notifier(root) {
        this.root = root || document.getElementById('toasts');
    }

    Notifier.prototype.info = function (m) {
        this._push(m, '');
    };
    Notifier.prototype.ok = function (m) {
        this._push(m, 'ok');
    };
    Notifier.prototype.error = function (m) {
        this._push(m, 'error');
    };
    Notifier.prototype._push = function (msg, cls) {
        var el = document.createElement('div');
        el.className = 'toast ' + (cls || '');
        el.textContent = msg;
        this.root.appendChild(el);
        setTimeout(function () {
            el.remove();
        }, 4000);
    };
    global.Notifier = Notifier;
})(window);
