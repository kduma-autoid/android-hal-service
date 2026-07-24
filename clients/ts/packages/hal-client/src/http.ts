import { HttpCommandTransport } from '@kduma-autoid/hal-client-transport-http';
import { HalClient } from './hal-client.js';
import type { HalClientOptions } from './hal-client-options.js';
import { resolveBaseUrl } from './hal-client-options.js';

export function createHttpHalClient(options: HalClientOptions): HalClient {
  const baseUrl = resolveBaseUrl(options);
  const transport = new HttpCommandTransport({
    baseUrl,
    timeout: options.timeout,
    logger: options.logger,
  });
  return new HalClient(options)
    .useAuthTransport(transport)
    .useCommandTransport(transport);
}

export { HalClient } from './hal-client.js';
export type { HalClientOptions } from './hal-client-options.js';
