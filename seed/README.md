# NoSlop Seed - Smart Installer

## Overview

NoSlop Seed is the intelligent deployment system for the NoSlop framework. It automatically discovers devices on your local network, assesses their hardware capabilities, assigns optimal roles, and orchestrates the installation of all required services.

## Features

✅ **Intelligent Role Assignment**
- Automatic master node election based on capability scores
- GPU-aware compute node selection
- Storage node assignment for large disk devices
- Single-device all-in-one mode support

✅ **Hardware Detection**
- CPU cores, speed, and architecture
- RAM total and available
- GPU vendor, VRAM, and count (NVIDIA/AMD/Apple/Intel)
- Disk space
- OS type and version
- SSH availability

✅ **Network Discovery**
- Automatic local network scanning
- SSH-enabled device detection
- Manual IP address entry
- Hostname resolution

✅ **Deployment Orchestration**
- Deployment plan validation
- Node-specific configuration generation
- .env file creation for each node
- Service mapping based on roles
- Deployment artifact management

✅ **CLI Interface**
- Interactive deployment wizard
- Single-device mode
- Multi-device mode
- Network scanning
- Manual device selection

## Architecture

### Components

1. **hardware_detector.py** - Detects system capabilities
2. **network_scanner.py** - Discovers SSH-enabled devices
3. **models.py** - Data models for devices, roles, and deployment plans
4. **role_assigner.py** - Intelligent role assignment algorithm
5. **ssh_manager.py** - SSH key generation and distribution
6. **deployer.py** - Deployment orchestration
7. **seed_cli.py** - Command-line interface

### Role Types

- **MASTER** - Runs backend, database, Admin AI (Ollama)
- **COMPUTE** - Runs ComfyUI, FFmpeg, OpenCV (requires GPU)
- **STORAGE** - Stores media and blockchain data (requires large disk)
- **CLIENT** - Runs frontend (Next.js)
- **ALL** - Single-device mode (all services)

### Capability Scoring

Devices are scored 0-100 based on weighted hardware metrics:
- RAM: 40%
- GPU/VRAM: 30%
- CPU: 20%
- Disk: 10%

## Installation

### Prerequisites

```bash
# System packages
sudo apt install python3 python3-pip python3-paramiko ssh

# Python dependencies
pip install -r requirements.txt
```

### Dependencies

- `psutil>=5.9.0` - Hardware detection
- `paramiko>=3.0.0` - SSH operations

## Usage

### Single Device Deployment

Deploy NoSlop on the current device only (all-in-one mode):

```bash
python3 seed_cli.py --single-device
```

### Interactive Multi-Device Deployment

Launch the interactive deployment wizard:

```bash
python3 seed_cli.py
```

The wizard will:
1. Scan your local network for SSH-enabled devices
2. Detect hardware capabilities
3. Allow device selection
4. Create an optimized deployment plan
5. Generate configuration files
6. Execute deployment

### Command-Line Options

```bash
python3 seed_cli.py [OPTIONS]

Options:
  --single-device       Deploy on current device only
  --network CIDR        Network range to scan (e.g., 192.168.1.0/24)
  --ips IP1,IP2,...     Comma-separated list of IPs to use
  --skip-scan           Skip network scan (use manual IPs)
  --log-level LEVEL     Logging level (DEBUG/INFO/WARN/ERROR)
  --output-dir DIR      Directory for deployment artifacts
```

### Examples

```bash
# Single device deployment
python3 seed_cli.py --single-device

# Scan specific network
python3 seed_cli.py --network 192.168.1.0/24

# Use specific IPs
python3 seed_cli.py --ips 192.168.1.10,192.168.1.11,192.168.1.12

# Debug mode
python3 seed_cli.py --log-level DEBUG
```

## Testing Components

### Test Hardware Detection

```bash
python3 hardware_detector.py
```

Output:
```
==============================================================
Hardware Detection Results
==============================================================
Hostname: BigBOY
IP Address: 10.0.0.3

CPU: 16 cores @ 5.0 GHz
Architecture: x86_64

RAM: 31.15 GB (Available: 20.64 GB)

GPU: nvidia
  Model: NVIDIA GeForce RTX 3060
  VRAM: 12.0 GB (Available: 9.49 GB)
  Count: 1

Disk: 379.34 GB (Available: 142.17 GB)

OS: linux
Version: Linux-6.14.0-36-generic-x86_64-with-glibc2.39

SSH Available: false

Capability Score: 58.17/100
Meets Requirements: false
==============================================================
```

### Test Network Scanning

```bash
python3 network_scanner.py
```

### Test Role Assignment

```bash
python3 role_assigner.py
```

Output shows deployment plans for both single-device and multi-device scenarios.

### Test Deployment Orchestrator

```bash
python3 deployer.py
```

Generates deployment artifacts in `~/.noslop/deployments/`.

## Deployment Artifacts

After running the installer, deployment artifacts are saved to:

```
~/.noslop/deployments/<timestamp>/
├── deployment_plan.json    # Complete deployment plan
├── <hostname1>.env          # Environment config for node 1
├── <hostname2>.env          # Environment config for node 2
└── ...
```

### Example .env File

```bash
# NoSlop Environment Configuration
# Node: BigBOY
# Generated: 2025-12-01T18:26:46.368709

COMFYUI_URLS=http://10.0.0.3:8188
NOSLOP_BACKEND_URL=http://10.0.0.3:8000
NOSLOP_BLOCKCHAIN_PATH=/var/noslop/blockchain
NOSLOP_DATABASE_URL=postgresql://noslop:noslop@localhost:5432/noslop
NOSLOP_IMAGE_MODEL=stable-diffusion
NOSLOP_LOGIC_MODEL=llama3.2:latest
NOSLOP_MASTER_HOSTNAME=BigBOY
NOSLOP_MASTER_IP=10.0.0.3
NOSLOP_MEDIA_PATH=/var/noslop/media
NOSLOP_NODE_HOSTNAME=BigBOY
NOSLOP_NODE_IP=10.0.0.3
NOSLOP_NODE_ROLES=all
NOSLOP_VIDEO_MODEL=animatediff
OLLAMA_URL=http://10.0.0.3:11434
```

## Minimum Requirements

**Basic (will work, but may be slow)**:
- **CPU**: 2+ cores
- **RAM**: 4 GB
- **GPU**: Optional (CPU-only mode available)
- **Disk**: 100 GB
- **OS**: Linux, macOS, or Windows (WSL2)
- **Network**: SSH access (port 22)

**Recommended (for good performance)**:
- **CPU**: 4+ cores
- **RAM**: 16 GB
- **GPU**: 8 GB VRAM (NVIDIA/AMD)
- **Disk**: 500 GB
- **OS**: Linux (Ubuntu 22.04+)

**Optimal (for best experience)**:
- **CPU**: 8+ cores
- **RAM**: 32 GB
- **GPU**: 12+ GB VRAM (NVIDIA RTX series)
- **Disk**: 1 TB SSD
- **OS**: Linux (Ubuntu 22.04+)

## Current Status

### ✅ Phase 1 Complete: Core Infrastructure

- **Hardware detection** - CPU, RAM, GPU, disk, OS detection across all platforms
- **Network scanning** - SSH-enabled device discovery on local networks
- **Role assignment** - Intelligent master election with weighted capability scoring
- **SSH key management** - Ed25519 key generation and distribution framework
- **Deployment orchestration** - Plan validation, config generation, .env file creation
- **CLI interface** - Interactive wizard with single-device and multi-device modes
- **Dynamic service mapping** - Services automatically assigned based on device roles:
  - MASTER nodes → Ollama, Backend, PostgreSQL
  - COMPUTE nodes → ComfyUI, FFmpeg, OpenCV
  - CLIENT nodes → Frontend
  - STORAGE nodes → Media and blockchain storage

### 🚧 Phase 2 In Progress: Service Installers

**What's Needed**: Automated installation of services on assigned nodes via SSH.

**Planned Components**:

1. **Base Installer Framework** (`installers/base_installer.py`)
   - Abstract base class for all service installers
   - Common methods: `check_installed()`, `install()`, `configure()`, `start()`, `verify()`
   - OS-specific installation logic (apt/brew/chocolatey)
   - Error handling and rollback support

2. **Service Installers**:
   - **Ollama Installer** - Download and install Ollama, pull required models (llama3.2, etc.)
   - **ComfyUI Installer** - Clone repo, install dependencies, configure for GPU (CUDA/ROCm/Metal)
   - **FFmpeg Installer** - Install FFmpeg and OpenCV via package managers
   - **PostgreSQL Installer** - Install database, create noslop user and database
   - **Backend Installer** - Copy backend files, create venv, install requirements, start service
   - **Frontend Installer** - Copy frontend files, npm install, build, start service

3. **Remote Execution**:
   - SSH-based command execution on remote nodes
   - File transfer (backend/frontend code) to nodes
   - Service startup via systemd/launchd
   - Health check verification

4. **Deployer Integration**:
   - Update `deployer.py` to execute service installers
   - Progress tracking and real-time logging
   - Rollback on installation failure
   - Post-deployment verification

**Current Capability**: The installer can discover devices, assign roles, and generate configurations. It does **not yet** install services automatically - this requires Phase 2 implementation.

### 📋 Phase 3 Planned: Advanced Features

- Remote hardware detection via SSH
- Web-based installer UI
- Update/upgrade mechanism
- Multi-cluster management
- Deployment templates

## Development

### Project Structure

```
seed/
├── __init__.py
├── hardware_detector.py    # Hardware capability detection
├── network_scanner.py      # Network device discovery
├── models.py               # Data models
├── role_assigner.py        # Role assignment logic
├── ssh_manager.py          # SSH key management
├── deployer.py             # Deployment orchestration
├── seed_cli.py             # CLI interface
├── requirements.txt        # Python dependencies
└── installers/             # Service installers (TODO)
    ├── base_installer.py
    ├── ollama_installer.py
    ├── comfyui_installer.py
    ├── backend_installer.py
    └── frontend_installer.py
```

### Adding New Service Installers

1. Create installer in `installers/` directory
2. Inherit from `BaseInstaller`
3. Implement required methods:
   - `check_installed()`
   - `install()`
   - `configure()`
   - `start()`
   - `verify()`
4. Register in `deployer.py`

## Troubleshooting

### "Device does not meet minimum requirements"

The installer will warn if your device doesn't meet the recommended specs. You can proceed anyway, but performance may be limited.

### "paramiko not available"

Install paramiko:
```bash
sudo apt install python3-paramiko
# or
pip install paramiko
```

### "No SSH-enabled devices found"

Ensure:
- SSH is enabled on target devices
- Devices are on the same network
- Firewall allows SSH connections (port 22)

## License

Open Source. Built for the people. ❤️
