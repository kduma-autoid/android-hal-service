import type { IEventTransport } from './interfaces/event-transport.js';
import type { IEventSubscriber } from './interfaces/event-subscriber.js';
import type { EventMeta } from './types/event.js';

export class EventSubscriberAdapter implements IEventSubscriber {
  private readonly handlers = new Map<string, Set<Function>>();
  /** A subscribe in flight per pattern, so concurrent first `on()` calls share it. */
  private readonly subscribing = new Map<string, Promise<Set<Function>>>();

  constructor(private readonly transport: IEventTransport) {}

  async on<T = unknown>(
    event: string,
    handler: (eventName: string, data: T, meta?: EventMeta) => void,
  ): Promise<() => Promise<void>> {
    const set = this.handlers.get(event) ?? (await this.subscribeOnce(event));

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

  /**
   * Subscribes [event] on the transport and records the pattern — once, however many `on()` calls
   * arrive before the transport answers. Two concurrent first calls used to each subscribe and each
   * record a fresh Set, the second overwriting the first: its handler was orphaned and the count
   * behind the final unsubscribe was wrong.
   *
   * The pattern is recorded only once the transport accepted it. Recording it first meant a failed
   * subscribe (a `forbidden` from the permission gate, a timeout) left an empty Set behind, and the
   * next `on()` skipped subscribe and resolved happily with nothing subscribed server-side. A failure
   * rejects every caller waiting on it, and the next `on()` tries again.
   */
  private subscribeOnce(event: string): Promise<Set<Function>> {
    const inFlight = this.subscribing.get(event);
    if (inFlight) return inFlight;

    const subscribe = (async () => {
      await this.transport.subscribe([event]);
      const set = new Set<Function>();
      this.handlers.set(event, set);
      return set;
    })();
    this.subscribing.set(event, subscribe);
    // Cleared whichever way it settles; attached after the entry is set, so even a subscribe that
    // throws synchronously cannot leave a rejected promise behind for later calls.
    const clear = () => {
      this.subscribing.delete(event);
    };
    subscribe.then(clear, clear);
    return subscribe;
  }
}
