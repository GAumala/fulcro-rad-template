#!/bin/sh
# Creates a new Fulcro RAD project from this template:
#
#   curl -fsSL https://raw.githubusercontent.com/GAUMALA/fulcro-rad-template/main/new-project.sh | sh
#
# It asks for a project name and a namespace prefix, clones the template into ./<name>, renames every
# `com.example` (namespaces, config keys, paths, docs), writes the project's README and starts a fresh git repo.
#
# Non-interactive:  ... | sh -s -- my-app com.acme.my-app
# Environment:      TEMPLATE_REPO (git URL or local path), TEMPLATE_REF (branch or tag; default: the repo's default)
#
# POSIX sh, for macOS and Linux. Needs git and perl.
set -eu

TEMPLATE_REPO="${TEMPLATE_REPO:-https://github.com/GAUMALA/fulcro-rad-template}"
TEMPLATE_REF="${TEMPLATE_REF:-}"

die() { printf 'Error: %s\n' "$*" >&2; exit 1; }

# Piped into sh, stdin is this script, so answers come from the terminal.
ask() { # prompt default -> answer
  if (: < /dev/tty) 2> /dev/null; then # a terminal we can actually open (exists but fails in CI)
    printf '%s' "$1" > /dev/tty
    read -r answer < /dev/tty || answer=""
  else
    die "no terminal to prompt on; pass the answers as arguments: sh -s -- <name> <namespace>"
  fi
  [ -n "$answer" ] && printf '%s' "$answer" || printf '%s' "$2"
}

for tool in git perl; do
  command -v "$tool" > /dev/null 2>&1 || die "$tool is required"
done

name="${1:-}"
ns="${2:-}"
[ -n "$name" ] || name=$(ask "Project name (lowercase, hyphens), e.g. my-app: " "")
printf '%s' "$name" | grep -Eq '^[a-z][a-z0-9-]*$' ||
  die "'$name' is not a valid project name: use lowercase letters, digits and hyphens, e.g. my-app"
[ -n "$ns" ] || ns=$(ask "Namespace prefix [com.$name]: " "com.$name")
printf '%s' "$ns" | grep -Eq '^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+$' ||
  die "'$ns' is not a valid namespace prefix: lowercase dotted segments with hyphens, e.g. com.acme.my-app"
case "$ns" in com.example | com.example.*) die "choose a namespace prefix outside com.example" ;; esac
[ ! -e "$name" ] || die "./$name already exists"

dir=$(printf '%s' "$ns" | tr -- '-.' '_/')

printf 'Creating %s (namespace %s)...\n' "$name" "$ns"
if [ -n "$TEMPLATE_REF" ]; then
  git clone --quiet --depth 1 --branch "$TEMPLATE_REF" "$TEMPLATE_REPO" "$name"
else
  git clone --quiet --depth 1 "$TEMPLATE_REPO" "$name"
fi
cd "$name"
rm -rf .git

# The template's own name, and every com.example (namespaces, config keys, paths, docs)
grep -rlI --exclude-dir=.git --exclude-dir=node_modules --exclude=new-project.sh \
  -e 'com\.example' -e 'com/example' -e 'rad-statecharts-template' . |
  while IFS= read -r f; do
    NS="$ns" DIR="$dir" NAME="$name" perl -pi -e \
      's/com\.example/$ENV{NS}/g; s{com/example}{$ENV{DIR}}g; s/rad-statecharts-template/$ENV{NAME}/g' "$f"
  done

for src in src/main src/test; do
  if [ -d "$src/com/example" ]; then
    mkdir -p "$(dirname "$src/$dir")"
    mv "$src/com/example" "$src/$dir"
    rmdir "$src/com" 2> /dev/null || true # only if nothing else lives there
  fi
done

# The project's README, replacing the template's
NAME="$name" NS="$ns" perl -pe 's/\{\{PROJECT_NAME\}\}/$ENV{NAME}/g; s/\{\{NAMESPACE\}\}/$ENV{NS}/g' \
  PROJECT_README.adoc > README.adoc
rm -f PROJECT_README.adoc new-project.sh

git init --quiet

cat << EOF

Created ./$name. Next:

  cd $name
  mise trust && mise install   # or install Java 21, Clojure, Node 24 and pnpm yourself
  pnpm install
  make cljs                    # then, in another terminal: clj -A:dev, (require 'development) (development/start)

See README.adoc and docs/user-guide.adoc. Nothing is committed yet: review, then make your first commit.
EOF
