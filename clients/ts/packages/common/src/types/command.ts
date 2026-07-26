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
   * default, or the one pinned via `__provider`). Absent for native/system methods and on older
   * services that don't report it.
   */
  provider?: string;
}

/** A command result paired with its transport-level metadata (see {@link CommandMeta}). */
export interface CommandResultWithMeta<T = unknown> {
  result: T;
  meta: CommandMeta;
}
