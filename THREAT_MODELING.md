# Threat Modeling of the Bugbane app

This document describes the threat model of the Bugbane app.
For every threat, we will describe the risk and the adopted/proposed countermeasures.
Do not expect STRIDE, PASTA, etc. type of things.

## Threat: Attacker compromises the device gaining root access (non-consensual forensic extraction)
The attacker gains root access to the device by exploiting a vulnerability in the device's operating system. (eg. Cellebrite, Spyware, etc)

From that point on the attacker can perfrom all kind of adversarial actions, such as reading Bugbane's sandboxed storage.
The application sandboxed storage contains multiple sensitive files, such as past acquisitions, Bugbane ADB key, etc.

### Countermeasures
Bugbane should be designed to be as resistant as possible to future compromises.
For this reason all the data in the application's sandbox should be encrypted in a secure way.
This includes:
- Exports
- Past acquisitions
- ADB keys/certificates

The encryption key should be hardware backed and stored in the device's secure storage whenever possible.

Encrypting past acquisitions however it's still an open problem, since we want to keep the possibility to scan older acquisitions with newer IoCs.

## Threat: Attacker gains phisical access to a device with Bugbane installed
The attacker can open applications and view their content.

The attacker could export old aquisitions or initiate new ones accessing private information.
Bugbane should also provide some degree of plausible deniability, whenever possible.

### Countermeasures

Allow to lock the app, using the system credentials (PIN/Password/Biometric)  
Hide the application by allowing users to change the application name and icon (https://support.signal.org/hc/en-us/articles/6325632279834-Change-the-Signal-app-icon-on-your-phone)  
Allow users to re-package the application under a different applicationID (like MagiskHide?)

## Threat: Malicious application installed on the same Device
The attacker has already compromised the user's device where Bugbane is being installed. (eg Spyware, Stalkerware, etc)

Since Bugbane requires a privileged access to perform aquisitions (via Developer Options and ADB), other malicious app could try to abuse or leverage Bugbane to gain more privileges.

Bugbane should NEVER in any way allow third-party apps to gain more privileges than the one they already have.
In other word, it should not lower the security posture of the device.

### Countermeasures

Force and push the user to disable Developer Options whenever possible.
Ease as much as possible the process for enabling Developer Options in order to avoid any UX fatigue.

Secure the ADB communication channel using a strong key.
The ADB key is stored in the application sandbox encrypted with a StrongBox key (whenever supported).


