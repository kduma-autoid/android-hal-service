import { describe, it, expect } from 'vitest';
import { isInterfaceDefinerOnly } from '../types/describe.js';
import type { PluginDescriptor } from '../types/describe.js';

const base: PluginDescriptor = { pluginId: 'p', name: 'P', version: 1, capabilities: [], groups: [] };
const method = { name: 'x.y', description: '', capability: 'x' } as never;

describe('isInterfaceDefinerOnly', () => {
  it('is true for a plugin that only defines interfaces', () => {
    expect(isInterfaceDefinerOnly({ ...base, definesInterfaces: ['scale'] })).toBe(true);
  });

  it('is false for providers and plugins with their own API', () => {
    expect(isInterfaceDefinerOnly(base)).toBe(false);
    expect(isInterfaceDefinerOnly({ ...base, providesInterfaces: ['demo'] })).toBe(false);
    expect(isInterfaceDefinerOnly({ ...base, definesInterfaces: ['demo'], providesInterfaces: ['demo'] })).toBe(false);
    expect(
      isInterfaceDefinerOnly({ ...base, definesInterfaces: ['x'], groups: [{ methods: [method], events: [] }] }),
    ).toBe(false);
  });
});
