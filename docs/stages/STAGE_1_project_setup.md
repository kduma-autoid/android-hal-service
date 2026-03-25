# Etap 1: Szkielet projektu — moduły Gradle, zależności, build flavors

Przeczytaj `docs/spec/architecture.md` — zawiera pełną listę modułów, zależności i build flavors.

## Zadanie

Utwórz wszystkie moduły Gradle, skonfiguruj zależności i build flavors.

## Kroki

1. Sprawdź istniejącą strukturę projektu (namespace, pakiety, moduły). Dostosuj do tego co jest.
2. Jeśli istnieje `plugin-sunmi`, rozbij na `plugin-sunmi-printer-lib` + `plugin-sunmi-scanner-lib` (oba Library). Utwórz `plugin-sunmi-bundle` (Application).
3. Utwórz brakujące moduły (build.gradle.kts, AndroidManifest, pusty src/main/java/).
4. Zaktualizuj settings.gradle.kts.
5. Skonfiguruj hal-service z build flavors (generic/sunmi).

## Ważne

- Kotlin, min SDK 24, target SDK 34
- `buildFeatures { aidl = true }` w hal-service i transport-aidl
- `kotlin-kapt` w hal-service (Room), `kotlinx-serialization` w hal-service + transport-ws + transport-http
- Nie dodawaj klas Kotlin — tylko struktura Gradle

## Kryterium

`./gradlew assembleDebug` buduje się bez błędów.
