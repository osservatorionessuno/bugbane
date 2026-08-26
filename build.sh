#!/bin/bash
set -euo pipefail

cd "$(dirname "$0")"

BASE_DIR=./app/build/outputs
case "${1:-}" in
  beta)
    GRADLE_TASKS="bundleBetaRelease assembleBetaRelease"
    AAB=$BASE_DIR/bundle/betaRelease/app-beta-release.aab
    APK=$BASE_DIR/apk/beta/release/app-beta-release-unsigned.apk
    TAG_SUFFIX="-beta"
    ;;
  production)
    GRADLE_TASKS="bundleProductionRelease assembleProductionRelease"
    AAB=$BASE_DIR/bundle/productionRelease/app-production-release.aab
    APK=$BASE_DIR/apk/production/release/app-production-release-unsigned.apk
    TAG_SUFFIX=""
    ;;
  *)
    echo "Usage: $0 <beta|production>"
    exit 1
    ;;
esac

VERSION=$(sed -n 's/.*versionName = "\(.*\)"/\1/p' app/build.gradle.kts)
if [[ -z "$VERSION" ]]; then
  echo "Error: could not read versionName from app/build.gradle.kts"
  exit 1
fi
FDROID_VERSION=$(sed -n 's/.*versionName: \(.*\)/\1/p' metadata/org.osservatorionessuno.bugbane.yml)
if [[ -z "$FDROID_VERSION" ]]; then
  echo "Error: could not read versionName from metadata/org.osservatorionessuno.bugbane.yml"
  exit 1
fi
if [[ "$VERSION" != "$FDROID_VERSION" ]]; then
  echo "Error: versionName in app/build.gradle.kts ($VERSION) does not match versionName in metadata/org.osservatorionessuno.bugbane.yml ($FDROID_VERSION)"
  exit 1
fi
TAG="${VERSION}${TAG_SUFFIX}"

echo "==> ./gradlew $GRADLE_TASKS"
./gradlew $GRADLE_TASKS

if [[ -f "$AAB" ]]; then
  echo "==> ./apksigner-pkcs11.sh $AAB"
  ./apksigner-pkcs11.sh "$AAB"
  mv "$AAB" ./build/bugbane-${VERSION}.aab
  mv "$AAB.idsig" ./build/bugbane-${VERSION}.idsig
  echo "Done: ./build/bugbane-${VERSION}.aab"
else
  echo "Error: $AAB not found."
  exit 1
fi

if [[ -f "$APK" ]]; then
  echo "==> ./apksigner-pkcs11.sh $APK"
  ./apksigner-pkcs11.sh "$APK"
  mv "$APK" ./build/bugbane-${VERSION}.apk
  mv "$APK.idsig" ./build/bugbane-${VERSION}.idsig
  echo "Done: ./build/bugbane-${VERSION}.apk"
else
  echo "Error: $APK not found."
  exit 1
fi

read -p "Do you also want to tag version $TAG? [y/N] " confirm
case "$confirm" in
    [yY][eE][sS]|[yY]) TAG_RELEASE=true ;;
    *) TAG_RELEASE=false ;;
esac

if [[ "$TAG_RELEASE" == "true" ]]; then
  if git rev-parse "$TAG" >/dev/null 2>&1; then
    echo "Error: tag $TAG already exists."
    echo "Do you want to overwrite it? [y/N] "
    read -p confirm
    case "$confirm" in
        [yY][eE][sS]|[yY]) ;;
        *) echo "Aborted by user."; exit 1;;
    esac
  fi

  echo "==> signed tag $TAG"
  git tag -s "$TAG" -m "Release $TAG"
  git push origin "$TAG"
fi

echo "Finished."