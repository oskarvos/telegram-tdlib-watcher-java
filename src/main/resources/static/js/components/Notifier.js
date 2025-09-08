// Простейшие тост-уведомления (правый нижний угол).
export class Notifier {
    constructor(root = document.getElementById('toasts')) { this.root = root; }
    info(msg)  { this.#push(msg, ''); }
    ok(msg)    { this.#push(msg, 'ok'); }
    error(msg) { this.#push(msg, 'error'); }

    #push(message, cls) {
        const el = document.createElement('div');
        el.className = `toast ${cls}`;
        el.textContent = message;
        this.root.appendChild(el);
        setTimeout(() => el.remove(), 4000);
    }
}
