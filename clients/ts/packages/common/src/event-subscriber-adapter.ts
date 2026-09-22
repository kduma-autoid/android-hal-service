import type { IEventTransport } from './interfaces/event-transport.js';
import type { IEventSubscriber } from './interfaces/event-subscriber.js';
import type { EventMeta } from './types/event.js';

export class EventSubscriberAdapter implements IEventSubscriber {
  private readonly handlers = new Map<string, Set<Function>>();

  constructor(private readonly transport: IEventTransport) {}

  async on<T = unknown>(
    event: string,
    handler: (eventName: string, data: T, meta?: EventMeta) => void,
  ): Promise<() => Promise<void>> {
    let set = this.handlers.get(event);
    if (!set) {
      // Register the pattern only once the transport has accepted it. Recording it first meant a
      // failed subscribe (a `forbidden` from the permission gate, a timeout) left an empty Set
      // behind, and the next `on()` for the same pattern skipped subscribe and resolved happily
      // with nothing subscribed server-side.
      await this.transport.subscribe([event]);
      set = new Set();
      this.handlers.set(event, set);
    }

    set.add(handler);
    const off = this.transport.on<T>(event, handler);

    return async () => {
      off();
      const s = this.handlers.get(event);
      if (s) {
        s.delete(handler);
        if (s.size === 0) {
          this.handlers.delete(event);
          await this.transport.unsubscribe([event]);
        }
      }
    };
  }
}
