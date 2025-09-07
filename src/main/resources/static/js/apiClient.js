// Базовый HTTP-клиент (fetch + JSON). Делаем глобальным.
(function (global) {
    function ApiClient(baseUrl) {
        this.baseUrl = baseUrl || '';
    }

    ApiClient.prototype.request = async function (endpoint, options) {
        const res = await fetch((this.baseUrl || '') + endpoint, Object.assign({
            headers: {'Content-Type': 'application/json'}
        }, options || {}));
        const ct = res.headers.get('content-type') || '';
        const parse = async () => (ct.includes('application/json') ? res.json() : res.text());
        if (!res.ok) {
            const msg = await parse().catch(() => res.statusText);
            throw new Error(typeof msg === 'string' ? msg : JSON.stringify(msg));
        }
        return parse();
    };

    ApiClient.prototype.get = function (e) {
        return this.request(e, {method: 'GET'});
    };
    ApiClient.prototype.post = function (e, d) {
        return this.request(e, {method: 'POST', body: JSON.stringify(d || {})});
    };
    ApiClient.prototype.del = function (e) {
        return this.request(e, {method: 'DELETE'});
    };

    global.ApiClient = ApiClient;
})(window);
