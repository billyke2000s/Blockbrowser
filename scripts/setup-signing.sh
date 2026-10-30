#!/usr/bin/env bash
# Run once on a trusted computer with Java keytool and GitHub CLI installed.
# NEVER commit the resulting keystore or passwords.
set -euo pipefail
command -v keytool >/dev/null || { echo "Install a JDK (keytool) first."; exit 1; }
command -v gh >/dev/null || { echo "Install GitHub CLI (gh) first."; exit 1; }
gh auth status >/dev/null || { echo "Run gh auth login first."; exit 1; }
umask 077
read -r -p "Private backup directory (outside the repo): " BACKUP
[ -n "$BACKUP" ] || { echo "Backup directory required"; exit 1; }
mkdir -p "$BACKUP"
KEYSTORE="$BACKUP/blockbrowser-release.jks"
[ ! -e "$KEYSTORE" ] || { echo "Keystore already exists. Refusing to replace signing identity."; exit 1; }
read -r -s -p "Choose a strong keystore password: " PASSWORD
echo
[ "${#PASSWORD}" -ge 12 ] || { echo "Use at least 12 characters"; exit 1; }
read -r -s -p "Confirm password: " CONFIRM
echo
[ "$PASSWORD" = "$CONFIRM" ] || { echo "Passwords do not match"; exit 1; }
unset CONFIRM
ALIAS="blockbrowser"
keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias "$ALIAS" -keyalg RSA -keysize 3072 -validity 10000 -storepass "$PASSWORD" -keypass "$PASSWORD" -dname "CN=Block Browser, OU=Android, O=Block Browser"
gh secret set SIGNING_KEYSTORE_BASE64 -R billyke2000s/Blockbrowser < <(base64 < "$KEYSTORE" | tr -d '\n')
printf '%s' "$PASSWORD" | gh secret set SIGNING_STORE_PASSWORD -R billyke2000s/Blockbrowser
printf '%s' "$ALIAS" | gh secret set SIGNING_KEY_ALIAS -R billyke2000s/Blockbrowser
printf '%s' "$PASSWORD" | gh secret set SIGNING_KEY_PASSWORD -R billyke2000s/Blockbrowser
unset PASSWORD
echo "Signing secrets uploaded. BACK UP $KEYSTORE and its password securely."
echo "Do not delete the keystore: losing it prevents updates to installations signed with this key."
echo "Trigger a new workflow run from GitHub Actions to publish the first permanently signed APK."
