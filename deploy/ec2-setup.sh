#!/bin/bash
# EC2 Instance Setup Script for Inboop Backend
# Run this script on a fresh Amazon Linux 2023 or Ubuntu 22.04 EC2 instance
#
# Prerequisites:
# - EC2 instance with IAM Role that has SecretsManager access
# - Security group allowing inbound on ports 22, 80, 443, 8080

set -e

echo "=== Inboop Backend EC2 Setup ==="

# Detect OS
if [ -f /etc/os-release ]; then
    . /etc/os-release
    OS=$ID
else
    echo "Cannot detect OS"
    exit 1
fi

echo "Detected OS: $OS"

# Install Docker
install_docker() {
    if command -v docker &> /dev/null; then
        echo "Docker already installed"
        return
    fi

    echo "Installing Docker..."
    if [ "$OS" = "amzn" ]; then
        # Amazon Linux 2023
        sudo dnf update -y
        sudo dnf install -y docker
        sudo systemctl start docker
        sudo systemctl enable docker
        sudo usermod -aG docker $USER
    elif [ "$OS" = "ubuntu" ]; then
        # Ubuntu
        sudo apt-get update
        sudo apt-get install -y ca-certificates curl gnupg
        sudo install -m 0755 -d /etc/apt/keyrings
        curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
        sudo chmod a+r /etc/apt/keyrings/docker.gpg
        echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
        sudo apt-get update
        sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
        sudo usermod -aG docker $USER
    else
        echo "Unsupported OS: $OS"
        exit 1
    fi
    echo "Docker installed successfully"
}

# Install Docker Compose
install_docker_compose() {
    if command -v docker-compose &> /dev/null || docker compose version &> /dev/null; then
        echo "Docker Compose already installed"
        return
    fi

    echo "Installing Docker Compose..."
    if [ "$OS" = "amzn" ]; then
        sudo curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
        sudo chmod +x /usr/local/bin/docker-compose
    fi
    echo "Docker Compose installed successfully"
}

# Install Git
install_git() {
    if command -v git &> /dev/null; then
        echo "Git already installed"
        return
    fi

    echo "Installing Git..."
    if [ "$OS" = "amzn" ]; then
        sudo dnf install -y git
    elif [ "$OS" = "ubuntu" ]; then
        sudo apt-get install -y git
    fi
    echo "Git installed successfully"
}

# Install AWS CLI (for ECR login if needed)
install_aws_cli() {
    if command -v aws &> /dev/null; then
        echo "AWS CLI already installed"
        return
    fi

    echo "Installing AWS CLI..."
    if [ "$OS" = "amzn" ]; then
        sudo dnf install -y aws-cli
    elif [ "$OS" = "ubuntu" ]; then
        sudo apt-get install -y awscli
    fi
    echo "AWS CLI installed successfully"
}

# Create application directory
setup_app_directory() {
    echo "Setting up application directory..."
    sudo mkdir -p /opt/inboop
    sudo chown $USER:$USER /opt/inboop
    echo "Application directory created at /opt/inboop"
}

# Create environment file template
create_env_template() {
    echo "Creating environment file template..."
    cat > /opt/inboop/.env << 'EOF'
# AWS Configuration (credentials from IAM Role)
AWS_REGION=us-east-2
AWS_SECRET_NAME=your_secret_arn_here

# Database
DB_HOST=your_rds_endpoint_here
DB_NAME=inboop

# Google OAuth2 (optional)
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=

# Application URL (update with your EC2 public DNS or domain)
BACKEND_URL=http://localhost:8080

# CORS (frontend URLs)
ALLOWED_ORIGINS=http://localhost:3000

# Instagram Webhook
INSTAGRAM_WEBHOOK_VERIFY_TOKEN=your_secure_verify_token

# DDL Auto (use 'validate' in production after initial setup)
DDL_AUTO=update
EOF
    echo "Environment file created at /opt/inboop/.env"
}

# Create systemd service for auto-start
create_systemd_service() {
    echo "Creating systemd service..."

    # Determine docker-compose command
    if command -v docker-compose &> /dev/null; then
        COMPOSE_CMD="/usr/local/bin/docker-compose"
    else
        COMPOSE_CMD="/usr/bin/docker compose"
    fi

    sudo tee /etc/systemd/system/inboop.service > /dev/null << EOF
[Unit]
Description=Inboop Backend Service
Requires=docker.service
After=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=/opt/inboop
ExecStart=$COMPOSE_CMD -f docker-compose.prod.yml up -d
ExecStop=$COMPOSE_CMD -f docker-compose.prod.yml down
User=$USER
Group=docker

[Install]
WantedBy=multi-user.target
EOF

    sudo systemctl daemon-reload
    echo "Systemd service created"
}

# Clone repository
clone_repository() {
    if [ -d /opt/inboop/.git ]; then
        echo "Repository already cloned"
        return
    fi

    echo ""
    read -p "Enter your GitHub repository URL (e.g., https://github.com/user/InboopBackend.git): " REPO_URL

    if [ -n "$REPO_URL" ]; then
        cd /opt/inboop
        git clone "$REPO_URL" .
        echo "Repository cloned successfully"
    else
        echo "Skipping repository clone. Clone manually later."
    fi
}

# Main execution
main() {
    install_docker
    install_docker_compose
    install_git
    install_aws_cli
    setup_app_directory
    create_env_template
    create_systemd_service

    echo ""
    echo "=== Base Setup Complete ==="
    echo ""

    # Ask to clone repository
    clone_repository

    echo ""
    echo "=== Setup Complete ==="
    echo ""
    echo "IMPORTANT: Log out and back in for docker group membership to take effect!"
    echo ""
    echo "After logging back in:"
    echo "1. Update /opt/inboop/.env with your settings"
    echo "2. Start the application:"
    echo "   cd /opt/inboop"
    echo "   docker-compose -f docker-compose.prod.yml up -d --build"
    echo ""
    echo "3. Enable auto-start on boot:"
    echo "   sudo systemctl enable inboop"
    echo ""
    echo "Useful commands:"
    echo "  View logs: docker-compose -f docker-compose.prod.yml logs -f"
    echo "  Restart:   docker-compose -f docker-compose.prod.yml restart"
    echo "  Stop:      docker-compose -f docker-compose.prod.yml down"
    echo "  Update:    git pull && docker-compose -f docker-compose.prod.yml up -d --build"
    echo ""
    echo "For GitHub Actions deployment, add these secrets to your repository:"
    echo "  EC2_HOST:     Your EC2 public IP or DNS"
    echo "  EC2_USERNAME: ec2-user (Amazon Linux) or ubuntu (Ubuntu)"
    echo "  EC2_SSH_KEY:  Your EC2 private key content"
}

main
