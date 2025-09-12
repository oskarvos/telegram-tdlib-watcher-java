// apiClient
/** Клиент для HTTP-запросов к backend API. */
export class ApiClient {
    constructor(baseUrl = '') {
        this.baseUrl = baseUrl; // базовый URL API
    }

    async request(endpoint, options = {}) {
        const res = await fetch(`${this.baseUrl}${endpoint}`, {
            headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
            ...options
        });
        const ct = res.headers.get('content-type') || '';
        const parse = async () => ct.includes('application/json') ? res.json() : res.text();
        const data = await parse().catch(() => null);

        if (!res.ok) {
            // формируем читаемое сообщение об ошибке
            const msg = typeof data === 'string'
                ? data
                : (data && (data.message || JSON.stringify(data))) || res.statusText;
            throw new Error(msg);
        }
        return data;
    }

    get(endpoint) { return this.request(endpoint, { method: 'GET' }); }           // GET
    post(endpoint, d) { return this.request(endpoint, { method: 'POST', body: JSON.stringify(d || {}) }); } // POST
    del(endpoint) { return this.request(endpoint, { method: 'DELETE' }); }        // DELETE
}

