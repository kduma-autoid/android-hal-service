export interface DescribeOptions {
  withSuper?: boolean;
  withExperimental?: boolean;
}

export interface DescribeResponse {
  plugins: PluginDescriptor[];
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
  exampleEvent: string;
}

export function allMethods(plugin: PluginDescriptor): MethodDescriptor[] {
  return plugin.groups.flatMap(g => g.methods);
}

export function allEvents(plugin: PluginDescriptor): EventDescriptor[] {
  return plugin.groups.flatMap(g => g.events);
}
