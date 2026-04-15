#!/bin/bash
set -e

export LANG=en_US.UTF-8
export LC_ALL=C

REPO_OWNER="${REPO_OWNER:-yannafroes84}"
REPO_NAME="${REPO_NAME:-mianbanA}"
RELEASE_TAG="${RELEASE_TAG:-latest}"
EXPLICIT_APP_VERSION="${APP_VERSION:-}"
EXPLICIT_BACKEND_IMAGE="${BACKEND_IMAGE:-}"
EXPLICIT_FRONTEND_IMAGE="${FRONTEND_IMAGE:-}"
IMAGE_NAMESPACE="${IMAGE_NAMESPACE:-ghcr.io/$(printf '%s' "$REPO_OWNER" | tr '[:upper:]' '[:lower:]')}"
IMAGE_BASENAME="${IMAGE_BASENAME:-$(printf '%s' "$REPO_NAME" | tr '[:upper:]' '[:lower:]')}"
BACKEND_IMAGE="${BACKEND_IMAGE:-${IMAGE_NAMESPACE}/${IMAGE_BASENAME}-backend}"
FRONTEND_IMAGE="${FRONTEND_IMAGE:-${IMAGE_NAMESPACE}/${IMAGE_BASENAME}-frontend}"

get_source_ref() {
  if [[ "$RELEASE_TAG" == "latest" ]]; then
    echo "main"
  else
    echo "$RELEASE_TAG"
  fi
}

build_raw_file_url() {
  local file_name="$1"
  echo "https://raw.githubusercontent.com/${REPO_OWNER}/${REPO_NAME}/$(get_source_ref)/${file_name}"
}

get_source_page_url() {
  echo "https://github.com/${REPO_OWNER}/${REPO_NAME}/tree/$(get_source_ref)"
}

RAW_DOCKER_COMPOSEV4_URL="$(build_raw_file_url "docker-compose-v4.yml")"
RAW_DOCKER_COMPOSEV6_URL="$(build_raw_file_url "docker-compose-v6.yml")"
DOCKER_COMPOSEV4_URL="$RAW_DOCKER_COMPOSEV4_URL"
DOCKER_COMPOSEV6_URL="$RAW_DOCKER_COMPOSEV6_URL"

COUNTRY="$(curl -fsSL https://ipinfo.io/country 2>/dev/null || true)"
if [[ "$COUNTRY" == "CN" ]]; then
  DOCKER_COMPOSEV4_URL="https://ghfast.top/${DOCKER_COMPOSEV4_URL}"
  DOCKER_COMPOSEV6_URL="https://ghfast.top/${DOCKER_COMPOSEV6_URL}"
fi

print_compose_source_error() {
  local asset_name="$1"
  echo "Error: missing compose file: ${asset_name}"
  echo "Source page: $(get_source_page_url)"
  if [[ "$RELEASE_TAG" == "latest" ]]; then
    echo "Make sure ${asset_name} exists on the main branch."
  else
    echo "Make sure ref ${RELEASE_TAG} exists and includes ${asset_name}."
  fi
}

has_ipv6_support() {
  if command -v ip >/dev/null 2>&1 && ip -6 addr show 2>/dev/null | grep -v "scope link" | grep -q "inet6"; then
    return 0
  fi
  if command -v ifconfig >/dev/null 2>&1 && ifconfig 2>/dev/null | grep -v "fe80:" | grep -q "inet6"; then
    return 0
  fi
  return 1
}

check_ipv6_support() {
  echo "Checking IPv6 support..."
  if has_ipv6_support; then
    echo "IPv6 is available"
    return 0
  fi
  echo "IPv6 is not available"
  return 1
}

get_compose_asset_name() {
  if has_ipv6_support; then
    echo "docker-compose-v6.yml"
  else
    echo "docker-compose-v4.yml"
  fi
}

get_docker_compose_url() {
  if has_ipv6_support; then
    echo "$DOCKER_COMPOSEV6_URL"
  else
    echo "$DOCKER_COMPOSEV4_URL"
  fi
}

get_raw_docker_compose_url() {
  if has_ipv6_support; then
    echo "$RAW_DOCKER_COMPOSEV6_URL"
  else
    echo "$RAW_DOCKER_COMPOSEV4_URL"
  fi
}

download_compose_file() {
  local asset_name raw_url download_url
  asset_name="$(get_compose_asset_name)"
  raw_url="$(get_raw_docker_compose_url)"
  download_url="$(get_docker_compose_url)"

  echo "Using compose asset: ${asset_name}"
  if ! curl -fsLI --connect-timeout 15 "$raw_url" >/dev/null 2>&1; then
    print_compose_source_error "$asset_name"
    return 1
  fi

  rm -f docker-compose.yml
  if ! curl -fL --retry 3 --connect-timeout 15 -o docker-compose.yml "$download_url"; then
    print_compose_source_error "$asset_name"
    return 1
  fi

  if [[ ! -s docker-compose.yml ]]; then
    echo "Error: downloaded docker-compose.yml is empty"
    return 1
  fi

  if grep -aqE '^(Not Found|404: Not Found)$|^<html|^<!DOCTYPE html' docker-compose.yml; then
    echo "Error: downloaded docker-compose.yml is not valid"
    rm -f docker-compose.yml
    print_compose_source_error "$asset_name"
    return 1
  fi

  if ! grep -q "services:" docker-compose.yml; then
    echo "Error: docker-compose.yml validation failed"
    return 1
  fi
}

check_docker() {
  if command -v docker-compose >/dev/null 2>&1; then
    DOCKER_CMD="docker-compose"
  elif command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    DOCKER_CMD="docker compose"
  else
    echo "Error: docker compose command is not available"
    exit 1
  fi
  echo "Docker command: ${DOCKER_CMD}"
}

configure_docker_ipv6() {
  echo "Configuring Docker IPv6..."

  if [[ "$(uname -s)" == "Darwin" ]]; then
    echo "Docker Desktop on macOS already supports IPv6"
    return 0
  fi

  local docker_config="/etc/docker/daemon.json"
  local sudo_cmd=""
  if [[ $EUID -ne 0 ]]; then
    sudo_cmd="sudo"
  fi

  if [[ -f "$docker_config" ]] && grep -q '"ipv6"' "$docker_config"; then
    echo "Docker IPv6 is already configured"
    return 0
  fi

  $sudo_cmd mkdir -p /etc/docker
  cat <<'EOF' | $sudo_cmd tee "$docker_config" >/dev/null
{
  "ipv6": true,
  "fixed-cidr-v6": "fd00::/80"
}
EOF

  if command -v systemctl >/dev/null 2>&1; then
    $sudo_cmd systemctl restart docker
  elif command -v service >/dev/null 2>&1; then
    $sudo_cmd service docker restart
  else
    echo "Please restart Docker manually"
  fi
  sleep 5
}

show_menu() {
  echo "==============================================="
  echo "              Panel Manager"
  echo "==============================================="
  echo "1. Install panel"
  echo "2. Update panel"
  echo "3. Uninstall panel"
  echo "4. Exit"
  echo "==============================================="
}

generate_random() {
  LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c16
}

load_existing_env() {
  if [[ -f ".env" ]]; then
    set -a
    # shellcheck disable=SC1091
    . ./.env
    set +a
  fi

  if [[ -n "$EXPLICIT_APP_VERSION" ]]; then
    APP_VERSION="$EXPLICIT_APP_VERSION"
  fi
  if [[ -n "$EXPLICIT_BACKEND_IMAGE" ]]; then
    BACKEND_IMAGE="$EXPLICIT_BACKEND_IMAGE"
  fi
  if [[ -n "$EXPLICIT_FRONTEND_IMAGE" ]]; then
    FRONTEND_IMAGE="$EXPLICIT_FRONTEND_IMAGE"
  fi
}

delete_self() {
  echo
  echo "Cleaning up script file..."
  local script_path
  script_path="$(readlink -f "$0" 2>/dev/null || realpath "$0" 2>/dev/null || echo "$0")"
  sleep 1
  rm -f "$script_path" && echo "Script file removed" || echo "Failed to remove script file"
}

get_config_params() {
  echo "Enter deployment parameters:"

  read -r -p "Frontend port (default 6366): " FRONTEND_PORT
  FRONTEND_PORT="${FRONTEND_PORT:-6366}"

  read -r -p "Backend port (default 6365): " BACKEND_PORT
  BACKEND_PORT="${BACKEND_PORT:-6365}"

  read -r -p "Release tag (default ${RELEASE_TAG}): " APP_VERSION_INPUT
  APP_VERSION="${APP_VERSION_INPUT:-${APP_VERSION:-$RELEASE_TAG}}"
  BACKEND_IMAGE="${BACKEND_IMAGE:-${IMAGE_NAMESPACE}/${IMAGE_BASENAME}-backend}"
  FRONTEND_IMAGE="${FRONTEND_IMAGE:-${IMAGE_NAMESPACE}/${IMAGE_BASENAME}-frontend}"
  JWT_SECRET="$(generate_random)"
}

write_env_file() {
  cat > .env <<EOF
JWT_SECRET=$JWT_SECRET
FRONTEND_PORT=$FRONTEND_PORT
BACKEND_PORT=$BACKEND_PORT
APP_VERSION=$APP_VERSION
BACKEND_IMAGE=$BACKEND_IMAGE
FRONTEND_IMAGE=$FRONTEND_IMAGE
EOF
}

wait_for_backend_health() {
  local max_wait=90
  local i=1
  while [[ $i -le $max_wait ]]; do
    if docker ps --format "{{.Names}}" | grep -q "^springboot-backend$"; then
      local status
      status="$(docker inspect -f '{{.State.Health.Status}}' springboot-backend 2>/dev/null || echo "unknown")"
      if [[ "$status" == "healthy" || "$status" == "unknown" ]]; then
        echo "Backend service is up"
        return 0
      fi
      if [[ "$status" == "unhealthy" ]]; then
        echo "Backend health check failed"
        return 1
      fi
    fi
    if (( i % 10 == 1 )); then
      echo "Waiting for backend... (${i}/${max_wait})"
    fi
    sleep 1
    i=$((i + 1))
  done
  echo "Backend start timed out"
  return 1
}

install_panel() {
  echo "Installing panel..."
  check_docker
  load_existing_env
  get_config_params

  echo "Downloading required files..."
  download_compose_file

  if check_ipv6_support; then
    configure_docker_ipv6
  fi

  write_env_file

  $DOCKER_CMD up -d
  wait_for_backend_health || true

  echo "Install completed"
  echo "Panel URL: http://SERVER_IP:${FRONTEND_PORT}"
  echo "Repository: https://github.com/${REPO_OWNER}/${REPO_NAME}"
  echo "Default admin: admin_user / admin_user"
  echo "Change the default password after login"
}

update_panel() {
  echo "Updating panel..."
  check_docker
  load_existing_env
  APP_VERSION="${APP_VERSION:-$RELEASE_TAG}"
  BACKEND_IMAGE="${BACKEND_IMAGE:-${IMAGE_NAMESPACE}/${IMAGE_BASENAME}-backend}"
  FRONTEND_IMAGE="${FRONTEND_IMAGE:-${IMAGE_NAMESPACE}/${IMAGE_BASENAME}-frontend}"
  FRONTEND_PORT="${FRONTEND_PORT:-6366}"
  BACKEND_PORT="${BACKEND_PORT:-6365}"
  JWT_SECRET="${JWT_SECRET:-$(generate_random)}"

  download_compose_file
  write_env_file

  if check_ipv6_support; then
    configure_docker_ipv6
  fi

  docker stop -t 30 springboot-backend 2>/dev/null || true
  docker stop -t 10 vite-frontend 2>/dev/null || true
  sleep 5

  $DOCKER_CMD down
  $DOCKER_CMD pull
  $DOCKER_CMD up -d
  wait_for_backend_health || true

  echo "Update completed"
}

uninstall_panel() {
  echo "Uninstalling panel..."
  check_docker

  if [[ ! -f docker-compose.yml ]]; then
    echo "docker-compose.yml is missing, downloading it first..."
    download_compose_file
  fi

  read -r -p "Remove panel containers, images and volumes? (y/N): " confirm
  if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
    echo "Cancelled"
    return 0
  fi

  $DOCKER_CMD down --rmi all --volumes --remove-orphans || true
  rm -f docker-compose.yml .env
  echo "Uninstall completed"
}

main() {
  while true; do
    show_menu
    read -r -p "Choose an option (1-4): " choice
    case "$choice" in
      1)
        install_panel
        delete_self
        exit 0
        ;;
      2)
        update_panel
        delete_self
        exit 0
        ;;
      3)
        uninstall_panel
        delete_self
        exit 0
        ;;
      4)
        echo "Exit"
        delete_self
        exit 0
        ;;
      *)
        echo "Invalid option, please enter 1-4"
        echo
        ;;
    esac
  done
}

main
