import type {
  IConnectable,
  ConnectionState,
  ConnectionStateHandler,
  ConnectionStateEvent,
  DisconnectReason,
  ILogger,
} from '@kduma-autoid/hal-client-common';
import {
  Deferred,
  HalConnectionError,
  HalTimeoutError,
  HalError,
} from '@kduma-autoid/hal-client-common';
import type {
  IWebSocketAdapter,
  IWebSocketAdapterFactory,
} from './interfaces/ws-adapter.js';
import type { WsServerMessage } from './types/message.js';
import { DefaultWsAdapterFactory } from './default-ws-adapter.js';

export interface WsConnectionOptions {
  url: string;
  wsAdapterFactory?: IWebSocketAdapterFactory;
  /** Reconnect after the connection is lost. Default `true`. */
  autoReconnect?: boolean;
  /** Reconnect attempts before giving up (state `disconnected`). Default: unlimited. */
  maxReconnectAttempts?: number;
  /** Upper bound of the exponential reconnect backoff, in ms. Default 30000. */
  maxReconnectDelay?: number;
  requestTimeout?: number;
  /**
   * How long the connection may stay silent before a heartbeat probe is sent, in ms. A probe that
   * gets no reply within {@link heartbeatTimeout} counts the connection as lost (reason `timeout`) —
   * the browser often never reports a dead socket by itself. `0` disables the heartbeat. Default 10000.
   */
  heartbeatInterval?: number;
  /** How long a heartbeat probe may wait for any reply, in ms. Default 5000. */
  heartbeatTimeout?: number;
  /**
   * In a browser, check the connection when the tab becomes visible again or the network comes back
   * (`visibilitychange` / `online`), and retry a pending reconnect right away. Default `true`.
   */
  watchBrowserEvents?: boolean;
  logger?: ILogger;
}

/** Sends a message on the socket being set up, bypassing the "wait until connected" gate. */
export type SessionSend = (msg: Record<string, unknown>) => Promise<WsServerMessage>;

/**
 * Restores per-socket server state — the server keeps the token and event subscriptions per WebSocket
 * session, so a new socket starts with neither. Hooks run in `order` after every (re)connect and on
 * {@link WsConnection.restoreSession}, before other requests may use the connection. A hook that
 * throws stops the hooks after it (they depend on it, e.g. subscribing needs authentication).
 */
export type SessionHook = (send: SessionSend) => Promise<void>;

interface PendingRequest {
  deferred: Deferred<WsServerMessage>;
  timer: ReturnType<typeof setTimeout>;
  type: unknown;
}

interface ReadyWaiter {
  deferred: Deferred<void>;
  timer: ReturnType<typeof setTimeout>;
}

let nextId = 0;

function generateId(): string {
  return `msg_${++nextId}_${Date.now()}`;
}

/** The probe sent as a heartbeat. Any reply proves the link — even an `unauthorized` error. */
const HEARTBEAT_PROBE = { type: 'command', method: 'system.ping', params: {} } as const;

export class WsConnection implements IConnectable {
  private readonly url: string;
  private readonly wsAdapterFactory: IWebSocketAdapterFactory;
  private readonly autoReconnect: boolean;
  private readonly maxReconnectAttempts: number;
  private readonly maxReconnectDelay: number;
  private readonly requestTimeout: number;
  private readonly heartbeatInterval: number;
  private readonly heartbeatTimeout: number;
  private readonly watchBrowserEvents: boolean;
  private readonly logger?: ILogger;

  private ws: IWebSocketAdapter | null = null;
  /**
   * Bumped whenever a socket is abandoned. Callbacks from an older socket compare their generation
   * and are ignored — a dead socket's late `close`, or both `error` and `close` of one failed attempt,
   * can no longer schedule extra reconnects.
   */
  private generation = 0;
  private _connectionState: ConnectionState = 'disconnected';
  private stateHandlers: ConnectionStateHandler[] = [];
  private pendingRequests = new Map<string, PendingRequest>();
  private readyWaiters = new Set<ReadyWaiter>();
  private messageHandlers: Array<(msg: WsServerMessage) => void> = [];
  private sessionHooks: Array<{ order: number; hook: SessionHook }> = [];

  private connectPromise: Promise<void> | null = null;
  /** Session hooks re-running on a live connection (token change); other requests wait for it. */
  private restoring: Promise<void> | null = null;

  private reconnectAttempts = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  /** Set by disconnect(): no reconnecting until the next connect(). */
  private stopped = true;

  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private probe: Promise<boolean> | null = null;
  private lastMessageAt = 0;
  private browserListening = false;

  constructor(options: WsConnectionOptions) {
    this.url = options.url;
    this.wsAdapterFactory = options.wsAdapterFactory ?? new DefaultWsAdapterFactory();
    this.autoReconnect = options.autoReconnect ?? true;
    this.maxReconnectAttempts = options.maxReconnectAttempts ?? Infinity;
    this.maxReconnectDelay = options.maxReconnectDelay ?? 30000;
    this.requestTimeout = options.requestTimeout ?? 30000;
    this.heartbeatInterval = options.heartbeatInterval ?? 10000;
    this.heartbeatTimeout = options.heartbeatTimeout ?? 5000;
    this.watchBrowserEvents = options.watchBrowserEvents ?? true;
    this.logger = options.logger;
  }

  get connectionState(): ConnectionState {
    return this._connectionState;
  }

  onConnectionStateChange(handler: ConnectionStateHandler): () => void {
    this.stateHandlers.push(handler);
    return () => {
      this.stateHandlers = this.stateHandlers.filter((h) => h !== handler);
    };
  }

  /** Registers a {@link SessionHook}; lower `order` runs first. Returns a function removing it. */
  addSessionHook(hook: SessionHook, order = 0): () => void {
    const entry = { order, hook };
    this.sessionHooks.push(entry);
    this.sessionHooks.sort((a, b) => a.order - b.order);
    return () => {
      this.sessionHooks = this.sessionHooks.filter((h) => h !== entry);
    };
  }

  /**
   * Re-runs the session hooks on the live connection (e.g. after a new token was set); requests sent
   * meanwhile wait for it. Without a live connection there is nothing to do — the hooks run on the
   * next (re)connect anyway.
   */
  restoreSession(): Promise<void> {
    if (this._connectionState !== 'connected' || this.ws === null) return Promise.resolve();
    const gen = this.generation;
    const run = (this.restoring ?? Promise.resolve()).then(() =>
      gen === this.generation ? this.runSessionHooks(gen) : undefined,
    );
    const tracked: Promise<void> = run.finally(() => {
      if (this.restoring === tracked) this.restoring = null;
    });
    this.restoring = tracked;
    return tracked;
  }

  connect(): Promise<void> {
    if (this._connectionState === 'connected') {
      return Promise.resolve();
    }
    if (this.connectPromise !== null) {
      return this.connectPromise;
    }

    this.stopped = false;
    this.attachBrowserListeners();
    // An explicit connect() replaces any pending automatic reconnect.
    this.cancelReconnect();

    const attempt = this.openSocket('connecting').catch((error: unknown) => {
      const err = error instanceof Error ? error : new HalConnectionError('WebSocket connection failed', error);
      if (!this.stopped) {
        // A first connection that failed (even one dropped while restoring the session) is not retried
        // automatically: the caller gets the error.
        this.cancelReconnect();
        this.setConnectionState('disconnected', 'error', err);
        this.rejectWaiters(err);
      }
      throw err;
    });
    const tracked = attempt.finally(() => {
      if (this.connectPromise === tracked) this.connectPromise = null;
    });
    this.connectPromise = tracked;
    return tracked;
  }

  disconnect(permanent?: boolean): void {
    this.stopped = true;
    this.generation++;

    this.cancelReconnect();
    this.stopHeartbeat();

    const ws = this.ws;
    this.ws = null;
    if (ws !== null) {
      try {
        ws.close(1000, 'Client disconnect');
      } catch {
        /* already closed */
      }
    }

    const error = new HalConnectionError(permanent ? 'Connection closed permanently' : 'Disconnected');
    this.rejectPending(error);
    this.rejectWaiters(error);

    if (permanent) {
      this.messageHandlers = [];
      this.sessionHooks = [];
      this.detachBrowserListeners();
    }

    this.setConnectionState('disconnected', 'manual');
  }

  /**
   * Sends a request and resolves with the matching reply (a `response` or an `error` frame). While
   * the connection is (re)connecting or restoring its session, the request waits for it — up to the
   * request timeout — instead of failing at once.
   */
  async send(msg: Record<string, unknown>, options?: { timeout?: number }): Promise<WsServerMessage> {
    if (this._connectionState === 'connecting' || this._connectionState === 'reconnecting') {
      await this.waitUntilConnected();
    }
    if (this._connectionState !== 'connected' || this.ws === null) {
      msg.id = generateId();
      throw new HalConnectionError('Not connected');
    }
    if (this.restoring !== null) {
      await this.restoring.catch(() => undefined);
    }
    return this.sendRaw(msg, this.generation, options?.timeout ?? this.requestTimeout);
  }

  async authenticate(token: string): Promise<void> {
    const response = await this.send({ type: 'authenticate', token });

    if (response.type === 'error') {
      throw new HalError(response.error.code, response.error.message);
    }
  }

  /**
   * Probes the connection now (instead of waiting for the next heartbeat). Resolves `true` when the
   * server answered; on no answer within the heartbeat timeout the connection is treated as lost and
   * a reconnect starts.
   */
  checkConnection(): Promise<boolean> {
    if (this._connectionState !== 'connected' || this.ws === null) return Promise.resolve(false);
    if (this.probe !== null) return this.probe;
    // A token request may wait up to a minute for the permission dialog, and older servers read no
    // other frame meanwhile — a probe would go unanswered and wrongly drop the connection.
    if (this.tokenRequestPending()) return Promise.resolve(true);

    const gen = this.generation;
    const probe = this.sendRaw({ ...HEARTBEAT_PROBE }, gen, this.heartbeatTimeout)
      .then(() => true)
      .catch((error: unknown) => {
        if (gen === this.generation && error instanceof HalTimeoutError) {
          this.logger?.warn('WebSocket heartbeat got no reply — reconnecting', { timeout: this.heartbeatTimeout });
          this.handleLost('timeout', error);
        }
        return false;
      })
      .finally(() => {
        if (this.probe === probe) this.probe = null;
      });
    this.probe = probe;
    return probe;
  }

  onMessage(handler: (msg: WsServerMessage) => void): () => void {
    this.messageHandlers.push(handler);
    return () => {
      this.messageHandlers = this.messageHandlers.filter((h) => h !== handler);
    };
  }

  // --- Socket lifecycle ---

  /**
   * Opens a socket and resolves once it is open and the session hooks have run. Rejects when this
   * attempt fails before opening; a socket lost later goes through {@link handleLost}.
   */
  private openSocket(state: 'connecting' | 'reconnecting'): Promise<void> {
    const gen = ++this.generation;
    if (this._connectionState !== state) this.setConnectionState(state);
    // Never leave an older socket (e.g. an attempt superseded by connect()) open behind us.
    if (this.ws !== null) {
      try {
        this.ws.close(1000, 'Superseded');
      } catch {
        /* ignore */
      }
      this.ws = null;
    }

    return new Promise<void>((resolve, reject) => {
      let opened = false;
      let ws: IWebSocketAdapter | null = null;

      const fail = (error: Error) => {
        if (gen !== this.generation) return;
        this.generation++; // this attempt is over: ignore whatever else its socket reports
        this.ws = null;
        try {
          ws?.close();
        } catch {
          /* ignore */
        }
        reject(error);
      };

      try {
        ws = this.wsAdapterFactory.create(this.url);
      } catch (error) {
        fail(new HalConnectionError('Failed to create WebSocket', error));
        return;
      }
      this.ws = ws;
      const socket = ws;

      socket.onOpen(() => {
        if (gen !== this.generation) return;
        opened = true;
        this.reconnectAttempts = 0;
        this.lastMessageAt = Date.now();
        this.logger?.info('WebSocket open', { url: this.url });

        this.runSessionHooks(gen).then(() => {
          if (gen !== this.generation) {
            reject(new HalConnectionError('Connection lost while restoring the session'));
            return;
          }
          this.setConnectionState('connected');
          this.resolveWaiters();
          this.startHeartbeat();
          resolve();
        });
      });

      socket.onClose((code, reason) => {
        if (gen !== this.generation) return;
        this.logger?.info('WebSocket closed', { code, reason });
        if (opened) {
          this.handleLost('server_close');
        } else {
          fail(new HalConnectionError(`WebSocket closed before opening (${code})`));
        }
      });

      socket.onMessage((data) => {
        if (gen !== this.generation) return;
        this.handleMessage(data);
      });

      socket.onError((error) => {
        if (gen !== this.generation) return;
        this.logger?.error('WebSocket error', error);
        if (!opened) fail(new HalConnectionError('WebSocket connection failed', error));
      });
    });
  }

  /** The socket is gone (closed by the server, or silent past the heartbeat): clean up and reconnect. */
  private handleLost(reason: DisconnectReason, error?: Error): void {
    this.generation++;
    const ws = this.ws;
    this.ws = null;
    if (ws !== null) {
      try {
        ws.close(4000, reason);
      } catch {
        /* ignore */
      }
    }
    this.stopHeartbeat();
    this.rejectPending(new HalConnectionError('Connection lost'));

    if (this.stopped) {
      this.setConnectionState('disconnected', 'manual');
      return;
    }

    if (this.autoReconnect && this.reconnectAttempts < this.maxReconnectAttempts) {
      this.setConnectionState('reconnecting', reason, error);
      this.scheduleReconnect();
    } else {
      this.setConnectionState('disconnected', reason, error);
      this.rejectWaiters(new HalConnectionError('Connection lost'));
    }
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer !== null) clearTimeout(this.reconnectTimer);

    // 1 s, 2 s, 4 s … capped, with ±20 % jitter so many clients don't reconnect in lockstep.
    const base = Math.min(1000 * Math.pow(2, Math.min(this.reconnectAttempts, 30)), this.maxReconnectDelay);
    const delay = Math.round(base * (0.8 + Math.random() * 0.4));

    this.logger?.info('Scheduling reconnect', { attempt: this.reconnectAttempts + 1, delay });

    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.reconnectAttempts++;
      this.doReconnect();
    }, delay);
  }

  private doReconnect(): void {
    if (this.stopped || this._connectionState === 'connected' || this.connectPromise !== null) return;

    this.openSocket('reconnecting').catch((error: unknown) => {
      if (this.stopped) return;
      const err = error instanceof Error ? error : undefined;
      this.logger?.warn('Reconnect failed', error);

      if (this.reconnectAttempts < this.maxReconnectAttempts) {
        this.setConnectionState('reconnecting', 'error', err);
        this.scheduleReconnect();
      } else {
        this.setConnectionState('disconnected', 'error', err);
        this.rejectWaiters(new HalConnectionError('Reconnect failed'));
        this.logger?.error('Max reconnect attempts reached');
      }
    });
  }

  private async runSessionHooks(gen: number): Promise<void> {
    const send: SessionSend = (msg) => this.sendRaw(msg, gen);
    for (const { hook } of [...this.sessionHooks]) {
      if (gen !== this.generation) return;
      try {
        await hook(send);
      } catch (error) {
        this.logger?.warn('Session restore step failed; skipping the rest', error);
        return;
      }
    }
  }

  // --- Requests ---

  private sendRaw(msg: Record<string, unknown>, gen: number, timeout = this.requestTimeout): Promise<WsServerMessage> {
    const id = generateId();
    msg.id = id;

    const ws = this.ws;
    if (ws === null || gen !== this.generation) {
      return Promise.reject(new HalConnectionError('Not connected'));
    }

    const deferred = new Deferred<WsServerMessage>();
    const timer = setTimeout(() => {
      this.pendingRequests.delete(id);
      deferred.reject(new HalTimeoutError(`Request ${id} timed out after ${timeout}ms`));
    }, timeout);

    this.pendingRequests.set(id, { deferred, timer, type: msg.type });

    try {
      ws.send(JSON.stringify(msg));
      this.logger?.debug('Sent message', { id, type: msg.type });
    } catch (error) {
      clearTimeout(timer);
      this.pendingRequests.delete(id);
      deferred.reject(new HalConnectionError('Failed to send message', error));
    }

    return deferred.promise;
  }

  private tokenRequestPending(): boolean {
    for (const pending of this.pendingRequests.values()) {
      if (pending.type === 'requestToken') return true;
    }
    return false;
  }

  private cancelReconnect(): void {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  private rejectPending(error: Error): void {
    for (const [id, pending] of this.pendingRequests) {
      clearTimeout(pending.timer);
      pending.deferred.reject(error);
      this.pendingRequests.delete(id);
    }
  }

  private waitUntilConnected(): Promise<void> {
    const deferred = new Deferred<void>();
    const waiter: ReadyWaiter = {
      deferred,
      timer: setTimeout(() => {
        this.readyWaiters.delete(waiter);
        deferred.reject(new HalConnectionError(`Not connected (still ${this._connectionState})`));
      }, this.requestTimeout),
    };
    this.readyWaiters.add(waiter);
    return deferred.promise;
  }

  private resolveWaiters(): void {
    for (const waiter of this.readyWaiters) {
      clearTimeout(waiter.timer);
      waiter.deferred.resolve();
    }
    this.readyWaiters.clear();
  }

  private rejectWaiters(error: Error): void {
    for (const waiter of this.readyWaiters) {
      clearTimeout(waiter.timer);
      waiter.deferred.reject(error);
    }
    this.readyWaiters.clear();
  }

  // --- Heartbeat ---

  private startHeartbeat(): void {
    this.stopHeartbeat();
    if (this.heartbeatInterval <= 0) return;
    // Check a few times per interval, so a silent link is probed soon after it falls quiet.
    const tick = Math.max(1000, Math.floor(this.heartbeatInterval / 3));
    this.heartbeatTimer = setInterval(() => {
      if (Date.now() - this.lastMessageAt >= this.heartbeatInterval) {
        void this.checkConnection();
      }
    }, tick);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer !== null) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  // --- Browser wake-ups ---

  private readonly onVisibilityChange = () => {
    const doc = (globalThis as { document?: { visibilityState?: string } }).document;
    if (doc?.visibilityState === 'visible') this.wake();
  };

  private readonly onOnline = () => this.wake();

  /** Tab visible again / network back: verify a live connection, or retry a pending reconnect now. */
  private wake(): void {
    if (this._connectionState === 'connected') {
      void this.checkConnection();
    } else if (this._connectionState === 'reconnecting' && this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
      this.doReconnect();
    }
  }

  private attachBrowserListeners(): void {
    if (!this.watchBrowserEvents || this.browserListening) return;
    const g = globalThis as {
      document?: { addEventListener?: (type: string, fn: () => void) => void };
      addEventListener?: (type: string, fn: () => void) => void;
    };
    g.document?.addEventListener?.('visibilitychange', this.onVisibilityChange);
    g.addEventListener?.('online', this.onOnline);
    this.browserListening = true;
  }

  private detachBrowserListeners(): void {
    if (!this.browserListening) return;
    const g = globalThis as {
      document?: { removeEventListener?: (type: string, fn: () => void) => void };
      removeEventListener?: (type: string, fn: () => void) => void;
    };
    g.document?.removeEventListener?.('visibilitychange', this.onVisibilityChange);
    g.removeEventListener?.('online', this.onOnline);
    this.browserListening = false;
  }

  // --- Dispatch ---

  private setConnectionState(
    state: ConnectionState,
    reason?: ConnectionStateEvent['reason'],
    error?: Error,
  ): void {
    this._connectionState = state;
    const event: ConnectionStateEvent = { state, reason, error };
    for (const handler of this.stateHandlers) {
      try {
        handler(event);
      } catch (e) {
        this.logger?.error('Connection state handler threw', e);
      }
    }
  }

  private handleMessage(data: string): void {
    this.lastMessageAt = Date.now();

    let msg: WsServerMessage;
    try {
      msg = JSON.parse(data) as WsServerMessage;
    } catch {
      this.logger?.error('Failed to parse WebSocket message', data);
      return;
    }

    this.logger?.debug('Received message', { type: msg.type });

    // Check if this is a response to a pending request
    if ('id' in msg && msg.id !== undefined) {
      const pending = this.pendingRequests.get(msg.id);
      if (pending) {
        clearTimeout(pending.timer);
        this.pendingRequests.delete(msg.id);
        pending.deferred.resolve(msg);
        return;
      }
    }

    // Dispatch to message handlers (for events and other unsolicited messages)
    for (const handler of this.messageHandlers) {
      try {
        handler(msg);
      } catch (e) {
        this.logger?.error('Message handler threw', e);
      }
    }
  }
}
