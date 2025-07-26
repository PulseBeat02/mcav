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

print_status "Downloading nix-portable..."
wget https://github.com/DavHau/nix-portable/releases/latest/download/nix-portable-$(uname -m)
chmod +x nix-portable-$(uname -m)
mv nix-portable-$(uname -m) nix-portable

print_status "Setting up nix-portable..."
./nix-portable nix-channel --add https://nixos.org/channels/nixpkgs-unstable nixpkgs
./nix-portable nix-channel --update

# CREATE THE BIN DIRECTORY FIRST!
mkdir -p "$HOME/bin"

# Now create wrapper script
cat > "$HOME/bin/nix-portable" << EOF
#!/bin/bash
exec "$NIX_PORTABLE_DIR/nix-portable" "\$@"
EOF
chmod +x "$HOME/bin/nix-portable"

print_status "Installing packages..."
"$NIX_PORTABLE_DIR/nix-portable" nix-env -iA nixpkgs.vlc
"$NIX_PORTABLE_DIR/nix-portable" nix-env -iA nixpkgs.opencv
"$NIX_PORTABLE_DIR/nix-portable" nix-env -iA nixpkgs.qemu
"$NIX_PORTABLE_DIR/nix-portable" nix-env -iA nixpkgs.ffmpeg-full

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

echo
print_status "Setup complete!"
echo "To use, run: source $HOME/nix-portable-env.sh"
echo "Then use commands like:"
echo "  nix-portable nix-env -iA nixpkgs.packagename"
echo "  nix-portable nix-shell -p package1 package2"