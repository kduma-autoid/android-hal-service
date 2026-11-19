import { getCurrentScope, onScopeDispose, watch } from 'vue';
import { useHalClient } from './useHalClient';

/** Sent when a plugin is hot-plugged, or an external plugin (bundle APK) connects or goes away. */
export const PLUGINS_CHANGED_EVENT = 'system.plugins.changed';
/** Sent when a provider of an interface is reordered, enabled or disabled. */
export const INTERFACES_CHANGED_EVENT = 'system.interfaces.changed';

const SYSTEM_CHANGE_EVENTS = [PLUGINS_CHANGED_EVENT, INTERFACES_CHANGED_EVENT];

/**
 * Calls [onChange] whenever what `system.status`/`system.describe` return may have changed, so a view
 * showing them can re-read instead of waiting for Refresh. Subscribes while connected and drops the
 * subscriptions on disconnect and when the calling scope (a component, or an `effectScope`) ends.
 *
 * Calls are debounced: one change often arrives as several events — an external plugin going away
 * and the built-in it displaced coming back, or a reorder sent once per provider.
 *
 * Over HTTP there are no events; subscribing fails quietly and the view keeps its Refresh button.
 */
export function useSystemChanges(onChange: () => void, debounceMs = 150): void {
  const { client, isConnected } = useHalClient();

  let offs: (() => Promise<void>)[] = [];
  let generation = 0;
  let timer: ReturnType<typeof setTimeout> | null = null;

  function schedule() {
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => {
      timer = null;
      onChange();
    }, debounceMs);
  }

  function unsubscribe() {
    generation++;
    if (timer) {
      clearTimeout(timer);
      timer = null;
    }
    const toRelease = offs;
    offs = [];
    for (const off of toRelease) off().catch(() => { /* connection already gone */ });
  }

  async function subscribe() {
    unsubscribe();
    const c = client.value;
    if (!c || !isConnected.value) return;
    const mine = generation;
    await Promise.all(
      SYSTEM_CHANGE_EVENTS.map((name) =>
        c
          .on(name, schedule)
          .then((off) => {
            // Disconnected or unmounted while the subscribe was in flight: release it at once.
            if (mine === generation) offs.push(off);
            else off().catch(() => {});
          })
          .catch(() => { /* no event transport (HTTP) */ }),
      ),
    );
  }

  watch(
    [client, isConnected],
    ([, connected]) => {
      if (connected) subscribe();
      else unsubscribe();
    },
    { immediate: true },
  );

  if (getCurrentScope()) onScopeDispose(unsubscribe);
}
