import type { EventMeta } from '../types/event.js';

export interface IEventSubscriber {
  on<T = unknown>(
    event: string,
    handler: (eventName: string, data: T, meta?: EventMeta) => void,
  ): Promise<() => Promise<void>>;
}
