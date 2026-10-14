#!/usr/bin/env bash
# Renames the template's `com.example` namespace (code, config keys, paths, docs) to your own, e.g.
#   ./rename-project.sh com.my-domain.app-name
# Use hyphens, not underscores; the matching directories get underscores automatically. Works on macOS and Linux.
set -euo pipefail

if [ $# -ne 1 ]; then
  echo "Usage: $0 com.my-domain.app-name" >&2
  exit 1
fi
ns="$1"
if [[ ! "$ns" =~ ^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+$ ]]; then
  echo "'$ns' is not a valid namespace prefix: use lowercase dotted segments with hyphens, e.g. com.acme.my-app" >&2
  exit 1
fi
if [[ "$ns" == com.example || "$ns" == com.example.* ]]; then
  echo "Choose a prefix outside com.example" >&2
  exit 1
fi
dir=$(echo "$ns" | tr -- '-.' '_/')

cd "$(dirname "$0")"

# 1. Every text file that mentions the old name: namespaces, config keys (:com.example...), paths, docs.
grep -rlI --exclude-dir={.git,node_modules,.cpcache,.shadow-cljs,target,data} --exclude=rename-project.sh \
  -e 'com\.example' -e 'com/example' . |
  while IFS= read -r f; do
    NS="$ns" DIR="$dir" perl -pi -e 's/com\.example/$ENV{NS}/g; s{com/example}{$ENV{DIR}}g' "$f"
  done

# 2. Move the source directories.
for src in src/main src/test; do
  if [ -d "$src/com/example" ]; then
    mkdir -p "$(dirname "$src/$dir")"
    mv "$src/com/example" "$src/$dir"
    rmdir "$src/com" 2>/dev/null || true # only if nothing else lives there
  fi
done

echo "Renamed com.example -> $ns. Check with: grep -rn 'com.example' --exclude-dir=node_modules ."
