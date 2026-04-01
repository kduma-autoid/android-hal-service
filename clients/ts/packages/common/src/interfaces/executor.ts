export interface IExecutor {
  execute<T = unknown>(method: string, params?: unknown): Promise<T>;
}
