#!/usr/bin/env bash
# The SDK's names are temporary until the product's brand and domain are
# picked. They live in gradle.properties; this script changes them there and
# moves the Kotlin package with them, in one go.
#
#   scripts/rename.sh com.example.push                       # package (and namespace)
#   scripts/rename.sh com.example.push com.example push-android  # also group and artifact
#   scripts/rename.sh --check                                # CI: sources match gradle.properties
#
# Then build and test (./gradlew :dak:testDebugUnitTest), and look at git diff.
set -euo pipefail

cd "$(dirname "$0")/.."

prop() { sed -n "s/^$1=//p" gradle.properties; }
set_prop() { perl -pi -e "s/^\Q$1\E=.*/$1=$2/" gradle.properties; }

current=$(prop dak.namespace)
roots=(dak/src/main/kotlin dak/src/test/kotlin dak/src/androidTest/kotlin example/src/main/kotlin)

if [[ "${1:-}" == "--check" ]]; then
  bad=0
  dir=${current//.//}
  while IFS= read -r f; do
    pkg=$(sed -n 's/^package \([A-Za-z0-9_.]*\).*/\1/p' "$f" | head -1)
    rel=${f#*/kotlin/}
    if [[ "$pkg" != "$current" && "$pkg" != "$current".* ]] || [[ "$rel" != "$dir"/* ]]; then
      echo "$f: package $pkg, not under $current ($dir)" >&2
      bad=1
    fi
  done < <(find "${roots[@]}" -name '*.kt' 2>/dev/null)
  if grep -rn --include='*.kt' --include='*.kts' --include='*.xml' --include='*.pro' -e 'io\.github\.hridoyrocks\.dak_push' . >/dev/null 2>&1; then
    echo "the Flutter plugin's package appears in the sources" >&2
    bad=1
  fi
  [[ $bad == 0 ]] && echo "names: package $current, group $(prop dak.group), artifact $(prop dak.artifact)"
  exit $bad
fi

new=${1:?usage: scripts/rename.sh NEW_PACKAGE [GROUP] [ARTIFACT] | --check}
[[ "$new" =~ ^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$ ]] || { echo "not a package name: $new" >&2; exit 1; }
[[ "$new" != "$current" ]] || { echo "already $current" >&2; exit 1; }

old_dir=${current//.//}
new_dir=${new//.//}
for root in "${roots[@]}"; do
  [[ -d "$root/$old_dir" ]] || continue
  mkdir -p "$(dirname "$root/$new_dir")"
  tmp=$(mktemp -d)
  mv "$root/$old_dir" "$tmp/src"
  # Remove the old package's now empty parents.
  d=$(dirname "$root/$old_dir")
  while [[ "$d" != "$root" ]] && rmdir "$d" 2>/dev/null; do d=$(dirname "$d"); done
  mkdir -p "$(dirname "$root/$new_dir")"
  mv "$tmp/src" "$root/$new_dir"
  rmdir "$tmp"
done

# The package in code, docs, manifests and R8 rules (the example's
# application id follows, as it starts with the namespace).
while IFS= read -r f; do
  perl -pi -e "s/\Q$current\E/$new/g" "$f"
done < <(git ls-files -co --exclude-standard -- '*.kt' '*.kts' '*.xml' '*.pro' '*.md' '*.properties' | xargs grep -l -F "$current" 2>/dev/null || true)

set_prop dak.namespace "$new"
old_coordinates="$(prop dak.group):$(prop dak.artifact)"
[[ -n "${2:-}" ]] && set_prop dak.group "$2"
[[ -n "${3:-}" ]] && set_prop dak.artifact "$3"
new_coordinates="$(prop dak.group):$(prop dak.artifact)"
if [[ "$old_coordinates" != "$new_coordinates" ]]; then
  # The install snippets in the docs.
  while IFS= read -r f; do
    perl -pi -e "s/\Q$old_coordinates\E/$new_coordinates/g" "$f"
  done < <(git ls-files -co --exclude-standard -- '*.md' | xargs grep -l -F "$old_coordinates" 2>/dev/null || true)
fi
echo "renamed $current -> $new ($new_coordinates); now: ./gradlew :dak:testDebugUnitTest and git diff"
