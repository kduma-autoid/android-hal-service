export interface HalEvent<T = unknown> {
  type: 'event';
  event: string;
  data: T;
}

export type EventHandler<T = unknown> = (eventName: string, data: T) => void;

export interface EventSubscription {
  pattern: string;
  handler: EventHandler;
}
