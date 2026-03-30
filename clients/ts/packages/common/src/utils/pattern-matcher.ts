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
