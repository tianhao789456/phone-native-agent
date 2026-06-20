#!/data/data/com.termux/files/usr/bin/sh
set -eu

cd "$(dirname "$0")/.."
mkdir -p "$HOME/bin"
for target in "$HOME/bin/ma" "$PREFIX/bin/ma"; do
cat > "$target" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
cd "$HOME/storage/downloads/mobile-agent"
exec sh scripts/chat-termux.sh "$@"
EOF
chmod +x "$target"
done
case ":$PATH:" in
  *":$HOME/bin:"*) ;;
  *) echo 'Add this to your shell profile if ma is not found: export PATH="$HOME/bin:$PATH"' ;;
esac
echo "$PREFIX/bin/ma"
