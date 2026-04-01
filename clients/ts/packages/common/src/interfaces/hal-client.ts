import type { IExecutor } from './executor.js';
import type { IEventSubscriber } from './event-subscriber.js';

export interface IHalClient extends IExecutor, IEventSubscriber {}
