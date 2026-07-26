/**
 * Match an event name against a subscription pattern.
 * Port of EventBus.matchesPattern() from hal-contract.
 *
 * Supports:
 * - Exact match: "scanner.barcode" matches "scanner.barcode"
 * - Wildcard prefix: "rfid.*" matches "rfid.tag_read", "rfid.status"
 * - Global wildcard: "*" matches everything
 */
export function matchPattern(pattern: string, eventName: string): boolean {
  if (pattern === '*') return true;
  if (pattern.endsWith('.*')) {
    const prefix = pattern.slice(0, -1); // keep the dot: "rfid."
    return eventName.startsWith(prefix);
  }
  return pattern === eventName;
}

/**
 * Match an event against a subscription of the form `namePattern[@sourcePattern]`.
 * Both halves use {@link matchPattern}; a missing `@source` matches any emitter — e.g.
 * `scanner.*@sunmi.*` or `demo.notice@demo.beta`. Port of EventBus.matchesSubscription().
 */
export function matchSubscription(subscription: string, eventName: string, source?: string): boolean {
  const at = subscription.indexOf('@');
  if (at < 0) return matchPattern(subscription, eventName);
  const namePattern = subscription.slice(0, at);
  const sourcePattern = subscription.slice(at + 1);
  return matchPattern(namePattern, eventName) && matchPattern(sourcePattern, source ?? '');
}
