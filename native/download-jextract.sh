#!/bin/bash
# Downloads and extracts the jextract binary for the current platform.
# Usage: download-jextract.sh [jextract-version] [output-directory]
set -eu
set -o pipefail

JEXTRACT_VERSION="${1:-25-jextract+2-4}"
JEXTRACT_DIR="${2:-target/jextract}"

OS=$(uname -s | tr '[:upper:]' '[:lower:]')
ARCH=$(uname -m)

case "$OS" in
  linux)
    case "$ARCH" in
      x86_64|amd64) PLATFORM="linux-x64" ;;
      aarch64|arm64) PLATFORM="linux-aarch64" ;;
      *) echo "Unsupported Linux architecture: $ARCH" >&2; exit 1 ;;
    esac
    ;;
  darwin)
    case "$ARCH" in
      x86_64|amd64) PLATFORM="macos-x64" ;;
      aarch64|arm64) PLATFORM="macos-aarch64" ;;
      *) echo "Unsupported macOS architecture: $ARCH" >&2; exit 1 ;;
    esac
    ;;
  mingw*|cygwin*|msys*|windows*)
    PLATFORM="windows-x64"
    ;;
  *)
    echo "Unsupported OS: $OS" >&2; exit 1 ;;
esac

# Build 25-jextract+2-4 is hosted under /25/2
BASE_URL="https://download.java.net/java/early_access/jextract/25/2"
TARBALL="openjdk-${JEXTRACT_VERSION}_${PLATFORM}_bin.tar.gz"
URL="${BASE_URL}/${TARBALL}"

mkdir -p "$JEXTRACT_DIR"
JEXTRACT_DIR=$(cd "$JEXTRACT_DIR" && pwd)

if [ -x "$JEXTRACT_DIR/bin/jextract" ]; then
  echo "jextract already installed at $JEXTRACT_DIR"
  exit 0
fi

DOWNLOAD_FILE="$JEXTRACT_DIR/${TARBALL//\+/%2B}"
# Actually, curl with a quoted URL is fine; the + in the tarball name only appears in the local filename.
# The tarball name in the URL contains +, which curl sends correctly when quoted.

echo "Downloading jextract from $URL"
curl -L -o "$DOWNLOAD_FILE" "$URL"

echo "Extracting jextract..."
tar -xzf "$DOWNLOAD_FILE" -C "$JEXTRACT_DIR" --strip-components=1

rm -f "$DOWNLOAD_FILE"

if [ ! -x "$JEXTRACT_DIR/bin/jextract" ]; then
  echo "ERROR: jextract binary not found after extraction" >&2
  exit 1
fi

echo "jextract installed at $JEXTRACT_DIR"
