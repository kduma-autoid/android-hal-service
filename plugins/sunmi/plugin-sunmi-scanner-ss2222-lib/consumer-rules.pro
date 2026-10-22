# HAL discovers built-in providers by class name and their Context constructor.
-keep class dev.duma.android.hal.plugins.sunmi.scanner.ss2222.SunmiSs2222ScannerPlugin {
    public <init>(android.content.Context);
}
