import type {
  IEventTransport,
  EventHandler,
  ILogger,
} from '@kduma-autoid/hal-client-common';
import { HalError, matchSubscription } from '@kduma-autoid/hal-client-common';
import type { WsConnection } from './ws-connection.js';
import type { WsServerMessage } from './types/message.js';

interface HandlerEntry {
  pattern: string;
  handler: EventHandler;
}

export class WsEventTransport implements IEventTransport {
  private readonly connection: WsConnection;
  private readonly logger?: ILogger;
  private token: string | null = null;
  private handlers: HandlerEntry[] = [];
  /** Event patterns the server accepted for this client — re-subscribed on every new socket. */
  private readonly serverSubscriptions = new Set<string>();
  private unsubscribeFromConnection: (() => void) | null = null;
  private removeSessionHook: (() => void) | null = null;

  constructor(connection: WsConnection, logger?: ILogger) {
    this.connection = connection;
    this.logger = logger;

    this.unsubscribeFromConnection = this.connection.onMessage((msg: WsServerMessage) => {
      if (msg.type === 'event') {
        this.dispatchEvent(msg.event, msg.data, msg.source);
      }
    });

    // Subscriptions live in the server's per-socket session: restore them after authenticating
    // (order 10, after the command transport's authenticate at order 0).
    this.removeSessionHook = this.connection.addSessionHook(async (send) => {
      if (this.serverSubscriptions.size === 0) return;
      const events = [...this.serverSubscriptions];
      const response = await send({ type: 'subscribe', events });
      if (response.type === 'error') {
        this.logger?.warn('Re-subscribing events failed', { code: response.error.code, events });
        throw new HalError(response.error.code, response.error.message);
      }
      this.logger?.debug('Re-subscribed events', { events });
    }, 10);
  }

  setToken(token: string): void {
    this.token = token;
    this.logger?.debug('Token set');
  }

  getToken(): string | null {
    return this.token;
  }

  async subscribe(events: string[]): Promise<void> {
    this.logger?.debug('Subscribing to events', { events });

    const response = await this.connection.send({
      type: 'subscribe',
      events,
    });

    if (response.type === 'error') {
      throw new HalError(response.error.code, response.error.message);
    }

    for (const e of events) this.serverSubscriptions.add(e);
    this.logger?.debug('Subscribed to events', { events });
  }

  async unsubscribe(events: string[]): Promise<void> {
    this.logger?.debug('Unsubscribing from events', { events });

    const response = await this.connection.send({
      type: 'unsubscribe',
      events,
    });

    for (const e of events) this.serverSubscriptions.delete(e);

    if (response.type === 'error') {
      throw new HalError(response.error.code, response.error.message);
    }

    this.logger?.debug('Unsubscribed from events', { events });
  }

  on<T = unknown>(pattern: string, handler: EventHandler<T>): () => void {
    const entry: HandlerEntry = { pattern, handler: handler as EventHandler };
    this.handlers.push(entry);

    return () => {
      this.handlers = this.handlers.filter((h) => h !== entry);
    };
  }

  off(pattern: string): void {
    this.handlers = this.handlers.filter((h) => h.pattern !== pattern);
  }

  dispose(): void {
    this.handlers = [];
    this.serverSubscriptions.clear();
    this.removeSessionHook?.();
    this.removeSessionHook = null;

    if (this.unsubscribeFromConnection !== null) {
      this.unsubscribeFromConnection();
      this.unsubscribeFromConnection = null;
    }

    this.logger?.debug('WsEventTransport disposed');
  }

  private dispatchEvent(eventName: string, data: unknown, source?: string): void {
    this.logger?.debug('Dispatching event', { event: eventName, source });

    for (const entry of this.handlers) {
      if (matchSubscription(entry.pattern, eventName, source)) {
        try {
          entry.handler(eventName, data, { source });
        } catch (e) {
          this.logger?.error('Event handler threw', e);
        }
      }
    }
  }
}
