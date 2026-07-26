import type { ITokenAware } from './token-aware.js';
import type { CommandResultWithMeta } from '../types/command.js';

export interface ICommandTransport extends ITokenAware {
  execute<T = unknown>(method: string, params?: unknown): Promise<T>;
  /**
   * Like {@link execute}, but also returns transport-level response metadata (e.g. the handling
   * provider). Optional — transports that can't surface metadata omit it, and callers fall back to
   * {@link execute} with empty meta.
   */
  executeWithMeta?<T = unknown>(method: string, params?: unknown): Promise<CommandResultWithMeta<T>>;
  dispose(): void;
}
