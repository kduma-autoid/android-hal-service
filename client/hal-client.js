/**
 * HAL Service JavaScript client. Provides HTTP and WebSocket APIs for communicating
 * with the HAL Service. Supports token management, command execution, event subscriptions
 * with wildcard patterns, and WebSocket auto-reconnect.
 */
class HalClient {
    constructor(baseUrl = 'http://localhost:8400') {
        this.baseUrl = baseUrl.replace(/\/$/, '');
        this.token = null;
        this.ws = null;
        this.wsCallbacks = new Map();
        this.eventHandlers = [];
        this.msgId = 0;
        this.reconnectAttempts = 0;
        this.maxReconnectAttempts = 10;
        this.autoReconnect = true;
    }

    // --- HTTP API ---

    async requestToken(clientId, serviceKey = null, requestedPermissions = null) {
        const body = { clientId };
        if (serviceKey) body.serviceKey = serviceKey;
        if (requestedPermissions) body.requestedPermissions = requestedPermissions;
        const res = await fetch(`${this.baseUrl}/api/token`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        const data = await res.json();
        if (data.token) this.token = data.token;
        return data;
    }

    async execute(method, params = {}) {
        if (!this.token) throw new Error('Not authenticated. Call requestToken first.');
        const res = await fetch(`${this.baseUrl}/api/execute`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${this.token}`
            },
            body: JSON.stringify({ method, params })
        });
        return await res.json();
    }

    async getHealth() {
        const res = await fetch(`${this.baseUrl}/api/health`);
        return await res.json();
    }

    async getStatus() {
        if (!this.token) throw new Error('Not authenticated.');
        const res = await fetch(`${this.baseUrl}/api/status`, {
            headers: { 'Authorization': `Bearer ${this.token}` }
        });
        return await res.json();
    }

    async getDescribe() {
        if (!this.token) throw new Error('Not authenticated.');
        const res = await fetch(`${this.baseUrl}/api/describe`, {
            headers: { 'Authorization': `Bearer ${this.token}` }
        });
        return await res.json();
    }

    // --- WebSocket API ---

    connectWs() {
        return new Promise((resolve, reject) => {
            const wsUrl = this.baseUrl.replace(/^http/, 'ws') + '/ws';
            this.ws = new WebSocket(wsUrl);

            this.ws.onopen = () => {
                this.reconnectAttempts = 0;
                resolve();
            };

            this.ws.onmessage = (event) => {
                const msg = JSON.parse(event.data);
                if (msg.id && this.wsCallbacks.has(msg.id)) {
                    const cb = this.wsCallbacks.get(msg.id);
                    this.wsCallbacks.delete(msg.id);
                    cb(msg);
                }
                if (msg.type === 'event') {
                    this.eventHandlers.forEach(h => {
                        if (this._matchPattern(h.pattern, msg.event)) {
                            h.callback(msg.event, msg.data);
                        }
                    });
                }
            };

            this.ws.onclose = () => {
                if (this.autoReconnect && this.reconnectAttempts < this.maxReconnectAttempts) {
                    const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempts), 30000);
                    this.reconnectAttempts++;
                    setTimeout(() => this.connectWs().catch(() => {}), delay);
                }
            };

            this.ws.onerror = (err) => reject(err);
        });
    }

    wsRequestToken(clientId, serviceKey = null, requestedPermissions = null) {
        const msg = { type: 'requestToken', clientId };
        if (serviceKey) msg.serviceKey = serviceKey;
        if (requestedPermissions) msg.requestedPermissions = requestedPermissions;
        return this._wsSend(msg).then(res => {
            if (res.result?.token) this.token = res.result.token;
            return res;
        });
    }

    wsAuthenticate(token = null) {
        if (token) this.token = token;
        return this._wsSend({ type: 'authenticate', token: this.token });
    }

    wsExecute(method, params = {}) {
        return this._wsSend({ type: 'command', method, params: JSON.stringify(params) });
    }

    wsSubscribe(events) {
        return this._wsSend({ type: 'subscribe', events });
    }

    wsUnsubscribe(events) {
        return this._wsSend({ type: 'unsubscribe', events });
    }

    on(pattern, callback) {
        this.eventHandlers.push({ pattern, callback });
    }

    off(pattern) {
        this.eventHandlers = this.eventHandlers.filter(h => h.pattern !== pattern);
    }

    disconnect() {
        this.autoReconnect = false;
        if (this.ws) this.ws.close();
    }

    // --- Internal ---

    _wsSend(msg) {
        return new Promise((resolve, reject) => {
            const id = String(++this.msgId);
            msg.id = id;
            this.wsCallbacks.set(id, resolve);
            setTimeout(() => {
                if (this.wsCallbacks.has(id)) {
                    this.wsCallbacks.delete(id);
                    reject(new Error('WebSocket request timeout'));
                }
            }, 30000);
            this.ws.send(JSON.stringify(msg));
        });
    }

    _matchPattern(pattern, eventName) {
        if (pattern === '*') return true;
        if (pattern.endsWith('.*')) {
            return eventName.startsWith(pattern.slice(0, -1));
        }
        return pattern === eventName;
    }
}
