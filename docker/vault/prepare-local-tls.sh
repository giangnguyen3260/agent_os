#!/usr/bin/env bash
set -euo pipefail
umask 077

# Destination must be outside the checkout so private material cannot be committed.
destination=${1:?Usage: prepare-local-tls.sh /absolute/external/directory}
[[ "$destination" = /* ]] || { echo "Use an absolute external directory" >&2; exit 1; }
checkout=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
mkdir -p "$destination"
destination=$(cd "$destination" && pwd -P)
case "$destination/" in
  "$checkout/"*) echo "TLS directory must be outside the checkout" >&2; exit 1 ;;
esac
[[ ! -e "$destination/server.key" && ! -e "$destination/server.crt" ]] ||
  { echo "Refusing to replace existing TLS material" >&2; exit 1; }

work=$(mktemp -d "$destination/.tls-XXXXXX")
trap 'rm -rf "$work"' EXIT
openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 30   -subj "/CN=agentos-local-vault"   -addext "subjectAltName=DNS:vault,DNS:localhost,IP:127.0.0.1"   -keyout "$work/server.key" -out "$work/server.crt" >/dev/null 2>&1
# Vault's Docker entrypoint drops to its vault user; group-readable directory/key
# require the operator to set an appropriate container-readable group on Linux.
chmod 750 "$destination"
chmod 640 "$work/server.key"
chmod 644 "$work/server.crt"
mv "$work/server.key" "$destination/server.key"
mv "$work/server.crt" "$destination/server.crt"
echo "Local TLS material prepared; configure container read permissions before startup."
