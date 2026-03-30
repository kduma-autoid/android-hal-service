import type {
  IConnectable,
  ConnectionState,
  ConnectionStateHandler,
  ConnectionStateEvent,
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
  autoReconnect?: boolean;
  maxReconnectAttempts?: number;
  maxReconnectDelay?: number;
  requestTimeout?: number;
  logger?: ILogger;
}

interface PendingRequest {
  deferred: Deferred<WsServerMessage>;
  timer: ReturnType<typeof setTimeout>;
}

let nextId = 0;

function generateId(): string {
  return `msg_${++nextId}_${Date.now()}`;
}

export class WsConnection implements IConnectable {
  private readonly url: string;
  private readonly wsAdapterFactory: IWebSocketAdapterFactory;
  private readonly autoReconnect: boolean;
  private readonly maxReconnectAttempts: number;
  private readonly maxReconnectDelay: number;
  private readonly requestTimeout: number;
  private readonly logger?: ILogger;

  private ws: IWebSocketAdapter | null = null;
  private _connectionState: ConnectionState = 'disconnected';
  private stateHandlers: ConnectionStateHandler[] = [];
  private pendingRequests = new Map<string, PendingRequest>();
  private messageHandlers: Array<(msg: WsServerMessage) => void> = [];

  private reconnectAttempts = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private permanent = false;

  constructor(options: WsConnectionOptions) {
    this.url = options.url;
    this.wsAdapterFactory = options.wsAdapterFactory ?? new DefaultWsAdapterFactory();
    this.autoReconnect = options.autoReconnect ?? true;
    this.maxReconnectAttempts = options.maxReconnectAttempts ?? 10;
    this.maxReconnectDelay = options.maxReconnectDelay ?? 30000;
    this.requestTimeout = options.requestTimeout ?? 30000;
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

  connect(): Promise<void> {
    if (this._connectionState === 'connected') {
      return Promise.resolve();
    }

    this.permanent = false;

    return new Promise<void>((resolve, reject) => {
      this.setConnectionState('connecting');

      try {
        this.ws = this.wsAdapterFactory.create(this.url);
      } catch (error) {
        this.setConnectionState('disconnected', 'error', error instanceof Error ? error : undefined);
        reject(new HalConnectionError('Failed to create WebSocket', error));
        return;
      }

      this.ws.onOpen(() => {
        this.reconnectAttempts = 0;
        this.setConnectionState('connected');
        this.logger?.info('WebSocket connected', { url: this.url });
        resolve();
      });

      this.ws.onClose((code, reason) => {
        this.logger?.info('WebSocket closed', { code, reason });
        this.handleClose(code, reason);
      });

      this.ws.onMessage((data) => {
        this.handleMessage(data);
      });

      this.ws.onError((error) => {
        this.logger?.error('WebSocket error', error);

        if (this._connectionState === 'connecting') {
          this.setConnectionState('disconnected', 'error', new HalConnectionError('WebSocket connection failed', error));
          reject(new HalConnectionError('WebSocket connection failed', error));
        }
      });
    });
  }

  disconnect(permanent?: boolean): void {
    if (permanent) {
      this.permanent = true;

      // Cancel reconnect timer
      if (this.reconnectTimer !== null) {
        clearTimeout(this.reconnectTimer);
        this.reconnectTimer = null;
      }

      // Reject all pending requests
      for (const [id, pending] of this.pendingRequests) {
        clearTimeout(pending.timer);
        pending.deferred.reject(new HalConnectionError('Connection closed permanently'));
        this.pendingRequests.delete(id);
      }

      // Clear message handlers
      this.messageHandlers = [];
    }

    if (this.ws !== null) {
      this.ws.close(1000, 'Client disconnect');
      this.ws = null;
    }

    this.setConnectionState('disconnected', 'manual');
  }

  send(msg: Record<string, unknown>): Promise<WsServerMessage> {
    const id = generateId();
    msg.id = id;

    if (this.ws === null || this._connectionState !== 'connected') {
      return Promise.reject(
        new HalConnectionError('Not connected'),
      );
    }

    const deferred = new Deferred<WsServerMessage>();

    const timer = setTimeout(() => {
      this.pendingRequests.delete(id);
      deferred.reject(new HalTimeoutError(`Request ${id} timed out after ${this.requestTimeout}ms`));
    }, this.requestTimeout);

    this.pendingRequests.set(id, { deferred, timer });

    try {
      this.ws.send(JSON.stringify(msg));
      this.logger?.debug('Sent message', { id, type: msg.type });
    } catch (error) {
      clearTimeout(timer);
      this.pendingRequests.delete(id);
      deferred.reject(new HalConnectionError('Failed to send message', error));
    }

    return deferred.promise;
  }

  async authenticate(token: string): Promise<void> {
    const response = await this.send({ type: 'authenticate', token });

    if (response.type === 'error') {
      throw new HalError(response.error.code, response.error.message);
    }
  }

  onMessage(handler: (msg: WsServerMessage) => void): () => void {
    this.messageHandlers.push(handler);
    return () => {
      this.messageHandlers = this.messageHandlers.filter((h) => h !== handler);
    };
  }

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

  private handleClose(_code: number, _reason: string): void {
    this.ws = null;

    if (this.permanent) {
      this.setConnectionState('disconnected', 'manual');
      return;
    }

    if (this.autoReconnect && this.reconnectAttempts < this.maxReconnectAttempts) {
      this.setConnectionState('reconnecting', 'server_close');
      this.scheduleReconnect();
    } else {
      this.setConnectionState('disconnected', 'server_close');
    }
  }

  private scheduleReconnect(): void {
    const delay = Math.min(
      1000 * Math.pow(2, this.reconnectAttempts),
      this.maxReconnectDelay,
    );

    this.logger?.info('Scheduling reconnect', {
      attempt: this.reconnectAttempts + 1,
      maxAttempts: this.maxReconnectAttempts,
      delay,
    });

    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.reconnectAttempts++;
      this.doReconnect();
    }, delay);
  }

  private doReconnect(): void {
    if (this.permanent) {
      return;
    }

    this.connect().catch((error) => {
      this.logger?.error('Reconnect failed', error);

      if (this.reconnectAttempts < this.maxReconnectAttempts) {
        this.setConnectionState('reconnecting', 'error', error instanceof Error ? error : undefined);
        this.scheduleReconnect();
      } else {
        this.setConnectionState('disconnected', 'error', error instanceof Error ? error : undefined);
        this.logger?.error('Max reconnect attempts reached');
      }
    });
  }

  private handleMessage(data: string): void {
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
