export interface IEventSubscriber {
  on<T = unknown>(event: string, handler: (eventName: string, data: T) => void): Promise<() => Promise<void>>;
}
