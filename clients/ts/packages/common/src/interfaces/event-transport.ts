import type { EventHandler } from '../types/event.js';
import type { ITokenAware } from './token-aware.js';

export interface IEventTransport extends ITokenAware {
  subscribe(events: string[]): Promise<void>;
  unsubscribe(events: string[]): Promise<void>;
  on<T = unknown>(pattern: string, handler: EventHandler<T>): () => void;
  off(pattern: string): void;
  dispose(): void;
}
