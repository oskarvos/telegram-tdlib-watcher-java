// /js/auth.js
/** Скрипт страницы авторизации через TDLib: старт, подтверждение кода, редирект при READY. */

// маршруты и «готовые» статусы
const PATHS = { APP: '/', AUTH: '/auth.html' };
const READY_STATES = new Set(['READY', 'AUTHORIZED', 'LOGGED_IN']);

let __redirecting = false;
function safeRedirect(url) {
    if (__redirecting) return;
    __redirecting = true;
    try { window.location.replace(url); } catch {}
    try { window.location.href = url; } catch {}
    setTimeout(() => { try { window.location.assign(url); } catch {} }, 150);
}


// простой клиент REST
class Api {
    async request(url, opt = {}) {
        const r  = await fetch(url, {headers: {'Content-Type': 'application/json'}, ...opt});
        const ct = r.headers.get('content-type') || '';
        const data = await (ct.includes('application/json') ? r.json() : r.text()).catch(() => null);
        if (!r.ok) throw new Error(typeof data === 'string' ? data : (data?.message || r.statusText));
        return data;
    }
    get(url)           { return this.request(url, {method: 'GET'}); }
    post(url, body)    { return this.request(url, {method: 'POST', body: JSON.stringify(body || {})}); }
}
const api = new Api();

// DOM
const s1 = {
    root: document.getElementById('step1'),
    apiId: document.getElementById('apiId'),
    apiHash: document.getElementById('apiHash'),
    phone: document.getElementById('phone'),
    useTestDc: document.getElementById('useTestDc'),
    remember: document.getElementById('rememberCreds'),
    btn: document.getElementById('btnSendCode'),
    status: document.getElementById('s1status')
};
const s2 = {
    root: document.getElementById('step2'),
    code: document.getElementById('code'),
    password: document.getElementById('password'),
    pwdWrap: document.getElementById('pwdWrap'),
    btn: document.getElementById('btnVerify'),
    status: document.getElementById('s2status')
};

// LocalStorage — запоминание полей
const LS = {
    get k() {
        return {
            remember: 'td.auth.remember',
            apiId: 'td.auth.apiId',
            apiHash: 'td.auth.apiHash',
            phone: 'td.auth.phone',
            useTestDc: 'td.auth.useTestDc'
        };
    },
    read() {
        const k = this.k;
        return {
            remember: localStorage.getItem(k.remember) === '1',
            apiId: localStorage.getItem(k.apiId) || '',
            apiHash: localStorage.getItem(k.apiHash) || '',
            phone: localStorage.getItem(k.phone) || '',
            useTestDc: localStorage.getItem(k.useTestDc) === '1'
        };
    },
    write({remember, apiId, apiHash, phone, useTestDc}) {
        const k = this.k;
        localStorage.setItem(k.remember, remember ? '1' : '0');
        if (remember) {
            localStorage.setItem(k.apiId, String(apiId ?? ''));
            localStorage.setItem(k.apiHash, apiHash ?? '');
            localStorage.setItem(k.phone, phone ?? '');
            localStorage.setItem(k.useTestDc, useTestDc ? '1' : '0');
        } else {
            localStorage.removeItem(k.apiId);
            localStorage.removeItem(k.apiHash);
            localStorage.removeItem(k.phone);
            localStorage.removeItem(k.useTestDc);
        }
    }
};

function hydrateFromStorage() {
    const st = LS.read();
    s1.remember.checked = st.remember;
    if (st.remember) {
        s1.apiId.value = st.apiId;
        s1.apiHash.value = st.apiHash;
        s1.phone.value = st.phone;
        s1.useTestDc.checked = st.useTestDc;
    }
}

let pollTimer = null;
// цикл опроса статуса авторизации до READY
function pollStatus() {
    clearInterval(pollTimer);
    pollTimer = setInterval(async () => {
        try {
            const st = await api.get('/api/webauth/status');
            if (!st?.ok) return;

            if (st.state === 'WAIT_CODE') {
                s2.status.textContent = 'Ожидаем код';
                s2.pwdWrap.classList.add('hidden');
            } else if (st.state === 'WAIT_PASSWORD') {
                s2.status.textContent = 'Требуется пароль 2FA';
                s2.pwdWrap.classList.remove('hidden');
            } else if (READY_STATES.has(st.state)) {
                s2.status.textContent = 'Готово! Переход...';
                clearInterval(pollTimer);
                safeRedirect(PATHS.APP);
            } else {
                s2.status.textContent = 'Статус: ' + st.state;
            }
        } catch {
            // молча продолжаем опрос
        }
    }, 1000);
}

// шаг 1 — отправка кода
s1.btn.addEventListener('click', async () => {
    const apiId    = parseInt((s1.apiId.value || '').trim(), 10);
    const apiHash  = (s1.apiHash.value || '').trim();
    const phone    = (s1.phone.value || '').trim();
    const useTestDc = !!s1.useTestDc.checked;

    // проверяет заполнение полей
    if (!apiId || !apiHash || !phone) {
        s1.status.textContent = 'Заполните все поля';
        s1.status.style.backgroundColor = '#ffecec';
        s1.status.style.color = '#e74c3c';
        return;
    }

    LS.write({remember: s1.remember.checked, apiId, apiHash, phone, useTestDc});

    s1.btn.disabled = true;
    s1.status.textContent = 'Отправляем код...';
    try {
        const res = await api.post('/api/webauth/start', {apiId, apiHash, phone, useTestDc});
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

// шаг 2 — подтверждение кода/пароля
s2.btn.addEventListener('click', async () => {
    const code = s2.code.value.trim();
    const password = s2.password.value.trim();

    // если пароль ещё не запросили — код обязателен
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

        // если сразу READY — уходим в приложение
        if (READY_STATES.has(res.state)) {
            clearInterval(pollTimer);
            safeRedirect(PATHS.APP);
            return;
        }

        // иначе обновим UI и начнём опрос, чтобы дождаться READY
        if (res.state === 'WAIT_PASSWORD') s2.pwdWrap.classList.remove('hidden');
        s2.status.textContent = 'Статус: ' + res.state + ' — ждём подтверждения...';
        pollStatus();
    } catch (e) {
        s2.status.textContent = 'Ошибка: ' + e.message;
        s2.status.style.backgroundColor = '#ffecec';
        s2.status.style.color = '#e74c3c';
    } finally {
        s2.btn.disabled = false;
    }
});

// если уже авторизованы — сразу в приложение
(async () => {
    try {
        hydrateFromStorage();
        const st = await api.get('/api/webauth/status');
        if (st.ok && READY_STATES.has(st.state)) safeRedirect(PATHS.APP);
    } catch {}
})();
