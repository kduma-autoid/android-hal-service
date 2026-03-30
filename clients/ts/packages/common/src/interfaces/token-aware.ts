export interface ITokenAware {
  setToken(token: string): void;
  getToken(): string | null;
}
