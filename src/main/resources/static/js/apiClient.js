// Базовый HTTP-клиент для REST вызовов бэка.
// Глобально перехватывает 401/403 и уводит на / (авторизация).

export class ApiClient {
    constructor(baseUrl = '') {
        this.baseUrl = baseUrl;
    }

    static _redirectScheduled = false;

    static redirectToLoginOnce() {
        if (ApiClient._redirectScheduled) return;
        ApiClient._redirectScheduled = true;
        try {
            // Чуть-чуть «смягчим» UX: дадим кадр на отрисовку
            requestAnimationFrame(() => window.location.replace('/'));
        } catch {
            window.location.replace('/');
        }
    }

    async request(endpoint, options = {}) {
        const res = await fetch(`${this.baseUrl}${endpoint}`, {
            method: options.method || 'GET',
            headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
            credentials: 'include',              // важно: куки/сессии
            body: options.body ?? undefined,
        });

        // Прямая проверка статуса
        if (res.status === 401 || res.status === 403) {
            ApiClient.redirectToLoginOnce();
            throw new Error('Требуется авторизация');
        }

        const ct = res.headers.get('content-type') || '';
        const parse = async () => ct.includes('application/json') ? res.json() : res.text();
        const data = await parse().catch(() => null);

        if (!res.ok) {
            // Если бэкенд отдаёт «мягкий» признак неавторизованности в JSON — тоже уводим
            const softUnauth = data && (data.ok === false) &&
                (data.state === 'NO_AUTH' || data.code === 'UNAUTHORIZED');
            if (softUnauth) {
                ApiClient.redirectToLoginOnce();
                throw new Error('Требуется авторизация');
            }
            const msg = typeof data === 'string'
                ? data
                : (data?.message || res.statusText);
            throw new Error(msg);
        }

        // Доп. страховка для JSON-ответов вида {ok:false}
        if (data && data.ok === false &&
            (data.state === 'NO_AUTH' || data.code === 'UNAUTHORIZED')) {
            ApiClient.redirectToLoginOnce();
            throw new Error('Требуется авторизация');
        }

        return data;
    }

    get(endpoint)       { return this.request(endpoint, { method: 'GET'  }); }
    post(endpoint, d)   { return this.request(endpoint, { method: 'POST',   body: JSON.stringify(d || {}) }); }
    del(endpoint)       { return this.request(endpoint, { method: 'DELETE' }); }
}
