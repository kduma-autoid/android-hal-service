#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Defaults
DEFAULT_KEYSTORE="${REPO_ROOT}/keys.jks"
DEFAULT_ALIAS="developer_portal_key"
DEFAULT_PERMISSIONS="*"
DEFAULT_VALIDITY_DAYS=365

# State
KEYSTORE_PATH=""
KEYSTORE_PASSWORD=""
KEY_ALIAS=""
PEM_FILE=""
SUBJECT=""
CLIENT_TYPE=""
CLIENT_ID=""
ORIGINS=()
PACKAGE_NAME=""
CERT_SHA256=""
PERMISSIONS_RAW=""
EXPERIMENTAL=""
SUPER=""
VALIDITY_DAYS=""
OUTPUT_FILE=""
TEMP_FILES=()

log() { echo "[make-dev-key] $*" >&2; }
die() { echo "[make-dev-key] ERROR: $*" >&2; exit 1; }

cleanup() {
  for f in "${TEMP_FILES[@]+"${TEMP_FILES[@]}"}"; do
    rm -f "$f" 2>/dev/null || true
  done
}
trap cleanup EXIT

usage() {
  cat >&2 <<'EOF'
Usage: make-dev-key.sh [OPTIONS]

Generate a signed HAL developer key JWT.

Key source (one of):
  --keystore PATH           Path to JKS/PKCS12 keystore (default: keys.jks in repo root)
  --keystore-password PW    Keystore password (or env SIGNING_STORE_PASSWORD, or prompt)
  --key-alias ALIAS         Key alias in keystore (default: developer_portal_key)
  --pem-file PATH           Use existing PEM private key file (skip keystore extraction)

JWT claims:
  --subject SUB             Subject / client identifier (required)
  --client-type TYPE        Client type: web, android, unrestricted (comma-separated for multiple)
  --client-id ID            Restrict to specific client ID
  --origin URL              Allowed origin for web clients (repeatable)
  --package-name NAME       Android package name restriction
  --cert-sha256 HASH        Android certificate SHA-256 restriction
  --permissions LIST        Comma-separated permissions (default: *)
  --experimental [LIST]     Enable experimental: flag for all, or comma-separated capabilities
  --super [LIST]            Enable super: flag for all, or comma-separated capabilities
  --validity-days N         Token validity in days (default: 365)

Output:
  --output FILE             Write JWT to file instead of stdout
  --help, -h                Show this help

Examples:
  # Web client key (interactive prompts for missing values)
  ./scripts/make-dev-key.sh

  # Web client key (non-interactive)
  ./scripts/make-dev-key.sh --subject hal-web-demo --client-type web \
    --client-id hal-example --origin https://hal.duma.dev

  # Android client key
  ./scripts/make-dev-key.sh --subject com.partner.app --client-type android \
    --package-name com.partner.app --cert-sha256 A1:B2:C3:...

  # Using existing PEM file
  ./scripts/make-dev-key.sh --pem-file /path/to/key.pem --subject test \
    --client-type unrestricted

  # Pipe to file
  ./scripts/make-dev-key.sh --subject demo --client-type web > key.jwt
EOF
  exit 0
}

# --- Argument parsing ---

while [[ $# -gt 0 ]]; do
  case "$1" in
    --keystore)
      KEYSTORE_PATH="$2"; shift 2 ;;
    --keystore-password)
      KEYSTORE_PASSWORD="$2"; shift 2 ;;
    --key-alias)
      KEY_ALIAS="$2"; shift 2 ;;
    --pem-file)
      PEM_FILE="$2"; shift 2 ;;
    --subject)
      SUBJECT="$2"; shift 2 ;;
    --client-type)
      CLIENT_TYPE="$2"; shift 2 ;;
    --client-id)
      CLIENT_ID="$2"; shift 2 ;;
    --origin)
      ORIGINS+=("$2"); shift 2 ;;
    --package-name)
      PACKAGE_NAME="$2"; shift 2 ;;
    --cert-sha256)
      CERT_SHA256="$2"; shift 2 ;;
    --permissions)
      PERMISSIONS_RAW="$2"; shift 2 ;;
    --experimental)
      if [[ $# -gt 1 && ! "$2" =~ ^-- ]]; then
        EXPERIMENTAL="$2"; shift 2
      else
        EXPERIMENTAL="true"; shift
      fi
      ;;
    --super)
      if [[ $# -gt 1 && ! "$2" =~ ^-- ]]; then
        SUPER="$2"; shift 2
      else
        SUPER="true"; shift
      fi
      ;;
    --validity-days)
      VALIDITY_DAYS="$2"; shift 2 ;;
    --output)
      OUTPUT_FILE="$2"; shift 2 ;;
    --help|-h)
      usage ;;
    *)
      die "Unknown option: $1" ;;
  esac
done

# --- Interactive prompts ---

interactive=false
[[ -t 0 ]] && interactive=true

prompt() {
  local var_name="$1" prompt_text="$2" default="${3:-}"
  local current_val="${!var_name}"
  if [[ -n "$current_val" ]]; then
    return
  fi
  if ! $interactive; then
    if [[ -n "$default" ]]; then
      eval "$var_name=\$default"
    fi
    return
  fi
  if [[ -n "$default" ]]; then
    read -r -p "$prompt_text [$default]: " val
    eval "$var_name=\${val:-\$default}"
  else
    read -r -p "$prompt_text: " val
    eval "$var_name=\$val"
  fi
}

prompt_password() {
  local var_name="$1" prompt_text="$2"
  local current_val="${!var_name}"
  if [[ -n "$current_val" ]]; then
    return
  fi
  if ! $interactive; then
    return
  fi
  read -r -s -p "$prompt_text: " val
  echo >&2
  eval "$var_name=\$val"
}

# Keystore password (from flag, env, or prompt)
if [[ -z "$PEM_FILE" && -z "$KEYSTORE_PASSWORD" ]]; then
  KEYSTORE_PASSWORD="${SIGNING_STORE_PASSWORD:-}"
  prompt_password KEYSTORE_PASSWORD "Keystore password"
fi

prompt SUBJECT "Subject (client identifier)" ""

if [[ -z "$CLIENT_TYPE" ]] && $interactive; then
  echo "Client type:" >&2
  echo "  1) web" >&2
  echo "  2) android" >&2
  echo "  3) unrestricted" >&2
  read -r -p "Choose [1-3]: " choice
  case "$choice" in
    1) CLIENT_TYPE="web" ;;
    2) CLIENT_TYPE="android" ;;
    3) CLIENT_TYPE="unrestricted" ;;
    *) die "Invalid choice: $choice" ;;
  esac
fi

# Always prompt for restrictions in interactive mode
if $interactive; then
  prompt CLIENT_ID "Client ID restriction (leave empty to skip)" ""

  case "$CLIENT_TYPE" in
    *web*)
      if [[ ${#ORIGINS[@]} -eq 0 ]]; then
        read -r -p "Allowed origins (comma-separated, leave empty to skip): " origins_input
        if [[ -n "$origins_input" ]]; then
          IFS=',' read -ra ORIGINS <<< "$origins_input"
          # Trim whitespace
          for i in "${!ORIGINS[@]}"; do
            ORIGINS[$i]="$(echo "${ORIGINS[$i]}" | xargs)"
          done
        fi
      fi
      ;;
    *android*)
      prompt PACKAGE_NAME "Android package name (leave empty to skip)" ""
      prompt CERT_SHA256 "Android cert SHA-256 (leave empty to skip)" ""
      ;;
  esac

  prompt PERMISSIONS_RAW "Permissions (comma-separated)" "$DEFAULT_PERMISSIONS"

  if [[ -z "$EXPERIMENTAL" ]]; then
    read -r -p "Experimental permissions (no/yes/comma-separated list) [no]: " exp_input
    case "${exp_input:-no}" in
      no|"") EXPERIMENTAL="" ;;
      yes) EXPERIMENTAL="true" ;;
      *) EXPERIMENTAL="$exp_input" ;;
    esac
  fi

  if [[ -z "$SUPER" ]]; then
    read -r -p "Super permissions (no/yes/comma-separated list) [no]: " sup_input
    case "${sup_input:-no}" in
      no|"") SUPER="" ;;
      yes) SUPER="true" ;;
      *) SUPER="$sup_input" ;;
    esac
  fi

  prompt VALIDITY_DAYS "Validity (days)" "$DEFAULT_VALIDITY_DAYS"
fi

# --- Apply defaults for non-interactive ---

[[ -z "$KEYSTORE_PATH" ]] && KEYSTORE_PATH="$DEFAULT_KEYSTORE"
[[ -z "$KEY_ALIAS" ]] && KEY_ALIAS="$DEFAULT_ALIAS"
[[ -z "$PERMISSIONS_RAW" ]] && PERMISSIONS_RAW="$DEFAULT_PERMISSIONS"
[[ -z "$VALIDITY_DAYS" ]] && VALIDITY_DAYS="$DEFAULT_VALIDITY_DAYS"

# --- Validation ---

[[ -z "$SUBJECT" ]] && die "Subject is required (use --subject or run interactively)"
[[ -z "$CLIENT_TYPE" ]] && die "Client type is required (use --client-type or run interactively)"

# Validate client_type values
IFS=',' read -ra CLIENT_TYPES <<< "$CLIENT_TYPE"
for ct in "${CLIENT_TYPES[@]}"; do
  ct="$(echo "$ct" | xargs)"
  case "$ct" in
    web|android|unrestricted) ;;
    *) die "Invalid client type: $ct (must be web, android, or unrestricted)" ;;
  esac
done

[[ "$VALIDITY_DAYS" =~ ^[0-9]+$ ]] || die "Validity days must be a positive integer"
[[ "$VALIDITY_DAYS" -gt 0 ]] || die "Validity days must be greater than 0"

# --- Dependency check ---

missing=()
command -v node >/dev/null 2>&1 || missing+=("node")
if [[ -z "$PEM_FILE" ]]; then
  command -v keytool >/dev/null 2>&1 || missing+=("keytool")
  command -v openssl >/dev/null 2>&1 || missing+=("openssl")
fi
if [[ ${#missing[@]} -gt 0 ]]; then
  die "Missing required tools: ${missing[*]}"
fi

# --- Key extraction ---

if [[ -n "$PEM_FILE" ]]; then
  [[ -f "$PEM_FILE" ]] || die "PEM file not found: $PEM_FILE"
  PEM_PATH="$PEM_FILE"
else
  [[ -f "$KEYSTORE_PATH" ]] || die "Keystore not found: $KEYSTORE_PATH"
  [[ -z "$KEYSTORE_PASSWORD" ]] && die "Keystore password is required (use --keystore-password, env SIGNING_STORE_PASSWORD, or run interactively)"

  TEMP_P12="$(mktemp)"
  TEMP_PEM="$(mktemp)"
  TEMP_FILES+=("$TEMP_P12" "$TEMP_PEM")

  # keytool refuses to write to an existing empty file
  rm -f "$TEMP_P12"

  log "Extracting private key from keystore..."
  keytool -importkeystore \
    -srckeystore "$KEYSTORE_PATH" \
    -srcstoretype PKCS12 \
    -srcstorepass "$KEYSTORE_PASSWORD" \
    -srckeypass "$KEYSTORE_PASSWORD" \
    -srcalias "$KEY_ALIAS" \
    -destkeystore "$TEMP_P12" \
    -deststoretype PKCS12 \
    -deststorepass changeit \
    -destkeypass changeit \
    -noprompt 2>/dev/null

  openssl pkcs12 \
    -in "$TEMP_P12" \
    -passin pass:changeit \
    -nocerts \
    -nodes \
    -out "$TEMP_PEM" 2>/dev/null

  rm -f "$TEMP_P12"
  PEM_PATH="$TEMP_PEM"
  log "Private key extracted."
fi

# --- Prepare JSON values ---

# Build permissions JSON array
PERMISSIONS_JSON="["
IFS=',' read -ra PERMS <<< "$PERMISSIONS_RAW"
for i in "${!PERMS[@]}"; do
  p="$(echo "${PERMS[$i]}" | xargs)"
  [[ $i -gt 0 ]] && PERMISSIONS_JSON+=","
  PERMISSIONS_JSON+="\"$p\""
done
PERMISSIONS_JSON+="]"

# Build origins JSON array
ORIGINS_JSON="["
for i in "${!ORIGINS[@]}"; do
  [[ $i -gt 0 ]] && ORIGINS_JSON+=","
  ORIGINS_JSON+="\"${ORIGINS[$i]}\""
done
ORIGINS_JSON+="]"

# Build client_type value (string if single, array if multiple)
if [[ ${#CLIENT_TYPES[@]} -eq 1 ]]; then
  CLIENT_TYPE_JSON="\"$(echo "${CLIENT_TYPES[0]}" | xargs)\""
else
  CLIENT_TYPE_JSON="["
  for i in "${!CLIENT_TYPES[@]}"; do
    ct="$(echo "${CLIENT_TYPES[$i]}" | xargs)"
    [[ $i -gt 0 ]] && CLIENT_TYPE_JSON+=","
    CLIENT_TYPE_JSON+="\"$ct\""
  done
  CLIENT_TYPE_JSON+="]"
fi

# Experimental JSON
EXPERIMENTAL_JSON=""
if [[ "$EXPERIMENTAL" == "true" ]]; then
  EXPERIMENTAL_JSON="true"
elif [[ -n "$EXPERIMENTAL" ]]; then
  EXPERIMENTAL_JSON="["
  IFS=',' read -ra EXP_CAPS <<< "$EXPERIMENTAL"
  for i in "${!EXP_CAPS[@]}"; do
    e="$(echo "${EXP_CAPS[$i]}" | xargs)"
    [[ $i -gt 0 ]] && EXPERIMENTAL_JSON+=","
    EXPERIMENTAL_JSON+="\"$e\""
  done
  EXPERIMENTAL_JSON+="]"
fi

# Super JSON
SUPER_JSON=""
if [[ "$SUPER" == "true" ]]; then
  SUPER_JSON="true"
elif [[ -n "$SUPER" ]]; then
  SUPER_JSON="["
  IFS=',' read -ra SUP_CAPS <<< "$SUPER"
  for i in "${!SUP_CAPS[@]}"; do
    s="$(echo "${SUP_CAPS[$i]}" | xargs)"
    [[ $i -gt 0 ]] && SUPER_JSON+=","
    SUPER_JSON+="\"$s\""
  done
  SUPER_JSON+="]"
fi

# --- Generate JWT ---

JWT=$(PEM_PATH="$PEM_PATH" \
  JWT_SUBJECT="$SUBJECT" \
  JWT_CLIENT_TYPE_JSON="$CLIENT_TYPE_JSON" \
  JWT_CLIENT_ID="$CLIENT_ID" \
  JWT_ORIGINS_JSON="$ORIGINS_JSON" \
  JWT_PACKAGE_NAME="$PACKAGE_NAME" \
  JWT_CERT_SHA256="$CERT_SHA256" \
  JWT_PERMISSIONS_JSON="$PERMISSIONS_JSON" \
  JWT_EXPERIMENTAL_JSON="$EXPERIMENTAL_JSON" \
  JWT_SUPER_JSON="$SUPER_JSON" \
  JWT_VALIDITY_DAYS="$VALIDITY_DAYS" \
  node -e '
const crypto = require("crypto");
const fs = require("fs");

const pem = fs.readFileSync(process.env.PEM_PATH, "utf8");
const now = Math.floor(Date.now() / 1000);
const validityDays = parseInt(process.env.JWT_VALIDITY_DAYS, 10);

const header = { alg: "RS256", typ: "hal-dev-key+jwt" };

const payload = {
  iss: "hal-developer-portal",
  sub: process.env.JWT_SUBJECT,
  iat: now,
  exp: now + validityDays * 24 * 60 * 60,
  client_type: JSON.parse(process.env.JWT_CLIENT_TYPE_JSON),
  permissions: JSON.parse(process.env.JWT_PERMISSIONS_JSON),
};

// Build restrictions
const restrictions = {};
if (process.env.JWT_CLIENT_ID) {
  restrictions.client_id = process.env.JWT_CLIENT_ID;
}
const origins = JSON.parse(process.env.JWT_ORIGINS_JSON);
if (origins.length > 0) {
  restrictions.web = { origins };
}
if (process.env.JWT_PACKAGE_NAME || process.env.JWT_CERT_SHA256) {
  const android = {};
  if (process.env.JWT_PACKAGE_NAME) android.package_name = process.env.JWT_PACKAGE_NAME;
  if (process.env.JWT_CERT_SHA256) android.cert_sha256 = process.env.JWT_CERT_SHA256;
  restrictions.android = android;
}
if (Object.keys(restrictions).length > 0) {
  payload.restrictions = restrictions;
}

// Experimental claim
if (process.env.JWT_EXPERIMENTAL_JSON === "true") {
  payload.experimental = true;
} else if (process.env.JWT_EXPERIMENTAL_JSON) {
  payload.experimental = JSON.parse(process.env.JWT_EXPERIMENTAL_JSON);
}

// Super claim
if (process.env.JWT_SUPER_JSON === "true") {
  payload.super = true;
} else if (process.env.JWT_SUPER_JSON) {
  payload.super = JSON.parse(process.env.JWT_SUPER_JSON);
}

function b64url(obj) {
  return Buffer.from(JSON.stringify(obj)).toString("base64url");
}

const h = b64url(header);
const p = b64url(payload);
const sig = crypto.sign("RSA-SHA256", Buffer.from(h + "." + p), pem).toString("base64url");
process.stdout.write(h + "." + p + "." + sig);
')

# --- Summary ---

expiry_date=$(date -d "+${VALIDITY_DAYS} days" "+%Y-%m-%d" 2>/dev/null || date -v "+${VALIDITY_DAYS}d" "+%Y-%m-%d" 2>/dev/null || echo "+${VALIDITY_DAYS} days")

log "Generated developer key JWT:"
log "  Subject:      $SUBJECT"
log "  Client type:  $CLIENT_TYPE"
log "  Permissions:  $PERMISSIONS_RAW"
log "  Valid until:  $expiry_date"
[[ -n "$CLIENT_ID" ]] && log "  Client ID:    $CLIENT_ID"
[[ ${#ORIGINS[@]} -gt 0 ]] && log "  Origins:      ${ORIGINS[*]}"
[[ -n "$PACKAGE_NAME" ]] && log "  Package:      $PACKAGE_NAME"
[[ -n "$CERT_SHA256" ]] && log "  Cert SHA-256: $CERT_SHA256"
[[ -n "$EXPERIMENTAL" ]] && log "  Experimental: $EXPERIMENTAL"
[[ -n "$SUPER" ]] && log "  Super:        $SUPER"

# --- Output ---

if [[ -n "$OUTPUT_FILE" ]]; then
  echo "$JWT" > "$OUTPUT_FILE"
  log "JWT written to $OUTPUT_FILE"
else
  echo "$JWT"
fi
