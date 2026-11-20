import { describe, it, expect } from 'vitest';
import { buildFrames, formatFrame, hasNonAscii, parseFrame, randomSequenceId, RollingQrAssembler } from '../rollingQr';

describe('rolling QR frames', () => {
  it('round-trips a payload through frames read in any order, with repeats', () => {
    const payload = 'SS2222 animated QR demo. Każdy kadr niesie fragment 🙂 tej wiadomości.';
    const frames = buildFrames(payload, 10, 'Z0T3').map(formatFrame);
    expect(frames[0]).toMatch(/^AQR\|Z0T3\|1\/\d+\|/);

    const asm = new RollingQrAssembler();
    const order = [...frames.keys()].reverse();
    const results = [...order, 0, 3].map((i) => asm.add(parseFrame(frames[i] + '\r')!));

    expect(results.filter((r) => r === 'complete')).toHaveLength(1);
    expect(results.slice(-2)).toEqual(['duplicate', 'duplicate']);
    expect(asm.payload).toBe(payload);
    expect(asm.missing).toEqual([]);
  });

  it('never splits a character outside the BMP', () => {
    const frames = buildFrames('a🙂b🙂', 1, 'X');
    expect(frames.map((f) => f.chunk)).toEqual(['a', '🙂', 'b', '🙂']);
  });

  it('matches the proof-of-concept format', () => {
    expect(parseFrame('AQR|Z0T3|2/15|ted QR demo.\r')).toEqual({ id: 'Z0T3', index: 2, total: 15, chunk: 'ted QR demo.', encoding: 'text' });
    expect(parseFrame('AQR|Z0T3|3/15|a|b')?.chunk).toBe('a|b'); // a chunk may contain the separator
  });

  it('rejects codes that are not frames', () => {
    for (const text of ['hello', 'AQR|x|0/3|a', 'AQR|x|4/3|a', 'AQR|x|1/0|a', 'AQR||1/2|a']) {
      expect(parseFrame(text)).toBeNull();
    }
  });

  it('keeps to the first sequence it saw and reports what is missing', () => {
    const asm = new RollingQrAssembler();
    expect(asm.add({ id: 'A', index: 2, total: 3, chunk: 'b', encoding: 'text' })).toBe('new');
    expect(asm.add({ id: 'B', index: 1, total: 3, chunk: 'x', encoding: 'text' })).toBe('other-sequence');
    expect(asm.missing).toEqual([1, 3]);
    asm.reset();
    expect(asm.add({ id: 'B', index: 1, total: 1, chunk: 'x', encoding: 'text' })).toBe('complete');
  });

  it('makes ids without the separator', () => {
    expect(randomSequenceId()).toMatch(/^[A-Z0-9]{4}$/);
    expect(() => buildFrames('x', 1, 'a|b')).toThrow();
    expect(buildFrames('', 5, 'E')).toEqual([{ id: 'E', index: 1, total: 1, chunk: '', encoding: 'text' }]);
  });

  it('carries non-ASCII text intact as Base64 frames (AQRB) in plain ASCII', () => {
    // The SS2222 re-encodes decoded text to GB2312/GBK: ż, ł, 🙂 … arrive as 00 00. Base64 avoids that.
    const payload = 'Zażółć gęślą jaźń 🙂 — ok';
    expect(hasNonAscii(payload)).toBe(true);
    const frames = buildFrames(payload, 8, 'B64', 'base64').map(formatFrame);
    expect(frames.every((f) => f.startsWith('AQRB|B64|') && !hasNonAscii(f))).toBe(true);
    const asm = new RollingQrAssembler();
    frames.reverse().forEach((f) => asm.add(parseFrame(f)!));
    expect(asm.isComplete).toBe(true);
    expect(asm.payload).toBe(payload);
  });

  it('does not mix a text and a Base64 sequence with the same id', () => {
    const asm = new RollingQrAssembler();
    asm.add(parseFrame('AQR|S|1/2|ab')!);
    expect(asm.add(parseFrame('AQRB|S|2/2|YWI=')!)).toBe('other-sequence');
  });
});
