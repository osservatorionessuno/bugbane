#!/bin/bash

# This is a simple wrapper script to sign an APK with a PKCS11 token using OpenSC (Yubikey or Nitrokey for example)
# References: 
# - https://developers.yubico.com/PIV/Guides/Android_code_signing.html
# - https://geoffreymetais.github.io/code/key-signing/
# - https://medium.com/swlh/android-app-signing-with-a-yubikey-fdfc3d730965

# Environment variables that can be overridden:
#  - ANDROID_HOME -> the path to the Android SDK (by default $HOME/Library/Android/sdk or $HOME/Android/Sdk will be used)
#  - PKCS11_MODULE_PATH -> the path to the OpenSC PKCS11 module (by default brew and linux will be used)
#  - PKCS11_SLOT_LIST_INDEX -> the index of the slot list to use (by default 0 will be used but Yubico suggest using 1)
#  - MIN_SDK_VERSION -> the minimum SDK version to use (by default 30 will be used for Bugbane)
#  - EXTRA_FLAGS -> extra flags to pass to apksigner (by default empty)

check_and_set_default() {
    # Check if a variable is set and if not, set it to a default value
    local varname="$1"
    local default="$2"
    if [[ -z "${!varname+x}" || -z "${!varname}" ]]; then
        printf -v "$varname" "%s" "$default"
    fi
}

most_recent_build_tools_version() {
    # Get the most recent folder in ANDROID_HOME/build-tools/
    if [[ -d "$ANDROID_HOME/build-tools/" ]]; then
        LATEST_DIR=$(ls -1d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -r | head -n 1)
        if [[ -n "$LATEST_DIR" ]]; then
            export BUILD_TOOLS_VERSION=$(basename "$LATEST_DIR")
            if [[ "$BUILD_TOOLS_VERSION" > "35.0.0" ]]; then
                export EXTRA_FLAGS="--alignment-preserved"
            fi
        fi
    else
        echo "Error: ANDROID_HOME does not exist."
        exit 1
    fi
}

# Bugbane minimum SDK version
check_and_set_default MIN_SDK_VERSION 30
# PKCS11 slot list index
check_and_set_default PKCS11_SLOT_LIST_INDEX 0

if [[ "$OSTYPE" == "linux-gnu"* ]]; then
    check_and_set_default PKCS11_MODULE_PATH "/usr/lib64/opensc-pkcs11.so"
    check_and_set_default ANDROID_HOME "$HOME/Android/Sdk"
    most_recent_build_tools_version
    FLAGS="--add-exports jdk.crypto.cryptoki/sun.security.pkcs11=ALL-UNNNAMED --add-opens jdk.crypto.cryptoki/sun.security.pkcs11=ALL-UNNAMED"
elif [[ "$OSTYPE" == "darwin"* ]]; then
    check_and_set_default PKCS11_MODULE_PATH "/opt/homebrew/lib/opensc-pkcs11.so"
    check_and_set_default ANDROID_HOME "$HOME/Library/Android/sdk"
    most_recent_build_tools_version
    FLAGS="--add-opens jdk.crypto.cryptoki/sun.security.pkcs11=ALL-UNNAMED --enable-native-access=ALL-UNNAMED"
else
    echo "Error: Unsupported OS"
    exit 1
fi

if [[ ! -f "$PKCS11_MODULE_PATH" ]]; then
    echo "Error: Failed to load PKCS11_MODULE_PATH - $PKCS11_MODULE_PATH does not exist."
    exit 1
fi

# Create a temporary configuration file for the PKCS11 module
TEMP_CFG_FILE=$(mktemp)
cat <<EOF > "$TEMP_CFG_FILE"
name = OpenSC-PKCS11
description = SunPKCS11 via OpenSC
library = "$PKCS11_MODULE_PATH"
slotListIndex = $PKCS11_SLOT_LIST_INDEX
EOF

# We dont check the APK path here, apksigner will do it for us
APK_PATH="${1:-}"

java \
  $FLAGS \
  -jar "$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/lib/apksigner.jar" \
  sign \
  --ks NONE \
  --ks-type PKCS11 \
  --ks-pass "stdin" \
  --min-sdk-version $MIN_SDK_VERSION \
  --provider-class sun.security.pkcs11.SunPKCS11 \
  --provider-arg "$TEMP_CFG_FILE" \
  $EXTRA_FLAGS \
  "$APK_PATH"
if [[ $? -ne 0 ]]; then
  echo "Error: Failed to sign APK"
  exit 1
fi

java \
  $FLAGS \
  -jar "$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/lib/apksigner.jar" \
  verify \
  --verbose \
  --print-certs \
  --v4-signature-file "$APK_PATH.idsig" \
  "$APK_PATH"
if [[ $? -ne 0 ]]; then
  echo "Error: Failed to verify APK"
  exit 1
fi

rm "$TEMP_CFG_FILE"
echo "APK signed and verified successfully"
