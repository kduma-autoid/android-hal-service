export interface DescribeOptions {
  withSuper?: boolean;
  withExperimental?: boolean;
}

export interface DescribeResponse {
  plugins: PluginDescriptor[];
  /** Registered interfaces (server-side interface layer). */
  interfaces?: InterfaceDescriptor[];
}

/**
 * Separator introducing the provider selector in a method name — `light.on@sunmi.tms.led` pins one
 * provider of an interface for a single call. Mirrors the `event@source` subscription syntax, so
 * commands and events target a provider the same way.
 */
export const PROVIDER_SELECTOR = '@';

/**
 * Builds the method name for a call, appending the `@providerId` selector when a specific interface
 * provider is pinned. Returns the bare method name when `providerId` is absent, which routes to the
 * interface's default provider.
 */
export function methodForProvider(method: string, providerId?: string | null): string {
  return providerId ? `${method}${PROVIDER_SELECTOR}${providerId}` : method;
}

export interface InterfaceFeature {
  key: string;
  description: string;
  methods: string[];
}

export interface InterfaceProvider {
  pluginId: string;
  source: string;
  priority: number;
  isDefault: boolean;
  /** False when the user has disabled this provider for the interface (still listed, not routable). */
  enabled: boolean;
  /** The provider plugin is experimental; only listed at all when it is visible to this caller. */
  experimental?: boolean;
  /** True when the experimental provider is actually usable — user-enabled, or granted by the token. */
  experimentalActive?: boolean;
  features: string[];
}

export interface InterfaceDescriptor {
  kind: 'interface';
  interfaceId: string;
  version: number;
  /** The whole interface is experimental — every method and event of it requires experimental access. */
  experimental?: boolean;
  /** True when that experimental access is actually held (token) or granted (user setting). */
  experimentalActive?: boolean;
  features: InterfaceFeature[];
  methods: MethodDescriptor[];
  events: EventDescriptor[];
  providers: InterfaceProvider[];
}

export interface PluginDescriptor {
  pluginId: string;
  name: string;
  version: number;
  experimental?: boolean;
  experimentalActive?: boolean;
  capabilities: string[];
  groups: DescriptorGroup[];
  /** Interface ids this plugin provides (implements). Cross-reference DescribeResponse.interfaces. */
  providesInterfaces?: string[];
  /** Interface ids this plugin defines (registers). Cross-reference DescribeResponse.interfaces. */
  definesInterfaces?: string[];
}

export interface DescriptorGroup {
  name?: string | null;
  methods: MethodDescriptor[];
  events: EventDescriptor[];
}

export interface MethodDescriptor {
  name: string;
  description: string;
  requiredPermission: string;
  superRequired?: boolean;
  experimental?: boolean;
  experimentalActive?: boolean;
  exampleParameters: string;
  exampleOutput: string;
}

export interface EventDescriptor {
  name: string;
  description: string;
  requiredPermission: string;
  experimental?: boolean;
  experimentalActive?: boolean;
  exampleEvent: string;
}

export function allMethods(plugin: PluginDescriptor): MethodDescriptor[] {
  return plugin.groups.flatMap(g => g.methods);
}

export function allEvents(plugin: PluginDescriptor): EventDescriptor[] {
  return plugin.groups.flatMap(g => g.events);
}
