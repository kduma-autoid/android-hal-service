import type { IEventTransport } from './interfaces/event-transport.js';
import type { IEventSubscriber } from './interfaces/event-subscriber.js';

export class EventSubscriberAdapter implements IEventSubscriber {
  private readonly handlers = new Map<string, Set<Function>>();

  constructor(private readonly transport: IEventTransport) {}

  async on<T = unknown>(
    event: string,
    handler: (eventName: string, data: T) => void,
  ): Promise<() => Promise<void>> {
    let set = this.handlers.get(event);
    if (!set) {
      set = new Set();
      this.handlers.set(event, set);
      await this.transport.subscribe([event]);
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
