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
export type { HalEvent, EventMeta, EventHandler, EventSubscription } from './event.js';
export type { ExecuteRequest, ExecuteResponse, CommandMeta, ExecuteOptions } from './command.js';
export type {
  DescribeOptions,
  DescribeResponse,
  PluginDescriptor,
  DescriptorGroup,
  MethodDescriptor,
  EventDescriptor,
  InterfaceDescriptor,
  InterfaceFeature,
  InterfaceProvider,
} from './describe.js';
export { allMethods, allEvents, PROVIDER_PARAM_KEY } from './describe.js';
export type { StatusResponse, PluginStatus, TransportStatus } from './status.js';
export type { HealthResponse } from './health.js';
export type { LightColor, FlashStep, LightOptions, LightCapabilities } from './light.js';
export { LIGHT_COLORS } from './light.js';
export type { PrinterFeature, PrinterImageStyle, PrinterCapabilities } from './printer.js';
export { PRINTER_FEATURES } from './printer.js';
export type { ScanResult } from './scanner.js';
