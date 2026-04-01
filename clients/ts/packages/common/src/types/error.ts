export type HalErrorCode =
  | 'parse_error'
  | 'invalid_method'
  | 'invalid_params'
  | 'unauthorized'
  | 'forbidden'
  | 'invalid_key'
  | 'key_expired'
  | 'restriction_mismatch'
  | 'user_denied'
  | 'timeout'
  | 'device_unavailable'
  | 'device_busy'
  | 'plugin_error'
  | 'rate_limited'
  | 'experimental_method_disabled'
  | 'no_handler';

export interface HalErrorResponse {
  error: string;
  message: string;
}

export class HalError extends Error {
  readonly code: string;
  readonly httpStatus?: number;

  constructor(code: string, message: string, httpStatus?: number) {
    super(message);
    this.name = 'HalError';
    this.code = code;
    this.httpStatus = httpStatus;
  }
}

export class HalTransportError extends Error {
  readonly cause?: unknown;

  constructor(message: string, cause?: unknown) {
    super(message);
    this.name = 'HalTransportError';
    this.cause = cause;
  }
}

export class HalTimeoutError extends HalTransportError {
  constructor(message = 'Request timed out') {
    super(message);
    this.name = 'HalTimeoutError';
  }
}

export class HalConnectionError extends HalTransportError {
  constructor(message = 'Connection failed', cause?: unknown) {
    super(message, cause);
    this.name = 'HalConnectionError';
  }
}

export function isHalErrorResponse(value: unknown): value is HalErrorResponse {
  return (
    typeof value === 'object' &&
    value !== null &&
    'error' in value &&
    'message' in value &&
    typeof (value as HalErrorResponse).error === 'string' &&
    typeof (value as HalErrorResponse).message === 'string'
  );
}

export function createHalError(
  response: HalErrorResponse,
  httpStatus?: number,
): HalError {
  return new HalError(response.error, response.message, httpStatus);
}
