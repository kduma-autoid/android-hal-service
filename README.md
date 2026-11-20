<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="branding/hal-logo-dark.svg">
    <img src="branding/hal-logo.svg" alt="HAL Service" width="390">
  </picture>
</p>

HAL Service is a hardware abstraction layer for Android: a foreground service that gives other apps
access to device hardware — printers, barcode scanners, card readers, status lights and more — through
plugins, over HTTP, WebSocket, AIDL, intents and broadcasts.

## Repository

| Path | Contents |
|------|----------|
| `service/hal-service` | The HAL Service app (flavours `generic` / `sunmi` × `stable` / `development`) |
| `service/hal-contract` | The plugin contract, shared by the service and plugins |
| `service/transport/*` | Communication channels: HTTP, WebSocket, AIDL, intents, broadcasts |
| `plugins/` | Plugins — generic hardware interfaces, Sunmi devices — and their standalone bundle apps |
| `clients/ts` | TypeScript client library and the demo web app |
| `docs/spec` | Specification (in Polish) |
| `branding/` | Logo, app icons and how to use them |
