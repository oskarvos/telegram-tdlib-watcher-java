/** Утилиты для нормализации и разбиения списка чатов. */
export function normalizeChat(s) {
    s = (s || '').trim();
    if (/^@https?:\/\//i.test(s) || /^@t\.me\//i.test(s)) s = s.replace(/^@+/, '');
    if (/^https?:\/\/t\.me\//i.test(s) || /^t\.me\//i.test(s)) return s;
    s = s.replace(/^id:\s*/i, '');
    if (s.startsWith('@')) return s;
    if (/^[a-z0-9_]{5,}$/i.test(s)) return '@' + s;
    return s;
}

export function splitChats(text) {
    return (text || '')
        .split(/\r?\n|,|;/g)
        .map(normalizeChat)
        .map(s => s.trim())
        .filter(Boolean);
}
