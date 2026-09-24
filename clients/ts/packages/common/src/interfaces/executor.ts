import type { ExecuteOptions } from '../types/command.js';

export interface IExecutor {
  /**
   * Executes a command and returns the result body. Pass {@link ExecuteOptions.onMeta} to also
   * observe response metadata such as the handling provider.
   */
  execute<T = unknown>(method: string, params?: unknown, options?: ExecuteOptions): Promise<T>;
}
