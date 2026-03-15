#!/bin/bash
# release.sh — Build APK, tag, and publish a new GitHub release
set -e

# Make sure we're on dv-compat
BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [ "$BRANCH" != "dv-compat" ]; then
  echo "❌ You must be on the dv-compat branch to release."
  echo "   Run: git checkout dv-compat"
  exit 1
fi

# Ask for version number
echo "Current releases:"
git tag --sort=-version:refname | grep dv-compat | head -5
echo ""
read -p "Enter new version (e.g. 0.1.5): " VERSION
TAG="v${VERSION}-dv-compat"
TITLE="Dolby Vision Compatibility Mode v${VERSION}"

echo ""
read -p "Release notes (one line, or press Enter to open editor): " NOTES
if [ -z "$NOTES" ]; then
  TMPFILE=$(mktemp /tmp/release-notes.XXXXXX.md)
  ${EDITOR:-nano} "$TMPFILE"
  NOTES=$(cat "$TMPFILE")
  rm "$TMPFILE"
fi

echo ""
echo "🔨 Building APK..."
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/scott/android-sdk \
./gradlew assembleDebug

APK=$(find app/build/outputs/apk/debug -name "*.apk" | head -1)
if [ -z "$APK" ]; then
  echo "❌ APK not found. Build may have failed."
  exit 1
fi

echo "🏷️  Tagging $TAG..."
git tag "$TAG"
git push origin "$TAG"

echo "🚀 Publishing release..."
gh release create "$TAG" \
  --title "$TITLE" \
  --notes "$NOTES" \
  --latest \
  "$APK"

echo ""
echo "✅ Released $TAG!"
gh release view "$TAG" --web
