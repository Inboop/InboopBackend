#!/bin/bash
# Deployment script for Inboop Backend
# Run this script from the project root on the EC2 instance

set -e

echo "=== Deploying Inboop Backend ==="

# Check if .env exists
if [ ! -f .env ]; then
    echo "ERROR: .env file not found!"
    echo "Create .env from .env.template and configure your environment variables"
    exit 1
fi

# Pull latest changes (if git repo)
if [ -d .git ]; then
    echo "Pulling latest changes..."
    git pull
fi

# Build and start containers
echo "Building and starting containers..."
docker-compose down --remove-orphans || true
docker-compose build --no-cache
docker-compose up -d

# Wait for health check
echo "Waiting for application to start..."
sleep 30

# Check health
echo "Checking application health..."
if curl -f http://localhost:8080/actuator/health > /dev/null 2>&1; then
    echo "Application is healthy!"
else
    echo "WARNING: Health check failed. Checking logs..."
    docker-compose logs --tail=50 app
fi

echo ""
echo "=== Deployment Complete ==="
echo "Application URL: http://$(curl -s http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null || echo 'your-ec2-ip'):8080"
