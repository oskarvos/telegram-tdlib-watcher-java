// Логика страницы авторизации
class Api {
    async request(url, opt={}) {
        const r = await fetch(url, { headers:{'Content-Type':'application/json'}, ...opt });
        const ct = r.headers.get('content-type') || '';
        const parse = async () => ct.includes('application/json') ? r.json() : r.text();
        const data = await parse().catch(() => null);
        if (!r.ok) throw new Error(typeof data === 'string' ? data : (data?.message || r.statusText));
        return data;
    }
    get(url){ return this.request(url, { method:'GET'  }); }
    post(url, body){ return this.request(url, { method:'POST', body: JSON.stringify(body||{}) }); }
}
const api = new Api();

const s1 = {
    root: document.getElementById('step1'),
    apiId: document.getElementById('apiId'),
    apiHash: document.getElementById('apiHash'),
    phone: document.getElementById('phone'),
    useTestDc: document.getElementById('useTestDc'),
    btn: document.getElementById('btnSendCode'),
    status: document.getElementById('s1status'),
};
const s2 = {
    root: document.getElementById('step2'),
    code: document.getElementById('code'),
    password: document.getElementById('password'),
    pwdWrap: document.getElementById('pwdWrap'),
    btn: document.getElementById('btnVerify'),
    status: document.getElementById('s2status'),
};

let pollTimer = null;
function pollStatus(){
    clearInterval(pollTimer);
    pollTimer = setInterval(async () => {
        try {
            const st = await api.get('/api/webauth/status');
            if (!st.ok) return;
            if (st.state === 'WAIT_CODE') {
                s2.status.textContent = 'Ожидаем код';
                s2.pwdWrap.classList.add('hidden');
            } else if (st.state === 'WAIT_PASSWORD') {
                s2.status.textContent = 'Требуется пароль 2FA';
                s2.pwdWrap.classList.remove('hidden');
            } else if (st.state === 'READY') {
                s2.status.textContent = 'Готово! Переход...';
                clearInterval(pollTimer);
                setTimeout(() => { window.location.href = '/app'; }, 300);
            } else {
                s2.status.textContent = 'Статус: ' + st.state;
            }
        } catch {}
    }, 1000);
}

s1.btn.addEventListener('click', async () => {
    const apiId = parseInt(s1.apiId.value.trim(), 10);
    const apiHash = s1.apiHash.value.trim();
    const phone = s1.phone.value.trim();

    if (!apiId || !apiHash || !phone) {
        s1.status.textContent = 'Заполните все поля';
        s1.status.style.backgroundColor = '#ffecec';
        s1.status.style.color = '#e74c3c';
        return;
    }
    s1.btn.disabled = true;
    s1.status.textContent = 'Отправляем код...';
    try {
        const res = await api.post('/api/webauth/start', { apiId, apiHash, phone, useTestDc: s1.useTestDc.checked });
        if (!res.ok) throw new Error(res.message || 'Ошибка');
        s1.root.classList.add('hidden');
        s2.root.classList.remove('hidden');
        s2.status.textContent = 'Код отправлен. Проверьте Telegram';
        pollStatus();
    } catch (e) {
        s1.status.textContent = 'Ошибка: ' + e.message;
        s1.status.style.backgroundColor = '#ffecec';
        s1.status.style.color = '#e74c3c';
    } finally {
        s1.btn.disabled = false;
    }
});

s2.btn.addEventListener('click', async () => {
    const code = s2.code.value.trim();
    const password = s2.password.value.trim();

    if (!code && s2.pwdWrap.classList.contains('hidden')) {
        s2.status.textContent = 'Введите код';
        s2.status.style.backgroundColor = '#ffecec';
        s2.status.style.color = '#e74c3c';
        return;
    }
    s2.btn.disabled = true;
    s2.status.textContent = 'Подтверждаем...';
    try {
        const res = await api.post('/api/webauth/verify', { code, password });
        if (!res.ok) throw new Error(res.message || 'Ошибка');
        if (res.state === 'READY') { window.location.href = '/app'; return; }
        if (res.state === 'WAIT_PASSWORD') s2.pwdWrap.classList.remove('hidden');
        s2.status.textContent = 'Статус: ' + res.state;
    } catch (e) {
        s2.status.textContent = 'Ошибка: ' + e.message;
        s2.status.style.backgroundColor = '#ffecec';
        s2.status.style.color = '#e74c3c';
    } finally {
        s2.btn.disabled = false;
    }
});

// Уже авторизованы?
(async () => {
    try {
        const st = await api.get('/api/webauth/status');
        if (st.ok && st.state === 'READY') window.location.href = '/app';
    } catch {}
})();
