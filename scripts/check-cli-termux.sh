#!/data/data/com.termux/files/usr/bin/sh
set -eu

cd "$(dirname "$0")/.."
cat > .mobile-agent-cli-input.txt <<'EOF'
/status
/doctor
/plugins
/apps
what time is it?
continue
/traces
/exit
EOF
sh scripts/chat-termux.sh --mock --new-session < .mobile-agent-cli-input.txt
rm -f .mobile-agent-cli-input.txt
