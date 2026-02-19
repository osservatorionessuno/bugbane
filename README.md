# Bugbane
<img width="100" alt="image" src="https://raw.githubusercontent.com/osservatorionessuno/bugbane/refs/heads/main/icons/ic_bugbane_zoom.svg" />

[![Build status](https://github.com/osservatorionessuno/bugbane/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/osservatorionessuno/bugbane/actions/workflows/build.yml?query=branch:main)
[![Reproducibility status](https://github.com/osservatorionessuno/bugbane/actions/workflows/repro.yml/badge.svg?branch=main)](https://github.com/osservatorionessuno/bugbane/actions/workflows/repro.yml?query=branch:main)

[![GitHub Release](https://img.shields.io/github/v/release/osservatorionessuno/bugbane)](https://github.com/osservatorionessuno/bugbane/releases/latest)
[![F-Droid version](https://img.shields.io/f-droid/v/org.osservatorionessuno.bugbane)](https://f-droid.org/packages/org.osservatorionessuno.bugbane/)
[![GitHub license](https://img.shields.io/github/license/osservatorionessuno/bugbane)](https://github.com/osservatorionessuno/bugbane/blob/main/LICENSE)
[![Liberapay patrons](https://img.shields.io/liberapay/patrons/0n_odv)](https://liberapay.com/0n_odv/)
[![Translation status](https://hosted.weblate.org/widget/bugbane/bugbane/svg-badge.svg)](https://hosted.weblate.org/engage/bugbane/)

> [!CAUTION]
> This project is under active development and is not recommended for non-technical users at this stage.
> The installation and usage of Bugbane can be detected by spyware and stalkerware. Do not use it when there is a direct risk of physical harm.

Bugbane is an open-source Android application designed to help potential spyware and stalkerware victims self-triage their own device and, when appropriate, export evidence for later analysis by trusted organizations. [Read more and see the demo in our blog post](https://osservatorionessuno.org/blog/2025/09/bugbane-simplifying-consensual-android-forensics/) ([Italian version](https://osservatorionessuno.org/it/blog/2025/09/bugbane-rendere-la-rilevazione-di-spyware-accessibile-a-tutti/)).


<img width="260" alt="image" src="https://raw.githubusercontent.com/osservatorionessuno/bugbane/refs/heads/main/fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" /> <img width="260" alt="image" src="https://raw.githubusercontent.com/osservatorionessuno/bugbane/refs/heads/main/fastlane/metadata/android/en-US/images/phoneScreenshots/9.png" /> <img width="260" alt="image" src="https://raw.githubusercontent.com/osservatorionessuno/bugbane/refs/heads/main/fastlane/metadata/android/en-US/images/phoneScreenshots/7.png" />


Unlike existing tools such as [MVT](https://mvt.re), Bugbane does not require a separate computer, command-line usage, or debugging skills. It uses ADB Wireless Debugging to issue commands directly to the same device on which it is installed, through a guided, user-friendly interface.

Exports are intended to be [AndroidQF](https://github.com/mvt-project/androidqf)-compliant, making them portable and analyzable off-device using existing tools, and can be easily shared via Signal or other communication tools. Each export is automatically encrypted with a random password using [age](https://github.com/FiloSottile/age). Since exported data can contain sensitive personal information, the initial scan is performed on-device using a re-implementation of MVT that reuses the same IOCs as the original.

This project is still evolving. A beta release will be announced when ready, and testing will be opened at that stage.

## License
Portions of the code in Shizuku are distributed under the Apache 2.0 License, whereas Bugbane is released under the GNU General Public License, Version 3 (GPLv3).

## Technical details
Bugbane uses the pairing logic from [Shizuku](https://github.com/RikkaApps/Shizuku), forked and included in our code, and [libadb-android](https://github.com/MuntashirAkon/libadb-android) as a dependency. This allows it to interact with the device's debugging service exactly as a computer connected via USB would.
 - Commands are executed with shell privileges.
 - Output is saved to the application’s private storage and encrypted.
 - The generated ADB RSA certificate is authorized for debugging and stored encrypted at rest, using StrongBox-backed credentials when available.

Export and sharing logic are currently in progress. Documentation will be expanded as development continues.

## Project Structure

Bugbane is divided in 4 main coponents:
- the Bugbane UI components and activities (`org.osservatorionessuno.bugbane.*`)
- **cabd**, a Kotlin implementation of a minimal ADB client (`org.osservatorionessuno.cadb.*`)
- **QF**, a Kotlin implementation of [AndroidQF](https://github.com/mvt-project/androidqf/) acquisition modules (`org.osservatorionessuno.qf.*`). Its main entrypoint is the `AcquisitionRunner` class.
- **LibMVT**, a Java implementation of [MVT](https://github.com/mvt-project/mvt/) IOC analysis engine (`org.osservatorionessuno.libmvt.*`). Its main entrypoint is the `ForeniscRunner` class.
