import type { InterfaceProvider } from '@kduma-autoid/hal-client-common';

/**
 * Wraps an async function so calls run one after another instead of interleaving. A view re-binds on
 * every `system.interfaces.changed`, and two of those in a row would otherwise run two binds at once:
 * the later one could finish first and be overwritten, or leave a subscription nothing unsubscribes.
 * A failed call does not stop the next one.
 */
export function serialized<A extends unknown[]>(fn: (...args: A) => Promise<void>): (...args: A) => Promise<void> {
  let last: Promise<void> | null = null;
  return (...args: A) => {
    const previous = last;
    last = (async () => {
      await previous?.catch(() => {});
      await fn(...args);
    })();
    return last;
  };
}

/**
 * Binds a facade to the pinned backend, or to the interface default when none is pinned. A pin that
 * stopped working — the provider was disabled, unplugged or removed since it was picked — falls back
 * to the default rather than leaving the view unbound while other providers are there. `pinFailed`
 * names the pin that could not be used, so the view can say so.
 */
export async function bindBackend<T>(
  pinned: string | undefined,
  forBackend: (pluginId: string) => Promise<T>,
  create: () => Promise<T>,
): Promise<{ bound: T; pinFailed?: string }> {
  if (!pinned) return { bound: await create() };
  try {
    return { bound: await forBackend(pinned) };
  } catch {
    return { bound: await create(), pinFailed: pinned };
  }
}

/** Providers a picker can bind to; `system.describe` also lists disabled ones so they can be re-enabled. */
export function enabledBackends(backends: InterfaceProvider[]): InterfaceProvider[] {
  return backends.filter((b) => b.enabled !== false);
}
