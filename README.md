# TvHome – launcher in stile Apple TV per Fire TV (TCL 43PF650)

## 1. Compilare l'APK (Windows)
1. Installa Android Studio (Koala o più recente).
2. File > Open > cartella `TvHome`. Attendi la sincronizzazione di Gradle (scarica Gradle 8.7 e le librerie).
3. Build > Build Bundle(s) / APK(s) > Build APK(s).
4. Il file è in `app/build/outputs/apk/debug/app-debug.apk`.

## 2. Installare sulla TV
Sulla TV: Impostazioni > Mio Fire TV > Opzioni sviluppatore: attiva "Debug ADB" e "App da fonti sconosciute". Annota l'IP (Info > Rete).

```
adb connect IP_TV:5555
adb install -r app-debug.apk
adb shell am start -n com.raffaele.tvhome/.MainActivity
```

## 3. Tasto Home
Il tasto Home viene intercettato da un servizio di accessibilità incluso nell'app. Per attivarlo senza menu:

```
adb shell pm grant com.raffaele.tvhome android.permission.WRITE_SECURE_SETTINGS
adb shell am start -n com.raffaele.tvhome/.MainActivity
```
Alla prima apertura il servizio si abilita da solo. In alternativa, manualmente:

```
adb shell settings put secure enabled_accessibility_services com.raffaele.tvhome/.HomeKeyService
adb shell settings put secure accessibility_enabled 1
```

Se il tasto Home continua ad aprire la home Amazon, il firmware non lascia passare il tasto al servizio. Alternative:
- app **Launcher Manager** (rimappa Home sul launcher scelto);
- `adb shell pm disable-user --user 0 com.amazon.tv.launcher` (rischioso: se la TV mostra schermo nero, riattiva con `adb shell pm enable com.amazon.tv.launcher`).

## 4. Uso
- OK: apre l'app. Pressione lunga su OK: Apri / Preferiti / Informazioni / Disinstalla.
- Prima riga "Preferiti", poi "Tutte le app". L'ultima tile apre le Impostazioni.
- Il tasto Indietro non esce dal launcher.

## Ripristino
```
adb shell settings put secure enabled_accessibility_services ""
adb uninstall com.raffaele.tvhome
```
