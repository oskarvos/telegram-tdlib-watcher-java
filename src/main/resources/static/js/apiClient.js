// apiClient
export class ApiClient {
    constructor(baseUrl = '') {
        this.baseUrl = baseUrl;
    }

    async request(endpoint, options = {}) {
        const res = await fetch(`${this.baseUrl}${endpoint}`, {
            headers: {'Content-Type': 'application/json', ...(options.headers || {})},
            ...options
        });
        const ct = res.headers.get('content-type') || '';
        const parse = async () => ct.includes('application/json') ? res.json() : res.text();
        const data = await parse().catch(() => null);

        if (!res.ok) {
            const msg = typeof data === 'string' ? data : (data && (data.message || JSON.stringify(data))) || res.statusText;
            throw new Error(msg);
        }
        return data;
    }

    get(endpoint) {
        return this.request(endpoint, {method: 'GET'});
    }

    post(endpoint, d) {
        return this.request(endpoint, {method: 'POST', body: JSON.stringify(d || {})});
    }

    del(endpoint) {
        return this.request(endpoint, {method: 'DELETE'});
    }
}
