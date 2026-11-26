#!/data/data/com.termux/files/usr/bin/bash
# Signs a title_fixes.json for TitleFixes (ECDSA P-256, SHA-256): writes <file>.sig (base64 DER) next to it.
# The private key never leaves this box: ~/.config/vision-lite/fixes-signing.pem
set -e
f=${1:-app/src/main/assets/title_fixes.json}
python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$f"
openssl dgst -sha256 -sign ~/.config/vision-lite/fixes-signing.pem "$f" | base64 -w0 > "$f.sig"
pub=$(mktemp); openssl ec -in ~/.config/vision-lite/fixes-signing.pem -pubout 2>/dev/null > "$pub" 
openssl dgst -sha256 -verify "$pub" -signature <(base64 -d "$f.sig") "$f"; rm -f "$pub"
echo "upload $f and $f.sig to the FIXES_URL host"
