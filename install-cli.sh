#!/usr/bin/env bash
# Install flit-cli — Flit's desktop companion, the LocalSend v2.2 command line
# (same protocol stack as the Android app).
#
#   ./install-cli.sh                build from source, install to ~/.local
#   ./install-cli.sh --zip F.zip    install a prebuilt distribution zip (CI artifact)
#   ./install-cli.sh --prefix DIR   install under DIR instead of ~/.local
#
# Installs $prefix/lib/flit-cli and a guarded launcher at $prefix/bin/flit-cli
# (refuses Javas older than 21 with a readable message, instead of the JVM's
# JNI cry). No sudo. Building needs JDK 21 too.
set -euo pipefail

prefix="$HOME/.local"
zip_src=""
repo="$(cd "$(dirname "$0")" && pwd)"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --zip) [[ $# -ge 2 ]] || { echo "--zip needs a file" >&2; exit 2; }; zip_src="$2"; shift 2 ;;
    --prefix) [[ $# -ge 2 ]] || { echo "--prefix needs a directory" >&2; exit 2; }; prefix="$2"; shift 2 ;;
    -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "usage: $0 [--zip FILE] [--prefix DIR]" >&2; exit 2 ;;
  esac
done

# The distribution zip: given, or freshly built from this checkout.
# Java quality gate first — both paths need it (gradle for the build, java 21
# for the installed bin). The repo pins its JDK in env.sh; honor it when the
# ambient java won't do.
if [[ -f "$repo/env.sh" ]]; then source "$repo/env.sh"; fi
jmajor="$( { command -v java >/dev/null 2>&1 && java -version 2>&1 | head -1; } \
  | sed -nE 's/.*"([0-9]+).*/\1/p' || true )"
if [[ "${jmajor:-0}" -lt 21 ]]; then
  echo "flit-cli is a JDK 21 program (java found: ${jmajor:-none}) — source env.sh first, or set JAVA_HOME" >&2
  exit 1
fi

if [[ -n "$zip_src" ]]; then
  [[ -f "$zip_src" ]] || { echo "no such zip: $zip_src" >&2; exit 2; }
else
  cd "$repo"
  ./gradlew --console=plain :harness:installDist :harness:distZip
  zip_src="$(ls -t harness/build/distributions/flit-cli-*.zip | head -1)"
fi

stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
unzip -q "$zip_src" -d "$stage"
# The zip wraps a single flit-cli-<version>/ directory.
root="$(find "$stage" -mindepth 1 -maxdepth 1 -type d | head -1)"

mkdir -p "$prefix/lib" "$prefix/bin"
rm -rf "$prefix/lib/flit-cli"
cp -a "$root" "$prefix/lib/flit-cli"
chmod +x "$prefix/lib/flit-cli/bin/flit-cli"

# The launcher: same java-quality gate at run time, in one readable line —
# the Gradle start script's alternative is a stack trace about class file
# versions, which is no way to greet a user.
rm -f "$prefix/bin/flit-cli"
cat > "$prefix/bin/flit-cli" <<'LAUNCHER'
#!/usr/bin/env bash
jv="$( { command -v java >/dev/null 2>&1 && java -version 2>&1 | head -1; } | sed -nE 's/.*"([0-9]+).*/\1/p')"
if [[ "${jv:-0}" -lt 21 ]]; then
  echo "flit-cli needs JDK 21 (java found: ${jv:-none}) — set JAVA_HOME or put java 21 first on PATH" >&2
  exit 1
fi
exec "$(cd "$(dirname "$0")" && pwd)/../lib/flit-cli/bin/flit-cli" "$@"
LAUNCHER
chmod +x "$prefix/bin/flit-cli"

echo "installed: $prefix/bin/flit-cli (launcher) -> $prefix/lib/flit-cli"
if ! command -v flit-cli >/dev/null 2>&1; then
  echo "note: $prefix/bin is not on PATH — add it to use flit-cli directly"
fi
echo "--- smoke: the bin answers ---"
"$prefix/bin/flit-cli" | head -2
