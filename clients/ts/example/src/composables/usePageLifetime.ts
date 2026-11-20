import { onUnmounted } from 'vue';

type Unsubscribe = () => Promise<void>;

/**
 * Guards subscriptions a page makes after an await. Pages bind their backend first and subscribe
 * once that resolves; leaving the page meanwhile ran `onUnmounted` cleanup while there was nothing to
 * release yet, and the subscription that arrived afterwards was never released — it kept the server
 * subscription open and, for change events, re-bound the dead page on every later event.
 *
 * Call in `setup()`. Wrap each subscribe in {@link keep}: once the page is gone, what arrives is
 * released at once and `null` comes back instead.
 */
export function usePageLifetime() {
  let alive = true;
  onUnmounted(() => {
    alive = false;
  });

  return {
    /** False once the page has been unmounted. */
    isAlive: () => alive,

    async keep(subscribing: Promise<Unsubscribe>): Promise<Unsubscribe | null> {
      const off = await subscribing;
      if (alive) return off;
      await off().catch(() => {
        /* connection already gone */
      });
      return null;
    },
  };
}
