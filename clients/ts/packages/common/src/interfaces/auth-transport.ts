import type { TokenRequest, TokenResult } from '../types/token.js';

export interface IAuthTransport {
  requestToken(request: TokenRequest): Promise<TokenResult>;
}
