import type { CommandResultWithMeta } from '../types/command.js';

export interface IExecutor {
  execute<T = unknown>(method: string, params?: unknown): Promise<T>;
  /**
   * Like {@link execute}, but also returns response metadata such as the handling provider.
   * Optional so lightweight client mocks need not implement it; the concrete HalClient always does.
   */
  executeWithMeta?<T = unknown>(method: string, params?: unknown): Promise<CommandResultWithMeta<T>>;
}
