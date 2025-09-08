// Базовый HTTP-клиент для REST вызовов бэкенда.
export class ApiClient {
    constructor(baseUrl = '') { this.baseUrl = baseUrl; }

    async request(endpoint, options = {}) {
        const res = await fetch(`${this.baseUrl}${endpoint}`, {
            headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
            ...options
        });
        const contentType = res.headers.get('content-type') || '';
        const parse = async () => contentType.includes('application/json') ? res.json() : res.text();

        if (!res.ok) {
            const msg = await parse().catch(() => res.statusText);
            throw new Error(typeof msg === 'string' ? msg : JSON.stringify(msg));
        }
        return parse();
    }

    get(endpoint)     { return this.request(endpoint, { method: 'GET' }); }
    post(endpoint, d) { return this.request(endpoint, { method: 'POST', body: JSON.stringify(d || {}) }); }
    del(endpoint)     { return this.request(endpoint, { method: 'DELETE' }); }
}
