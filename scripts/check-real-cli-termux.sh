#!/data/data/com.termux/files/usr/bin/sh
set -eu

cd "$(dirname "$0")/.."
cat > .mobile-agent-real-cli-input.txt <<'EOF'
查一下当前时间，能用工具就用工具
acornix你详细了解一下
/exit
EOF
sh scripts/chat-termux.sh --new-session < .mobile-agent-real-cli-input.txt
rm -f .mobile-agent-real-cli-input.txt
