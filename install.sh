#!/bin/bash

set -u

REPO_OWNER="${REPO_OWNER:-yannafroes84}"
REPO_NAME="${REPO_NAME:-mianbanA}"
RELEASE_TAG="${RELEASE_TAG:-latest}"
INSTALL_DIR="/etc/flux_agent"
BIN_DIR="/usr/local/bin"
BIN_PATH="${BIN_DIR}/flux_agent"

get_architecture() {
  case "$(uname -m)" in
    x86_64) echo "amd64" ;;
    aarch64|arm64) echo "arm64" ;;
    *) echo "amd64" ;;
  esac
}

build_release_asset_url() {
  local asset_name="$1"
  if [[ "$RELEASE_TAG" == "latest" ]]; then
    echo "https://github.com/${REPO_OWNER}/${REPO_NAME}/releases/latest/download/${asset_name}"
  else
    echo "https://github.com/${REPO_OWNER}/${REPO_NAME}/releases/download/${RELEASE_TAG}/${asset_name}"
  fi
}

get_release_page_url() {
  if [[ "$RELEASE_TAG" == "latest" ]]; then
    echo "https://github.com/${REPO_OWNER}/${REPO_NAME}/releases"
  else
    echo "https://github.com/${REPO_OWNER}/${REPO_NAME}/releases/tag/${RELEASE_TAG}"
  fi
}

build_download_url() {
  build_release_asset_url "gost-$(get_architecture)"
}

get_source_ref() {
  if [[ "$RELEASE_TAG" == "latest" ]]; then
    echo "main"
  else
    echo "$RELEASE_TAG"
  fi
}

get_source_archive_url() {
  if [[ "$RELEASE_TAG" == "latest" ]]; then
    echo "https://codeload.github.com/${REPO_OWNER}/${REPO_NAME}/tar.gz/refs/heads/main"
  else
    echo "https://codeload.github.com/${REPO_OWNER}/${REPO_NAME}/tar.gz/refs/tags/${RELEASE_TAG}"
  fi
}

RAW_DOWNLOAD_URL="$(build_download_url)"
DOWNLOAD_URL="$RAW_DOWNLOAD_URL"
COUNTRY="$(curl -fsSL https://ipinfo.io/country 2>/dev/null || true)"
if [[ "$COUNTRY" == "CN" ]]; then
  DOWNLOAD_URL="https://ghfast.top/${DOWNLOAD_URL}"
fi

print_release_asset_error() {
  local asset_name="$1"
  echo "Error: missing GitHub release asset: ${asset_name}"
  echo "Release page: $(get_release_page_url)"
  if [[ "$RELEASE_TAG" == "latest" ]]; then
    echo "Create a release/tag first, then wait for the workflow to upload ${asset_name}."
  else
    echo "Make sure tag ${RELEASE_TAG} has been released and includes ${asset_name}."
  fi
}

assert_release_asset_exists() {
  local url="$1"
  local asset_name="$2"
  if ! curl -fsLI --connect-timeout 15 "$url" >/dev/null 2>&1; then
    print_release_asset_error "$asset_name"
    return 1
  fi
}

download_release_asset() {
  local url="$1"
  local output_path="$2"
  local asset_name="$3"

  rm -f "$output_path"
  if ! curl -fL --retry 3 --connect-timeout 15 "$url" -o "$output_path"; then
    print_release_asset_error "$asset_name"
    return 1
  fi

  if [[ ! -s "$output_path" ]]; then
    echo "Error: downloaded file is empty: ${asset_name}"
    return 1
  fi

  if grep -aqE '^(Not Found|404: Not Found)$|^<html|^<!DOCTYPE html' "$output_path"; then
    echo "Error: downloaded content is not a valid binary: ${asset_name}"
    print_release_asset_error "$asset_name"
    rm -f "$output_path"
    return 1
  fi

  return 0
}

install_go_toolchain() {
  if command -v go >/dev/null 2>&1; then
    return 0
  fi

  local go_version="1.23.4"
  local go_arch
  local go_url
  go_arch="$(get_architecture)"
  if [[ "$COUNTRY" == "CN" ]]; then
    go_url="https://golang.google.cn/dl/go${go_version}.linux-${go_arch}.tar.gz"
  else
    go_url="https://go.dev/dl/go${go_version}.linux-${go_arch}.tar.gz"
  fi

  echo "Installing Go toolchain..."
  if ! curl -fL --retry 3 --connect-timeout 15 "$go_url" -o /tmp/go-toolchain.tar.gz; then
    echo "Error: failed to download Go toolchain"
    return 1
  fi

  rm -rf /usr/local/go
  if ! tar -C /usr/local -xzf /tmp/go-toolchain.tar.gz; then
    echo "Error: failed to extract Go toolchain"
    rm -f /tmp/go-toolchain.tar.gz
    return 1
  fi
  rm -f /tmp/go-toolchain.tar.gz
  export PATH="/usr/local/go/bin:$PATH"
  return 0
}

build_flux_agent_from_source() {
  local output_path="$1"
  local temp_dir archive_url source_dir arch

  temp_dir="$(mktemp -d)"
  archive_url="$(get_source_archive_url)"
  if [[ "$COUNTRY" == "CN" ]]; then
    archive_url="https://ghfast.top/${archive_url}"
  fi

  echo "Release asset not ready, building from source..."
  if ! install_go_toolchain; then
    rm -rf "$temp_dir"
    return 1
  fi

  if ! curl -fL --retry 3 --connect-timeout 15 "$archive_url" -o "$temp_dir/repo.tar.gz"; then
    echo "Error: failed to download repository source"
    rm -rf "$temp_dir"
    return 1
  fi

  if ! tar -xzf "$temp_dir/repo.tar.gz" -C "$temp_dir"; then
    echo "Error: failed to extract repository source"
    rm -rf "$temp_dir"
    return 1
  fi

  source_dir="$(find "$temp_dir" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
  if [[ ! -d "$source_dir/go-gost" ]]; then
    echo "Error: go-gost source directory not found"
    rm -rf "$temp_dir"
    return 1
  fi

  arch="$(get_architecture)"
  export PATH="/usr/local/go/bin:$PATH"
  if ! (cd "$source_dir/go-gost" && GOOS=linux GOARCH="$arch" CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o "$output_path" .); then
    echo "Error: failed to build flux_agent from source"
    rm -rf "$temp_dir"
    return 1
  fi

  rm -rf "$temp_dir"
  chmod +x "$output_path"
  return 0
}

download_flux_agent_binary() {
  local output_path="$1"
  local asset_name="gost-$(get_architecture)"

  echo "Download URL: $DOWNLOAD_URL"
  if assert_release_asset_exists "$RAW_DOWNLOAD_URL" "$asset_name" && download_release_asset "$DOWNLOAD_URL" "$output_path" "$asset_name"; then
    chmod +x "$output_path"
  else
    if ! build_flux_agent_from_source "$output_path"; then
      return 1
    fi
  fi

  if ! "$output_path" -V >/dev/null 2>&1; then
    echo "Error: downloaded file cannot be executed: ${output_path}"
    print_release_asset_error "$asset_name"
    return 1
  fi

  return 0
}

show_menu() {
  echo "==============================================="
  echo "              Flux Agent Manager"
  echo "==============================================="
  echo "1. Install"
  echo "2. Update"
  echo "3. Uninstall"
  echo "4. Exit"
  echo "==============================================="
}

delete_self() {
  echo
  echo "Cleaning up script file..."
  local script_path
  script_path="$(readlink -f "$0" 2>/dev/null || realpath "$0" 2>/dev/null || echo "$0")"
  sleep 1
  rm -f "$script_path" && echo "Script file removed" || echo "Failed to remove script file"
}

check_and_install_tcpkill() {
  if command -v tcpkill >/dev/null 2>&1; then
    return 0
  fi

  local os_type distro sudo_cmd
  os_type="$(uname -s)"
  sudo_cmd=""
  if [[ $EUID -ne 0 ]]; then
    sudo_cmd="sudo"
  fi

  if [[ "$os_type" == "Darwin" ]]; then
    if command -v brew >/dev/null 2>&1; then
      brew install dsniff >/dev/null 2>&1 || true
    fi
    return 0
  fi

  if [[ -f /etc/os-release ]]; then
    . /etc/os-release
    distro="$ID"
  elif [[ -f /etc/redhat-release ]]; then
    distro="rhel"
  elif [[ -f /etc/debian_version ]]; then
    distro="debian"
  else
    return 0
  fi

  case "$distro" in
    ubuntu|debian)
      $sudo_cmd apt update >/dev/null 2>&1 || true
      $sudo_cmd apt install -y dsniff >/dev/null 2>&1 || true
      ;;
    centos|rhel|fedora)
      if command -v dnf >/dev/null 2>&1; then
        $sudo_cmd dnf install -y dsniff >/dev/null 2>&1 || true
      elif command -v yum >/dev/null 2>&1; then
        $sudo_cmd yum install -y dsniff >/dev/null 2>&1 || true
      fi
      ;;
    alpine)
      $sudo_cmd apk add --no-cache dsniff >/dev/null 2>&1 || true
      ;;
    arch|manjaro)
      $sudo_cmd pacman -S --noconfirm dsniff >/dev/null 2>&1 || true
      ;;
    opensuse*|sles)
      $sudo_cmd zypper install -y dsniff >/dev/null 2>&1 || true
      ;;
    gentoo)
      $sudo_cmd emerge --ask=n net-analyzer/dsniff >/dev/null 2>&1 || true
      ;;
    void)
      $sudo_cmd xbps-install -Sy dsniff >/dev/null 2>&1 || true
      ;;
  esac
}

get_config_params() {
  if [[ -z "${SERVER_ADDR:-}" || -z "${SECRET:-}" ]]; then
    echo "Enter install parameters:"

    if [[ -z "${SERVER_ADDR:-}" ]]; then
      read -r -p "Server address: " SERVER_ADDR
    fi

    if [[ -z "${SECRET:-}" ]]; then
      read -r -p "Secret: " SECRET
    fi

    if [[ -z "${SERVER_ADDR:-}" || -z "${SECRET:-}" ]]; then
      echo "Missing parameters, aborting."
      exit 1
    fi
  fi
}

while getopts "a:s:" opt; do
  case $opt in
    a) SERVER_ADDR="$OPTARG" ;;
    s) SECRET="$OPTARG" ;;
    *) echo "Invalid parameter"; exit 1 ;;
  esac
done

install_flux_agent() {
  echo "Installing flux_agent..."
  get_config_params
  check_and_install_tcpkill

  mkdir -p "$INSTALL_DIR"
  mkdir -p "$BIN_DIR"

  if systemctl list-units --full -all | grep -Fq "flux_agent.service"; then
    echo "Existing flux_agent service detected"
    systemctl stop flux_agent 2>/dev/null || true
    systemctl disable flux_agent 2>/dev/null || true
  fi

  rm -f "$BIN_PATH"
  rm -f "$INSTALL_DIR/flux_agent"

  echo "Downloading flux_agent..."
  if ! download_flux_agent_binary "$BIN_PATH"; then
    echo "Download failed. Check that the release/tag has been published correctly."
    exit 1
  fi

  echo "Version: $("$BIN_PATH" -V)"

  cat > "$INSTALL_DIR/config.json" <<EOF
{
  "addr": "$SERVER_ADDR",
  "secret": "$SECRET"
}
EOF

  if [[ ! -f "$INSTALL_DIR/gost.json" ]]; then
    cat > "$INSTALL_DIR/gost.json" <<EOF
{}
EOF
  fi

  chmod 600 "$INSTALL_DIR"/*.json

  cat > /etc/systemd/system/flux_agent.service <<EOF
[Unit]
Description=Flux_agent Proxy Service
After=network.target

[Service]
WorkingDirectory=$INSTALL_DIR
ExecStart=$BIN_PATH
Restart=on-failure

[Install]
WantedBy=multi-user.target
EOF

  systemctl daemon-reload
  systemctl enable flux_agent
  systemctl start flux_agent

  if systemctl is-active --quiet flux_agent; then
    echo "Install completed"
    echo "Config directory: $INSTALL_DIR"
    echo "Service status: $(systemctl is-active flux_agent)"
  else
    echo "flux_agent failed to start"
    echo "Check logs with: journalctl -u flux_agent -f"
  fi
}

update_flux_agent() {
  echo "Updating flux_agent..."

  if [[ ! -d "$INSTALL_DIR" ]]; then
    echo "flux_agent is not installed"
    return 1
  fi

  check_and_install_tcpkill

  echo "Downloading latest version..."
  if ! download_flux_agent_binary "${BIN_PATH}.new"; then
    echo "Download failed. Check that the release/tag has been published correctly."
    return 1
  fi

  if systemctl list-units --full -all | grep -Fq "flux_agent.service"; then
    systemctl stop flux_agent || true
  fi

  mv "${BIN_PATH}.new" "$BIN_PATH"
  chmod +x "$BIN_PATH"
  echo "Version: $("$BIN_PATH" -V)"

  systemctl start flux_agent
  echo "Update completed"
}

uninstall_flux_agent() {
  echo "Uninstalling flux_agent..."
  read -r -p "Remove flux_agent and all related files? (y/N): " confirm
  if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
    echo "Cancelled"
    return 0
  fi

  if systemctl list-units --full -all | grep -Fq "flux_agent.service"; then
    systemctl stop flux_agent 2>/dev/null || true
    systemctl disable flux_agent 2>/dev/null || true
  fi

  rm -f /etc/systemd/system/flux_agent.service
  rm -f "$BIN_PATH"
  rm -f "${BIN_PATH}.new"
  rm -f "$INSTALL_DIR/flux_agent"
  rm -rf "$INSTALL_DIR"
  systemctl daemon-reload

  echo "Uninstall completed"
}

main() {
  if [[ -n "${SERVER_ADDR:-}" && -n "${SECRET:-}" ]]; then
    install_flux_agent
    delete_self
    exit 0
  fi

  while true; do
    show_menu
    read -r -p "Choose an option (1-4): " choice

    case "$choice" in
      1)
        install_flux_agent
        delete_self
        exit 0
        ;;
      2)
        update_flux_agent
        delete_self
        exit 0
        ;;
      3)
        uninstall_flux_agent
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
