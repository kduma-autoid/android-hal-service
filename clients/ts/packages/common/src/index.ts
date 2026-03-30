// Types
export type {
  TokenRequest,
  TokenResult,
  HalErrorCode,
  HalErrorResponse,
  HalEvent,
  EventHandler,
  EventSubscription,
  ExecuteRequest,
  ExecuteResponse,
  DescribeOptions,
  DescribeResponse,
  PluginDescriptor,
  MethodDescriptor,
  EventDescriptor,
  StatusResponse,
  PluginStatus,
  TransportStatus,
  HealthResponse,
} from './types/index.js';

export {
  HalError,
  HalTransportError,
  HalTimeoutError,
  HalConnectionError,
  isHalErrorResponse,
  createHalError,
} from './types/index.js';

// Interfaces
export type {
  ITokenAware,
  ConnectionState,
  DisconnectReason,
  ConnectionStateEvent,
  ConnectionStateHandler,
  IConnectable,
  IAuthTransport,
  ICommandTransport,
  IEventTransport,
  ITokenStore,
  ILogger,
} from './interfaces/index.js';

// Implementations
export { InMemoryTokenStore } from './in-memory-token-store.js';

// Utils
export { matchPattern, Deferred } from './utils/index.js';
