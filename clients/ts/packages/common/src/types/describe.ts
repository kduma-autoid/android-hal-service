export interface DescribeOptions {
  withSuper?: boolean;
  withExperimental?: boolean;
}

export interface DescribeResponse {
  plugins: PluginDescriptor[];
  /** Registered interfaces (server-side interface layer). Absent on older services. */
  interfaces?: InterfaceDescriptor[];
}

/** Reserved params key selecting a specific interface provider for a call. */
export const PROVIDER_PARAM_KEY = '__provider';

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
  features: string[];
}

export interface InterfaceDescriptor {
  kind: 'interface';
  interfaceId: string;
  version: number;
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
