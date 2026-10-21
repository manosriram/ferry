#!/bin/sh
# Installs the ferry server (web UI included) on Linux or macOS.
#
#   curl -fsSL https://raw.githubusercontent.com/manosriram/ferry/main/install.sh | sh
#
# Options (environment variables):
#   FERRY_VERSION   release tag to install, e.g. v0.2.0 (default: latest)
#   FERRY_BIN_DIR   where to put the binary (default: /usr/local/bin, else ~/.local/bin)
#   FERRY_REPO      GitHub repo to download from (default: manosriram/ferry)
#   FERRY_BASE_URL  download from this URL instead of GitHub (mirrors, testing)
set -eu

REPO="${FERRY_REPO:-manosriram/ferry}"
VERSION="${FERRY_VERSION:-latest}"

say() { printf '%s\n' "$*"; }
die() { printf 'ferry install: %s\n' "$*" >&2; exit 1; }

case "$(uname -s)" in
  Linux) os=linux ;;
  Darwin) os=darwin ;;
  *) die "unsupported OS $(uname -s); on Windows run install.ps1" ;;
esac
case "$(uname -m)" in
  x86_64 | amd64) arch=amd64 ;;
  arm64 | aarch64) arch=arm64 ;;
  *) die "unsupported CPU $(uname -m); build from source: cd server && go build" ;;
esac
asset="ferry-$os-$arch"

if [ -n "${FERRY_BASE_URL:-}" ]; then
  base="$FERRY_BASE_URL"
elif [ "$VERSION" = latest ]; then
  base="https://github.com/$REPO/releases/latest/download"
else
  base="https://github.com/$REPO/releases/download/$VERSION"
fi

fetch() {
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$1" -o "$2"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$1" -O "$2"
  else
    die "need curl or wget"
  fi
}

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

say "Downloading $asset ($VERSION)…"
fetch "$base/$asset" "$tmp/ferry" || die "download failed: $base/$asset"
fetch "$base/SHA256SUMS" "$tmp/SHA256SUMS" || die "download failed: $base/SHA256SUMS"

expected="$(awk -v f="$asset" '$2 == f || $2 == "*" f { print $1 }' "$tmp/SHA256SUMS")"
[ -n "$expected" ] || die "$asset is not listed in SHA256SUMS"
[ "$(sha256 "$tmp/ferry")" = "$expected" ] || die "checksum mismatch for $asset; not installing"
chmod +x "$tmp/ferry"

# Pick a directory: an explicit one, a writable /usr/local/bin, sudo into it, or ~/.local/bin.
dir="${FERRY_BIN_DIR:-}"
sudo=""
if [ -z "$dir" ]; then
  if [ -w /usr/local/bin ]; then
    dir=/usr/local/bin
  # sudo works if it needs no password, or if there is a terminal to ask on (also when piped from curl).
  elif command -v sudo >/dev/null 2>&1 && { sudo -n true 2>/dev/null || (: </dev/tty) 2>/dev/null; }; then
    dir=/usr/local/bin
    sudo=sudo
    say "Installing to $dir (asks for your password)"
  else
    dir="$HOME/.local/bin"
  fi
fi
$sudo mkdir -p "$dir"
$sudo mv "$tmp/ferry" "$dir/ferry"

say ""
say "Installed $("$dir/ferry" -version) to $dir/ferry"
case ":$PATH:" in
  *":$dir:"*) ;;
  *) say "Add it to your PATH:  export PATH=\"$dir:\$PATH\"" ;;
esac
say ""
say "Run it:"
say "  ferry -addr :8080 -data ~/.local/share/ferry"
say "then open http://localhost:8080 and dock your devices."
