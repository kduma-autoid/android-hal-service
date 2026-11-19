/**
 * Demo-side helpers for magnetic stripe data: ISO 7811 track validation, payment card (ISO/IEC 7813)
 * interpretation and track building. Deliberately kept out of the client libraries — they only
 * transport track data; what the data *means* is the application's business.
 */

export type TrackNumber = 1 | 2 | 3;

export interface Tracks {
  track1: string | null;
  track2: string | null;
  track3: string | null;
}

// --- ISO 7811 track validation (sentinels excluded — the device adds them) ---

const TRACK_ALPHABET: Record<TrackNumber, string> = {
  1: ' #$()-./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^',
  2: '0123456789=',
  3: '0123456789=',
};
const TRACK_MAX_LENGTH: Record<TrackNumber, number> = { 1: 76, 2: 37, 3: 104 };

/** Returns an error message for invalid track data, or null when it can be written. */
export function validateTrack(track: TrackNumber, data: string): string | null {
  if (data.length > TRACK_MAX_LENGTH[track]) {
    return `Track ${track} holds at most ${TRACK_MAX_LENGTH[track]} characters (${data.length} given)`;
  }
  const bad = [...new Set([...data].filter((c) => !TRACK_ALPHABET[track].includes(c)))];
  if (bad.length) {
    const allowed = track === 1 ? 'uppercase letters, digits, space and # $ ( ) - . / [ \\ ] ^' : 'digits and =';
    return `Track ${track} cannot contain ${bad.map((c) => `"${c}"`).join(', ')} (allowed: ${allowed})`;
  }
  return null;
}

// --- Payment card interpretation ---

export type CardBrand =
  | 'Visa'
  | 'Mastercard'
  | 'American Express'
  | 'Discover'
  | 'JCB'
  | 'Diners Club'
  | 'UnionPay'
  | 'Maestro'
  | 'Unknown';

export interface ServiceCode {
  raw: string;
  /** Digit 1: interchange and technology. */
  interchange: string;
  /** Digit 2: authorization processing. */
  authorization: string;
  /** Digit 3: allowed services and PIN requirements. */
  allowedServices: string;
}

export interface PaymentCard {
  pan: string;
  maskedPan: string;
  luhnValid: boolean;
  brand: CardBrand;
  cardholder: { raw: string; last: string; first: string } | null;
  expiry: { raw: string; year: number; month: number; expired: boolean } | null;
  serviceCode: ServiceCode | null;
  discretionary: string;
  /** Which tracks the data came from. */
  sources: ('track1' | 'track2')[];
}

// Track 1, format code B: B<PAN>^<NAME>^<YYMM><service code><discretionary>
const TRACK1_RE = /^B(\d{12,19})\^([^^]{0,26})\^(\d{4})(\d{3})(.*)$/;
// Track 2: <PAN>=<YYMM><service code><discretionary>
const TRACK2_RE = /^(\d{12,19})=(\d{4})(\d{3})(.*)$/;

/**
 * Interprets the tracks as a payment card (ISO/IEC 7813). Track 2 is preferred for the PAN, expiry
 * and service code, Track 1 supplies the cardholder name. Returns null when neither track is in
 * payment card format.
 */
export function parsePaymentCard(tracks: Tracks, now: Date = new Date()): PaymentCard | null {
  const t1 = tracks.track1 ? TRACK1_RE.exec(tracks.track1) : null;
  const t2 = tracks.track2 ? TRACK2_RE.exec(tracks.track2) : null;
  if (!t1 && !t2) return null;

  const pan = t2?.[1] ?? t1![1];
  const expiryRaw = t2?.[2] ?? t1![3];
  const serviceRaw = t2?.[3] ?? t1![4];
  const discretionary = t2?.[4] ?? t1![5];
  const sources: PaymentCard['sources'] = [];
  if (t1) sources.push('track1');
  if (t2) sources.push('track2');

  return {
    pan,
    maskedPan: maskPan(pan),
    luhnValid: luhnValid(pan),
    brand: detectBrand(pan),
    cardholder: t1 ? parseCardholder(t1[2]) : null,
    expiry: parseExpiry(expiryRaw, now),
    serviceCode: parseServiceCode(serviceRaw),
    discretionary,
    sources,
  };
}

export function luhnValid(pan: string): boolean {
  if (!/^\d+$/.test(pan)) return false;
  let sum = 0;
  for (let i = 0; i < pan.length; i++) {
    let d = pan.charCodeAt(pan.length - 1 - i) - 48;
    if (i % 2 === 1) {
      d *= 2;
      if (d > 9) d -= 9;
    }
    sum += d;
  }
  return sum % 10 === 0;
}

export function detectBrand(pan: string): CardBrand {
  const p = (n: number) => Number(pan.slice(0, n));
  if (/^4/.test(pan)) return 'Visa';
  if ((p(2) >= 51 && p(2) <= 55) || (p(4) >= 2221 && p(4) <= 2720)) return 'Mastercard';
  if (/^3[47]/.test(pan)) return 'American Express';
  if (/^(6011|65)/.test(pan) || (p(3) >= 644 && p(3) <= 649)) return 'Discover';
  if (p(4) >= 3528 && p(4) <= 3589) return 'JCB';
  if (/^(36|38|39)/.test(pan) || (p(3) >= 300 && p(3) <= 305)) return 'Diners Club';
  if (/^62/.test(pan)) return 'UnionPay';
  if (/^(50|5[6-9]|6)/.test(pan)) return 'Maestro';
  return 'Unknown';
}

/** First 6 and last 4 digits visible (the PCI DSS display maximum), grouped by 4. */
export function maskPan(pan: string): string {
  const visible = pan.length > 10 ? pan.slice(0, 6) + '•'.repeat(pan.length - 10) + pan.slice(-4) : pan;
  return groupDigits(visible);
}

export function groupDigits(value: string): string {
  return value.replace(/(.{4})(?=.)/g, '$1 ');
}

function parseCardholder(raw: string): PaymentCard['cardholder'] {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  const [last, rest = ''] = trimmed.split('/', 2);
  // "JOHN.MR" → first name "JOHN" (the title after the dot is dropped)
  const first = rest.split('.')[0].trim();
  return { raw, last: last.trim(), first };
}

function parseExpiry(raw: string, now: Date): PaymentCard['expiry'] {
  const year = 2000 + Number(raw.slice(0, 2));
  const month = Number(raw.slice(2, 4));
  if (month < 1 || month > 12) return null;
  // Cards are valid through the last day of the expiry month.
  const expired = now >= new Date(year, month, 1);
  return { raw, year, month, expired };
}

const SERVICE_DIGIT_1: Record<string, string> = {
  '1': 'International interchange',
  '2': 'International interchange, use IC (chip) where feasible',
  '5': 'National interchange only',
  '6': 'National interchange only, use IC (chip) where feasible',
  '7': 'No interchange except under bilateral agreement',
  '9': 'Test card',
};
const SERVICE_DIGIT_2: Record<string, string> = {
  '0': 'Normal authorization',
  '2': 'Contact issuer via online means',
  '4': 'Contact issuer via online means, except under bilateral agreement',
};
const SERVICE_DIGIT_3: Record<string, string> = {
  '0': 'No restrictions, PIN required',
  '1': 'No restrictions',
  '2': 'Goods and services only',
  '3': 'ATM only, PIN required',
  '4': 'Cash only',
  '5': 'Goods and services only, PIN required',
  '6': 'No restrictions, prompt for PIN if PED present',
  '7': 'Goods and services only, prompt for PIN if PED present',
};

function parseServiceCode(raw: string): ServiceCode {
  const describe = (map: Record<string, string>, d: string) => map[d] ?? `Reserved (${d})`;
  return {
    raw,
    interchange: describe(SERVICE_DIGIT_1, raw[0]),
    authorization: describe(SERVICE_DIGIT_2, raw[1]),
    allowedServices: describe(SERVICE_DIGIT_3, raw[2]),
  };
}

// --- Building payment card tracks ---

export interface PaymentCardInput {
  pan: string;
  /** "SURNAME/FIRST NAME". */
  cardholder: string;
  /** YYMM. */
  expiry: string;
  /** Three digits, e.g. "201" (international, chip, no restrictions). */
  serviceCode: string;
  /** Issuer discretionary data. Track 2 only takes digits; letters are dropped there. */
  discretionary?: string;
}

/**
 * Builds Track 1 (format B) and Track 2 from payment card fields. Returns the tracks plus any
 * problems with the input; the tracks are still built so they can be previewed.
 */
export function buildPaymentTracks(input: PaymentCardInput): { track1: string; track2: string; errors: string[] } {
  const pan = input.pan.replace(/\s+/g, '');
  const name = input.cardholder.trim().toUpperCase();
  const disc = (input.discretionary ?? '').toUpperCase();
  const errors: string[] = [];

  if (!/^\d{12,19}$/.test(pan)) errors.push('PAN must be 12-19 digits');
  else if (!luhnValid(pan)) errors.push('PAN fails the Luhn check');
  if (name.length < 2 || name.length > 26) errors.push('Cardholder name must be 2-26 characters');
  if (!/^\d{2}(0[1-9]|1[0-2])$/.test(input.expiry)) errors.push('Expiry must be YYMM');
  if (!/^\d{3}$/.test(input.serviceCode)) errors.push('Service code must be 3 digits');

  const track1 = `B${pan}^${name}^${input.expiry}${input.serviceCode}${disc}`;
  const track2 = `${pan}=${input.expiry}${input.serviceCode}${disc.replace(/\D/g, '')}`;
  for (const [n, t] of [[1, track1], [2, track2]] as const) {
    const err = validateTrack(n, t);
    if (err) errors.push(err);
  }
  return { track1, track2, errors };
}

/** Parses tracks back into builder input, e.g. to copy a read card into the writer. */
export function paymentCardToInput(card: PaymentCard): PaymentCardInput {
  return {
    pan: card.pan,
    cardholder: card.cardholder?.raw.trim() ?? '',
    expiry: card.expiry?.raw ?? '',
    serviceCode: card.serviceCode?.raw ?? '201',
    discretionary: card.discretionary,
  };
}
