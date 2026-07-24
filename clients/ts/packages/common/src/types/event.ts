export interface HalEvent<T = unknown> {
  type: 'event';
  event: string;
  data: T;
  /** Plugin id that emitted the event (transport header, not part of `data`). */
  source?: string;
}

/** Transport-level event header, delivered alongside the payload. */
export interface EventMeta {
  /** Plugin id that emitted the event. Absent on older services that don't send it. */
  source?: string;
}

export type EventHandler<T = unknown> = (eventName: string, data: T, meta?: EventMeta) => void;

export interface EventSubscription {
  pattern: string;
  handler: EventHandler;
}
