#!/bin/bash

set -e

echo "=== Nix Portable Installer (No Root Required) ==="
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

# Create directory for nix-portable
NIX_PORTABLE_DIR="$HOME/.nix-portable"
mkdir -p "$NIX_PORTABLE_DIR"
cd "$NIX_PORTABLE_DIR"

# Check if nix-portable already exists
if [ -f "./nix-portable" ]; then
    print_warning "nix-portable already exists, skipping download..."
else
    print_status "Downloading nix-portable..."
    wget https://github.com/DavHau/nix-portable/releases/latest/download/nix-portable-$(uname -m)
    chmod +x nix-portable-$(uname -m)
    mv nix-portable-$(uname -m) nix-portable
fi

# Check if channels are already set up
if ! ./nix-portable nix-channel --list | grep -q nixpkgs; then
    print_status "Setting up nix-portable channels..."
    ./nix-portable nix-channel --add https://nixos.org/channels/nixpkgs-unstable nixpkgs
    ./nix-portable nix-channel --update
else
    print_warning "Channels already configured, skipping..."
fi

# CREATE THE BIN DIRECTORY FIRST!
mkdir -p "$HOME/bin"

# Create or update wrapper script
if [ -f "$HOME/bin/nix-portable" ]; then
    print_warning "Wrapper script already exists, skipping..."
else
    cat > "$HOME/bin/nix-portable" << EOF
#!/bin/bash
exec "$NIX_PORTABLE_DIR/nix-portable" "\$@"
EOF
    chmod +x "$HOME/bin/nix-portable"
fi

# Function to check if a package is installed
is_package_installed() {
    local package=$1
    "$NIX_PORTABLE_DIR/nix-portable" nix-env -q | grep -q "^${package}-" && return 0 || return 1
}

# Install packages only if not already installed
print_status "Checking and installing packages..."

declare -A packages=(
    ["vlc"]="nixpkgs.vlc"
    ["opencv"]="nixpkgs.opencv"
    ["qemu"]="nixpkgs.qemu"
    ["ffmpeg"]="nixpkgs.ffmpeg-full"
)

for pkg_name in "${!packages[@]}"; do
    if is_package_installed "$pkg_name"; then
        print_warning "$pkg_name is already installed, skipping..."
    else
        print_status "Installing $pkg_name..."
        "$NIX_PORTABLE_DIR/nix-portable" nix-env -iA "${packages[$pkg_name]}"
    fi
done

# Create environment setup script if it doesn't exist
if [ -f "$HOME/nix-portable-env.sh" ]; then
    print_warning "Environment setup script already exists, skipping..."
else
    print_status "Creating environment setup script..."
    cat > "$HOME/nix-portable-env.sh" << 'EOF'
#!/bin/bash
export PATH="$HOME/bin:$HOME/.nix-portable/nix-portable-profile/bin:$PATH"
export NIX_PORTABLE="$HOME/.nix-portable/nix-portable"
alias nix="$NIX_PORTABLE"
alias nix-env="$NIX_PORTABLE nix-env"
alias nix-shell="$NIX_PORTABLE nix-shell"
echo "Nix-portable environment loaded!"
EOF
    chmod +x "$HOME/nix-portable-env.sh"
fi

echo
print_status "Setup complete!"
echo "To use, run: source $HOME/nix-portable-env.sh"
echo "Then use commands like:"
echo "  nix-portable nix-env -iA nixpkgs.packagename"
echo "  nix-portable nix-shell -p package1 package2"