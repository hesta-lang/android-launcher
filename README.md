# Geode Android Launcher

Launches a vanilla copy of Geometry Dash with the Geode loader added.

## Shortcut Links

The launcher supports a handful of links as an escape hatch when the main functionality is impaired in some way (such as needing to prevent an automatic launch due to a crash).  
You can open these links by copying them into the URL bar in some browsers (like Chrome), or through ADB.

| Link                                        | Action                                                                   |
|---------------------------------------------|--------------------------------------------------------------------------|
| geode-launcher://main                       | Opens to the main activity, forces the user to explicitly confirm launch |
| geode-launcher://main/launch                | Opens to the game after the launcher is finished checking for updates    | 
| geode-launcher://main/launch?safe-mode=true | Opens to the game like before, but with safe mode enabled                |
| geode-launcher://settings                   | Opens the main settings                                                  |
| geode-launcher://developer-settings         | Opens the developer settings                                             |
| geode-launcher://logs                       | Opens the application logs                                               |
| geode-launcher://crashes                    | Opens the crash dumps viewer                                             |


## Android FPS pacing

The launcher requests the highest refresh mode reported by the active display on Android 10 and newer, and calls `eglSwapInterval(0)` from the active `GLSurfaceView` EGL thread when the surface changes. The EGL call is checked and logged; vendor drivers that reject interval zero retain their normal pacing. Disabling the EGL swap wait does not force the panel or Android compositor to display more frames than the hardware supports, and it may increase CPU/GPU activity, heat, and battery use.

No launch argument is required.

## Installing updates without uninstalling

The standard release keeps the package ID `com.geode.launcher`, advances to version `1.9.1` / version code `32`, and is signed with the repository's configured release keystore. The build workflow verifies the APK package, version, and signing-certificate fingerprint before publishing the standard release artifact. Install the **standard Release universal APK** over the existing standard release; do not use the Debug APK or the separate Google Play flavor for an in-place update.
