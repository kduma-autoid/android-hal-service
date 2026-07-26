export interface ExecuteRequest {
  method: string;
  params?: unknown;
}

export interface ExecuteResponse<T = unknown> {
  result?: T;
  error?: string;
  message?: string;
}

/** Transport-level metadata for a command response, delivered alongside the result body. */
export interface CommandMeta {
  /**
   * Plugin id of the provider that handled the call — present for interface methods (the resolved
   * default, or the one pinned via `__provider`), absent for native/system methods.
   */
  provider?: string;
}

/** Options for a command execution. */
export interface ExecuteOptions {
  /**
   * Invoked with response metadata (e.g. the handling provider) when the response arrives, so a
   * caller can observe it without changing what `execute` returns (still just the result body).
   */
  onMeta?: (meta: CommandMeta) => void;
}
