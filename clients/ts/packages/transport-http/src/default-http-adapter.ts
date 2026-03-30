import type { IHttpAdapter, HttpRequestOptions, HttpResponse } from './interfaces/http-adapter.js';

export class DefaultHttpAdapter implements IHttpAdapter {
  async request(options: HttpRequestOptions): Promise<HttpResponse> {
    const { url, method, headers, body, timeout } = options;

    const controller = new AbortController();
    let timeoutId: ReturnType<typeof setTimeout> | undefined;

    if (timeout !== undefined && timeout > 0) {
      timeoutId = setTimeout(() => controller.abort(), timeout);
    }

    try {
      const response = await globalThis.fetch(url, {
        method,
        headers,
        body,
        signal: controller.signal,
      });

      const responseHeaders: Record<string, string> = {};
      response.headers.forEach((value, key) => {
        responseHeaders[key] = value;
      });

      const responseBody = await response.text();

      return {
        status: response.status,
        statusText: response.statusText,
        headers: responseHeaders,
        body: responseBody,
      };
    } finally {
      if (timeoutId !== undefined) {
        clearTimeout(timeoutId);
      }
    }
  }
}
