export type { TokenRequest, TokenResult } from './token.js';
export type { HalErrorCode, HalErrorResponse } from './error.js';
export {
  HalError,
  HalTransportError,
  HalTimeoutError,
  HalConnectionError,
  isHalErrorResponse,
  createHalError,
} from './error.js';
export type { HalEvent, EventHandler, EventSubscription } from './event.js';
export type { ExecuteRequest, ExecuteResponse } from './command.js';
export type {
  DescribeOptions,
  DescribeResponse,
  PluginDescriptor,
  DescriptorGroup,
  MethodDescriptor,
  EventDescriptor,
} from './describe.js';
export { allMethods, allEvents } from './describe.js';
export type { StatusResponse, PluginStatus, TransportStatus } from './status.js';
export type { HealthResponse } from './health.js';
