# Bugbane
<img width="100" alt="image" src="https://raw.githubusercontent.com/osservatorionessuno/bugbane/refs/heads/main/icons/ic_bugbane_zoom.svg" />


> [!CAUTION]
> This project is under active development and is not recommended for non-technical users at this stage.
> The installation and usage of Bugbane can be detected by spyware and stalkerware. Do not use it when there is a direct risk of physical harm.

Bugbane is an open-source Android application designed to help potential spyware and stalkerware victims self-triage their own device and, when appropriate, export evidence for later analysis by trusted organizations.


<img width="260" alt="image" src="https://raw.githubusercontent.com/osservatorionessuno/bugbane/refs/heads/main/assets/screenshots/home.png" />
<img width="260" alt="image" src="https://raw.githubusercontent.com/osservatorionessuno/bugbane/refs/heads/main/assets/screenshots/pairing.png" />
<img width="260" alt="image" src="https://raw.githubusercontent.com/osservatorionessuno/bugbane/refs/heads/main/assets/screenshots/dump.png" />


Unlike existing tools such as [MVT](https://mvt.re), Bugbane does not require a separate computer, command-line usage, or debugging skills. It uses ADB Wireless Debugging to issue commands directly to the same device on which it is installed, through a guided, user-friendly interface.

Exports are intended to be [AndroidQF](https://github.com/botherder/androidqf)-compliant, making them portable and analyzable off-device using existing tools. Since exported data can contain sensitive personal information, the initial scan is performed on-device using a separately developed [libmvt](https://github.com/osservatorionessuno/libmvt), a Java re-implementation of MVT that reuses the same IOCs as the original.

This project is still evolving. A beta release will be announced when ready, and testing will be opened at that stage.

## Technical details
Bugbane uses the pairing logic from [Shizuku](https://github.com/RikkaApps/Shizuku), forked and included in our code, and [libadb-android](https://github.com/MuntashirAkon/libadb-android) as a dependency. This allows it to interact with the device's debugging service exactly as a computer connected via USB would.
 - Commands are executed with shell privileges.
 - Output is saved to the application’s private storage and encrypted.
 - The generated ADB RSA certificate is authorized for debugging and stored encrypted at rest, using StrongBox-backed credentials when available.

Export and sharing logic are currently in progress. Documentation will be expanded as development continues.
