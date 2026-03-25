# Etap 2: hal-contract + transport-core — interfejsy

Przeczytaj przed implementacją:
- `docs/spec/plugin-contract.md` — HalPlugin, PluginContext, PluginDescriptor, EventBus, AidlPluginAdapter, PluginServiceWrapper
- `docs/spec/transport-contract.md` — CommandTransport, EventTransport, CommandHandler, CallerContext, TransportRegistry, KtorServerManager
- `docs/spec/testing.md` — sekcja "Etap 2: hal-contract testy"

## Zadanie

Zaimplementuj hal-contract (interfejsy pluginów, AIDL, adaptery) i transport-core (interfejsy transportów, registry).

## hal-contract — pliki do utworzenia

1. AIDL: IHardwarePlugin.aidl, IPluginEventCallback.aidl (w src/main/aidl/)
2. Kotlin interfaces: HalPlugin, HalPluginEventCallback, PluginContext
3. Data classes: PluginDescriptor, MethodDescriptor, EventDescriptor
4. Adaptery: AidlPluginAdapter, PluginServiceWrapper
5. EventBus (matchesPattern jako companion object — testowany osobno)

## transport-core — pliki do utworzenia

1. Interfaces: CommandTransport, EventTransport, CommandHandler
2. Data classes: CallerContext, TransportConfig
3. TransportRegistry

## Testy (hal-contract)

Patrz `docs/spec/testing.md` → "Etap 2":
- EventBus.matchesPattern — exact, wildcard prefix, global wildcard, edge cases
- EventBus loop protection — plugin nie dostaje własnych eventów
- EventBus SharedFlow delivery

Dodaj testowe zależności (JUnit 5, coroutines-test, mockk) do modułów.

## Kryterium

Projekt się builduje. Interfejsy gotowe. Testy EventBus przechodzą.
