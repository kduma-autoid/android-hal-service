import { WsConnection, WsCommandTransport, WsEventTransport } from '@kduma-autoid/hal-client-transport-ws';
import { HalClient } from './hal-client.js';
import type { HalClientOptions } from './hal-client-options.js';
import { resolveBaseUrl } from './hal-client-options.js';

export function createWsHalClient(options: HalClientOptions): HalClient {
  const wsUrl = resolveBaseUrl(options, 'ws') + '/ws';
  const connection = new WsConnection({
    url: wsUrl,
    autoReconnect: options.autoReconnect,
    maxReconnectAttempts: options.maxReconnectAttempts,
    requestTimeout: options.timeout,
    logger: options.logger,
  });
  const cmdTransport = new WsCommandTransport(connection, options.logger);
  const evtTransport = new WsEventTransport(connection, options.logger);
  return new HalClient(options)
    .useConnection(connection)
    .useAuthTransport(cmdTransport)
    .useCommandTransport(cmdTransport)
    .useEventTransport(evtTransport);
}

export { HalClient } from './hal-client.js';
export type { HalClientOptions } from './hal-client-options.js';
