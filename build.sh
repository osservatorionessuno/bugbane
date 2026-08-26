#!/bin/bash
set -euo pipefail

cd "$(dirname "$0")"

BASE_DIR=./app/build/outputs/bundle
case "${1:-}" in
  beta)
    GRADLE_TASK=bundleBetaRelease
    AAB=betaRelease/app-beta-release.aab
    TAG_SUFFIX="-beta"
    ;;
  production)
    GRADLE_TASK=bundleProductionRelease
    AAB=productionRelease/app-production-release.aab
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

read -p "Are you sure you want to tag and build version $TAG? [y/N] " confirm
case "$confirm" in
    [yY][eE][sS]|[yY]) ;;
    *) echo "Aborted by user."; exit 1;;
esac

echo "==> signed tag $TAG"
git tag -s "$TAG" -m "Release $TAG"
git push origin "refs/tags/$TAG"

echo "==> ./gradlew $GRADLE_TASK"
./gradlew "$GRADLE_TASK"

echo "==> ./apksigner-pkcs11.sh $AAB"
./apksigner-pkcs11.sh "$AAB"

echo "Done: $AAB"
