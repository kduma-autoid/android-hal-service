/**
 * Rolling (animated) QR code: a payload too long for one code is split into frames shown one after another.
 * Each frame is `AQR|<id>|<index>/<total>|<chunk>` with a 1-based index — the format of the SS2222
 * proof of concept. Frames can be read in any order and repeats are ignored; the payload is the chunks
 * joined in index order.
 *
 * `AQRB|…` frames carry the payload as Base64 of its UTF-8 bytes, split into chunks. Some scanners
 * cannot pass non-ASCII text through: the SS2222 re-encodes what it decodes into GB2312/GBK and turns
 * characters outside it into `00 00`. Base64 is plain ASCII and survives any of them.
 */

export type RollingQrEncoding = 'text' | 'base64';

export interface RollingQrFrame {
  id: string;
  index: number;
  total: number;
  chunk: string;
  encoding: RollingQrEncoding;
}

const PATTERN = /^AQR(B?)\|([^|]+)\|(\d+)\/(\d+)\|([\s\S]*)$/;

/** Parses one scanned code; null when it is not a rolling QR frame. A trailing CR/LF from the scanner is ignored. */
export function parseFrame(text: string): RollingQrFrame | null {
  const m = PATTERN.exec(text.replace(/[\r\n]+$/, ''));
  if (!m) return null;
  const index = Number(m[3]);
  const total = Number(m[4]);
  if (!Number.isInteger(total) || total < 1 || !Number.isInteger(index) || index < 1 || index > total) return null;
  return { id: m[2], index, total, chunk: m[5], encoding: m[1] ? 'base64' : 'text' };
}

export function formatFrame(frame: RollingQrFrame): string {
  const tag = frame.encoding === 'base64' ? 'AQRB' : 'AQR';
  return `${tag}|${frame.id}|${frame.index}/${frame.total}|${frame.chunk}`;
}

/** True when [text] needs more than 7-bit ASCII, i.e. may not survive a scanner's code page. */
export function hasNonAscii(text: string): boolean {
  return /[^\x00-\x7F]/.test(text);
}

function toBase64(text: string): string {
  let binary = '';
  for (const b of new TextEncoder().encode(text)) binary += String.fromCharCode(b);
  return btoa(binary);
}

function fromBase64(data: string): string {
  const binary = atob(data);
  return new TextDecoder().decode(Uint8Array.from(binary, (c) => c.charCodeAt(0)));
}

/** A short random sequence id (`[A-Z0-9]`), so a reader can tell two sequences apart. */
export function randomSequenceId(length = 4): string {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => alphabet[b % alphabet.length]).join('');
}

/**
 * Splits [payload] into frames of at most [chunkLength] characters. Splits by code point, so a
 * character outside the BMP (emoji) is never cut in half. An empty payload gives one empty frame.
 */
export function buildFrames(
  payload: string,
  chunkLength: number,
  id = randomSequenceId(),
  encoding: RollingQrEncoding = 'text',
): RollingQrFrame[] {
  if (!Number.isInteger(chunkLength) || chunkLength < 1) throw new Error('chunkLength must be a positive integer');
  if (id.includes('|')) throw new Error('Sequence id must not contain "|"');
  const chars = Array.from(encoding === 'base64' ? toBase64(payload) : payload);
  const total = Math.max(1, Math.ceil(chars.length / chunkLength));
  return Array.from({ length: total }, (_, i) => ({
    id,
    index: i + 1,
    total,
    chunk: chars.slice(i * chunkLength, (i + 1) * chunkLength).join(''),
    encoding,
  }));
}

export type AddResult = 'new' | 'duplicate' | 'other-sequence' | 'complete';

/** Collects the frames of one sequence; the first frame decides which sequence that is. */
export class RollingQrAssembler {
  private chunks = new Map<number, string>();
  id: string | null = null;
  total = 0;
  encoding: RollingQrEncoding = 'text';

  get received(): number {
    return this.chunks.size;
  }

  get isComplete(): boolean {
    return this.total > 0 && this.chunks.size === this.total;
  }

  /** The joined chunks, decoded for `AQRB`; only meaningful once [isComplete]. Throws on malformed Base64. */
  get payload(): string {
    const joined = [...this.chunks.entries()].sort(([a], [b]) => a - b).map(([, c]) => c).join('');
    return this.encoding === 'base64' ? fromBase64(joined) : joined;
  }

  /** 1-based indexes not received yet. */
  get missing(): number[] {
    return Array.from({ length: this.total }, (_, i) => i + 1).filter((i) => !this.chunks.has(i));
  }

  has(index: number): boolean {
    return this.chunks.has(index);
  }

  reset(): void {
    this.chunks.clear();
    this.id = null;
    this.total = 0;
    this.encoding = 'text';
  }

  add(frame: RollingQrFrame): AddResult {
    if (this.id === null) {
      this.id = frame.id;
      this.total = frame.total;
      this.encoding = frame.encoding;
    }
    if (frame.id !== this.id || frame.total !== this.total || frame.encoding !== this.encoding) return 'other-sequence';
    if (this.chunks.has(frame.index)) return 'duplicate';
    this.chunks.set(frame.index, frame.chunk);
    return this.isComplete ? 'complete' : 'new';
  }
}
