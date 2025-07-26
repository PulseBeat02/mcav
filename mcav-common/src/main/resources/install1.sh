#!/bin/bash

set -e

echo "=== Nix Package Installer (No Root Required) ==="
echo

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

print_status() {
    echo -e "${GREEN}[*]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[!]${NC} $1"
}

print_error() {
    echo -e "${RED}[X]${NC} $1"
}

print_status "Installing Nix package manager in single-user mode..."
TEMP_DIR=$(mktemp -d)
cd "$TEMP_DIR"
curl -L https://nixos.org/nix/install > install-nix.sh
chmod +x install-nix.sh
sh install-nix.sh --no-daemon
cd -
rm -rf "$TEMP_DIR"
print_status "Nix installation completed!"

print_status "Setting up Nix environment..."
if [ -e "$HOME/.nix-profile/etc/profile.d/nix.sh" ]; then
    . "$HOME/.nix-profile/etc/profile.d/nix.sh"
elif [ -e "/nix/var/nix/profiles/default/etc/profile.d/nix-daemon.sh" ]; then
    . "/nix/var/nix/profiles/default/etc/profile.d/nix-daemon.sh"
else
    print_error "Could not find Nix profile script. Please restart your shell and run this script again."
    exit 1
fi

print_status "Updating Nix channels..."
nix-channel --add https://nixos.org/channels/nixpkgs-unstable nixpkgs
nix-channel --update

install_package() {
    local pkg_name=$1
    local display_name=$2

    print_status "Installing $display_name..."
    if nix-env -iA nixpkgs.$pkg_name; then
        print_status "$display_name installed successfully!"
    else
        print_warning "Failed to install $display_name, trying alternative method..."
        nix-env -i $pkg_name
    fi
}

print_status "Installing requested packages..."
echo

install_package "vlc" "VLC media player"
install_package "opencv" "OpenCV (development libraries)"
install_package "qemu" "QEMU (user-mode emulation)"
install_package "ffmpeg-full" "FFmpeg (full version)"

echo
print_status "All packages have been installed!"
echo

print_status "Creating environment setup script..."
cat > "$HOME/nix-env-setup.sh" << 'EOF'
#!/bin/bash
# Source this file to set up Nix environment

if [ -e "$HOME/.nix-profile/etc/profile.d/nix.sh" ]; then
    . "$HOME/.nix-profile/etc/profile.d/nix.sh"
fi

export PATH="$HOME/.nix-profile/bin:$PATH"
export LD_LIBRARY_PATH="$HOME/.nix-profile/lib:$LD_LIBRARY_PATH"
export PKG_CONFIG_PATH="$HOME/.nix-profile/lib/pkgconfig:$PKG_CONFIG_PATH"

echo "Nix environment loaded!"
EOF

chmod +x "$HOME/nix-env-setup.sh"

echo
print_status "Package locations:"
echo "  VLC:      $(which vlc 2>/dev/null || echo 'Not found in PATH')"
echo "  FFmpeg:   $(which ffmpeg 2>/dev/null || echo 'Not found in PATH')"
echo "  QEMU:     $(which qemu-x86_64 2>/dev/null || echo 'Not found in PATH')"
echo "  OpenCV:   $HOME/.nix-profile/lib/pkgconfig/opencv4.pc"

echo
print_status "Setup complete!"
echo
echo "IMPORTANT: To use these packages in future shell sessions, either:"
echo "  1. Source the Nix environment: . $HOME/nix-env-setup.sh"
echo "  2. Or add this to your ~/.bashrc or ~/.zshrc:"
echo "     . $HOME/.nix-profile/etc/profile.d/nix.sh"
echo
echo "To verify installation:"
echo "  - VLC:     vlc --version"
echo "  - FFmpeg:  ffmpeg -version"
echo "  - QEMU:    qemu-x86_64 --version"
echo "  - OpenCV:  pkg-config --modversion opencv4"
echo

if ! command -v nix-env &> /dev/null; then
    print_warning "Please run: source $HOME/nix-env-setup.sh"
    print_warning "Or restart your shell to load the Nix environment"
fi

cat > "$HOME/nix-uninstall-packages.sh" << 'EOF'
#!/bin/bash
# Uninstall script for the packages

echo "Uninstalling packages..."
nix-env -e vlc opencv qemu ffmpeg-full

echo "To completely remove Nix, run:"
echo "  rm -rf ~/.nix-profile ~/.nix-defexpr ~/.nix-channels ~/.config/nixpkgs"
echo "  rm -rf /nix  # (might need sudo)"
EOF

chmod +x "$HOME/nix-uninstall-packages.sh"
print_status "Created uninstall script at: $HOME/nix-uninstall-packages.sh"