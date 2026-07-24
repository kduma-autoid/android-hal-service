import type {
  IEventTransport,
  EventHandler,
  ILogger,
} from '@kduma-autoid/hal-client-common';
import { HalError, matchPattern } from '@kduma-autoid/hal-client-common';
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
  private unsubscribeFromConnection: (() => void) | null = null;

  constructor(connection: WsConnection, logger?: ILogger) {
    this.connection = connection;
    this.logger = logger;

    this.unsubscribeFromConnection = this.connection.onMessage((msg: WsServerMessage) => {
      if (msg.type === 'event') {
        this.dispatchEvent(msg.event, msg.data, msg.source);
      }
    });
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

    this.logger?.debug('Subscribed to events', { events });
  }

  async unsubscribe(events: string[]): Promise<void> {
    this.logger?.debug('Unsubscribing from events', { events });

    const response = await this.connection.send({
      type: 'unsubscribe',
      events,
    });

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

    if (this.unsubscribeFromConnection !== null) {
      this.unsubscribeFromConnection();
      this.unsubscribeFromConnection = null;
    }

    this.logger?.debug('WsEventTransport disposed');
  }

  private dispatchEvent(eventName: string, data: unknown, source?: string): void {
    this.logger?.debug('Dispatching event', { event: eventName, source });

    for (const entry of this.handlers) {
      if (matchPattern(entry.pattern, eventName)) {
        try {
          entry.handler(eventName, data, { source });
        } catch (e) {
          this.logger?.error('Event handler threw', e);
        }
      }
    }
  }
}
