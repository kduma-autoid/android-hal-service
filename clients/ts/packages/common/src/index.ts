// Types
export type {
  TokenRequest,
  TokenResult,
  HalErrorCode,
  HalErrorResponse,
  HalEvent,
  EventMeta,
  EventHandler,
  EventSubscription,
  ExecuteRequest,
  ExecuteResponse,
  CommandMeta,
  ExecuteOptions,
  DescribeOptions,
  DescribeResponse,
  PluginDescriptor,
  DescriptorGroup,
  MethodDescriptor,
  EventDescriptor,
  InterfaceDescriptor,
  InterfaceFeature,
  InterfaceProvider,
  StatusResponse,
  PluginStatus,
  TransportStatus,
  HealthResponse,
  LightColor,
  FlashStep,
  LightOptions,
  LightCapabilities,
  MultiFlash,
  PrinterFeature,
  PrinterImageStyle,
  PrinterCapabilities,
  ScanResult,
} from './types/index.js';

export {
  HalError,
  HalTransportError,
  HalTimeoutError,
  HalConnectionError,
  isHalErrorResponse,
  createHalError,
  allMethods,
  allEvents,
  PROVIDER_SELECTOR,
  methodForProvider,
} from './types/index.js';

export { LIGHT_COLORS } from './types/index.js';
export { PRINTER_FEATURES } from './types/index.js';

// Interfaces
export type {
  ITokenAware,
  ConnectionState,
  DisconnectReason,
  ConnectionStateEvent,
  ConnectionStateHandler,
  IConnectable,
  IExecutor,
  IAuthTransport,
  ICommandTransport,
  IEventTransport,
  IEventSubscriber,
  IHalClient,
  ITokenStore,
  ILogger,
} from './interfaces/index.js';

// Implementations
export { InMemoryTokenStore } from './in-memory-token-store.js';
export { EventSubscriberAdapter } from './event-subscriber-adapter.js';

// Utils
export { matchPattern, matchSubscription, Deferred } from './utils/index.js';
