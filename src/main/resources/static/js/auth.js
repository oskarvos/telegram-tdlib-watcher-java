// Лёгкий клиент для REST (не трогаем ваш ApiClient, чтобы не тянуть лишнее)
const $ = (id) => document.getElementById(id);
const statusBox = $('authStatus');

function setStatus(text, bg = '#edf7ff', color = '#2c3e50') {
    statusBox.textContent = text;
    statusBox.style.backgroundColor = bg;
    statusBox.style.color = color;
}

async function getJSON(url) {
    const r = await fetch(url, { headers: { 'Accept': 'application/json' } });
    if (!r.ok) throw new Error(await r.text());
    return r.json();
}

async function postJSON(url, body) {
    const r = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
        body: JSON.stringify(body || {})
    });
    const ct = r.headers.get('content-type') || '';
    const parse = async () => ct.includes('application/json') ? r.json() : r.text();
    if (!r.ok) throw new Error(await parse());
    return parse();
}

// если уже авторизованы — сразу в приложение
(async () => {
    try {
        const s = await getJSON('/api/auth/status');
        if (s.authorized) {
            location.replace('/app');
            return;
        }
        // префилд телефона, если есть
        if (s.phoneMasked) $('phone').placeholder = s.phoneMasked;
    } catch (e) {
        // тихо
    }
})();

$('startAuthBtn').addEventListener('click', async () => {
    const apiId  = parseInt($('apiId').value || '', 10);
    const apiHash = ($('apiHash').value || '').trim();
    const phone   = ($('phone').value || '').trim();
    const code    = ($('code').value || '').trim();
    const pass    = ($('pass').value || '').trim();

    if (!apiId || !apiHash || !phone || !code) {
        setStatus('Заполните api-id, api-hash, телефон и код.', '#ffecec', '#e74c3c');
        return;
    }

    setStatus('Авторизуемся...', '#edf7ff', '#2c3e50');
    $('startAuthBtn').disabled = true;

    try {
        const res = await postJSON('/api/auth/start', { apiId, apiHash, phone, code, pass });
        if (res.authorized) {
            setStatus('Готово! Переходим в приложение...', '#e7f6ec', '#27ae60');
            setTimeout(() => location.replace('/app'), 300);
        } else {
            setStatus('Не авторизовано: ' + (res.message || 'Проверьте данные'), '#ffecec', '#e74c3c');
        }
    } catch (e) {
        setStatus('Ошибка: ' + e.message, '#ffecec', '#e74c3c');
    } finally {
        $('startAuthBtn').disabled = false;
    }
});
