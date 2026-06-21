#!/data/data/com.termux/files/usr/bin/sh
set -eu

mkdir -p "$HOME/.termux"
props="$HOME/.termux/termux.properties"
touch "$props"

if grep -q '^allow-external-apps=' "$props"; then
  sed -i 's/^allow-external-apps=.*/allow-external-apps=true/' "$props"
else
  printf '\nallow-external-apps=true\n' >> "$props"
fi

termux-reload-settings 2>/dev/null || true
echo "Termux RUN_COMMAND external apps enabled."
