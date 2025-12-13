Yes# EC2 Deployment Guide for Inboop Backend

This guide covers deploying the Inboop Backend to AWS EC2 with GitHub Actions CI/CD.

## Prerequisites

- AWS Account with EC2 access
- SSH key pair for EC2 access
- Domain name (optional, but recommended for production)

## Step 1: Launch EC2 Instance

### Recommended Instance Configuration

| Setting | Value |
|---------|-------|
| AMI | Amazon Linux 2023 or Ubuntu 22.04 LTS |
| Instance Type | t3.small (minimum) or t3.medium (recommended) |
| Storage | 20 GB gp3 SSD |
| Security Group | See below |

### Security Group Rules

| Type | Port | Source | Description |
|------|------|--------|-------------|
| SSH | 22 | Your IP | SSH access |
| HTTP | 80 | 0.0.0.0/0 | Web traffic (if using reverse proxy) |
| HTTPS | 443 | 0.0.0.0/0 | Secure web traffic |
| Custom TCP | 8080 | 0.0.0.0/0 | Application port |

## Step 2: Connect and Setup Instance

```bash
# Connect to your EC2 instance
ssh -i your-key.pem ec2-user@your-ec2-public-ip

# For Ubuntu:
ssh -i your-key.pem ubuntu@your-ec2-public-ip
```

### Run the Setup Script

```bash
# Download and run setup script
curl -O https://raw.githubusercontent.com/your-repo/InboopBackend/master/deploy/ec2-setup.sh
chmod +x ec2-setup.sh
./ec2-setup.sh

# IMPORTANT: Log out and back in for Docker group membership
exit
# Reconnect via SSH
```

## Step 3: Deploy the Application

### Option A: Clone from Git Repository

```bash
cd /opt/inboop

# Clone your repository
git clone https://github.com/your-username/InboopBackend.git .

# Create environment file
cp .env.example .env
nano .env  # Edit with your values
```

### Option B: Copy Files Manually

```bash
# From your local machine
scp -i your-key.pem -r ./InboopBackend/* ec2-user@your-ec2-ip:/opt/inboop/

# On EC2, create .env
cd /opt/inboop
cp .env.example .env
nano .env
```

### Configure Environment Variables

Edit `/opt/inboop/.env` with your values:

```bash
# Database (change this!)
DB_PASSWORD=your_very_secure_password_here

# Google OAuth2 (from Google Cloud Console)
GOOGLE_CLIENT_ID=your-client-id.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=your-client-secret

# Application URL (use your EC2 public DNS or domain)
BACKEND_URL=http://ec2-xx-xx-xx-xx.compute.amazonaws.com:8080

# CORS (your frontend URL)
ALLOWED_ORIGINS=https://your-frontend.com

# Instagram webhook token (generate a random secure token)
INSTAGRAM_WEBHOOK_VERIFY_TOKEN=random_secure_token_here
```

### Start the Application

```bash
cd /opt/inboop
./deploy/deploy.sh

# Or manually:
docker-compose up -d
```

## Step 4: Verify Deployment

```bash
# Check containers are running
docker-compose ps

# Check application logs
docker-compose logs -f app

# Test health endpoint
curl http://localhost:8080/actuator/health
```

## Step 5: Setup Auto-Start (Optional)

```bash
# Enable systemd service to start on boot
sudo systemctl enable inboop
sudo systemctl start inboop
```

## Updating the Application

```bash
cd /opt/inboop

# Pull latest code
git pull

# Rebuild and restart
docker-compose down
docker-compose build --no-cache
docker-compose up -d

# Or use the deploy script
./deploy/deploy.sh
```

## Setting Up HTTPS with Nginx (Recommended)

### Install Nginx

```bash
# Amazon Linux 2023
sudo dnf install -y nginx

# Ubuntu
sudo apt-get install -y nginx
```

### Configure Nginx as Reverse Proxy

Create `/etc/nginx/conf.d/inboop.conf`:

```nginx
server {
    listen 80;
    server_name your-domain.com;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

### Add SSL with Let's Encrypt

```bash
# Install certbot
sudo dnf install -y certbot python3-certbot-nginx  # Amazon Linux
sudo apt-get install -y certbot python3-certbot-nginx  # Ubuntu

# Get certificate
sudo certbot --nginx -d your-domain.com

# Auto-renewal is configured automatically
```

## Troubleshooting

### View Application Logs

```bash
docker-compose logs -f app
```

### View Database Logs

```bash
docker-compose logs -f db
```

### Restart Services

```bash
docker-compose restart
```

### Reset Everything

```bash
docker-compose down -v  # Warning: This deletes database data!
docker-compose up -d
```

### Check Resource Usage

```bash
docker stats
```

## Production Checklist

- [ ] Use a strong, unique DB_PASSWORD
- [ ] Configure proper CORS origins (not localhost)
- [ ] Set up HTTPS with SSL certificate
- [ ] Configure Google OAuth redirect URI
- [ ] Set up CloudWatch or other monitoring
- [ ] Configure automatic backups for PostgreSQL
- [ ] Use AWS Secrets Manager for sensitive values
- [ ] Set up a domain name with Route 53

## Architecture Diagram

```
                    ┌─────────────┐
                    │   Client    │
                    │  (Browser)  │
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │   Nginx     │
                    │  (HTTPS)    │
                    └──────┬──────┘
                           │ :8080
              ┌────────────┴────────────┐
              │     Docker Network      │
              │  ┌─────────────────┐    │
              │  │  Inboop Backend │    │
              │  │   (Spring Boot) │    │
              │  └────────┬────────┘    │
              │           │             │
              │  ┌────────▼────────┐    │
              │  │   PostgreSQL    │    │
              │  │    Database     │    │
              │  └─────────────────┘    │
              └─────────────────────────┘
                      EC2 Instance
```

## GitHub Actions CI/CD Setup

### Step 1: Add Repository Secrets

Go to your GitHub repository → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**

Add these secrets:

| Secret Name | Value |
|-------------|-------|
| `EC2_HOST` | Your EC2 public IP or DNS (e.g., `ec2-xx-xx-xx-xx.us-east-2.compute.amazonaws.com`) |
| `EC2_USERNAME` | `ec2-user` (Amazon Linux) or `ubuntu` (Ubuntu) |
| `EC2_SSH_KEY` | Contents of your EC2 private key file (`.pem`) |
| `AWS_ACCESS_KEY_ID` | Your AWS access key (for ECR workflow only) |
| `AWS_SECRET_ACCESS_KEY` | Your AWS secret key (for ECR workflow only) |

### Step 2: Get SSH Key Content

```bash
cat ~/.ssh/your-ec2-key.pem
```

Copy the entire output including `-----BEGIN RSA PRIVATE KEY-----` and `-----END RSA PRIVATE KEY-----`.

### Step 3: Choose Deployment Workflow

Two workflows are available:

#### Option A: Simple Deployment (Recommended)
- File: `.github/workflows/deploy-simple.yml`
- Builds on EC2 directly
- No ECR required
- Simpler setup

#### Option B: ECR Deployment
- File: `.github/workflows/deploy.yml`
- Builds image in GitHub Actions
- Pushes to Amazon ECR
- Faster deployments (pre-built image)
- Requires ECR repository setup

### Step 4: Trigger Deployment

Push to the `master` branch:

```bash
git add .
git commit -m "Deploy to EC2"
git push origin master
```

GitHub Actions will:
1. Run tests
2. SSH into EC2
3. Pull latest code
4. Build and restart Docker container
5. Run health check

### Monitoring Deployments

- Go to **Actions** tab in your GitHub repository
- Click on the workflow run to see logs
- Green checkmark = successful deployment

### Manual Deployment Trigger

You can also manually trigger deployment:
1. Go to **Actions** tab
2. Select "Build and Deploy to EC2 (Simple)"
3. Click **Run workflow**

## Cost Estimate

| Resource | Monthly Cost (USD) |
|----------|-------------------|
| t3.small EC2 | ~$15 |
| 20GB gp3 Storage | ~$2 |
| Data Transfer (10GB) | ~$1 |
| **Total** | **~$18/month** |

*Note: Costs vary by region. Consider Reserved Instances for production workloads.*
