<!-- # START OF FILE README.md -->
<!-- # IT IS CRITICAL THAT ALL AIs and LLMs FOLLOW THE DEVELOPMENT INSTRUCTIONS IN THE `helperfiles/0_DEVELOPMENT_RULES.md` FILE WHEN FURTHER DEVELOPING THIS FRAMEWORK!!! -->

# NoSlop 🚫🥣
### **Self-hosted media making and sharing decentralized social network.**
*Power to the creators!* ✊✨

---

## 🎯 Vision

**NoSlop** is a revolutionary framework designed to empower humanity to create high-quality content without the "slop" of low quality generated content, and the centralized control or algorithmic manipulation of the big tech companies. It leverages **your** consumer hardware to build a private, secure, and AI-driven media production studio and social network.

**Own your data. Own your creativity. Share freely and securely.** 🌍🔓

---

## 🧩 Core Modules

### 🌱 **NoSlop Seed**
*The Smart Installer*
Automatically scans your local network and deploys the NoSlop cluster across available devices. It intelligently assigns roles (Master, Compute, Storage) based on hardware capabilities (CPU, GPU, RAM).

### 🎬 **NoSlop Media Creator**
*Your AI-Powered Studio*
A local, AI-driven media production cluster.
- **Admin AI**: Your personal creative director and **Pro Editor**. Capable of editing existing footage, color grading, and compiling complex films to industry standards. 🎞️✨
- **Worker Agents**: Specialized AI agents for scriptwriting, prompting, and editing. 👷‍♂️
- **Tools**: Integrates **ComfyUI** 🎨, **FFmpeg** 🎥, and **OpenCV** 👁️ for professional-grade output.

### ⛓️ **NoSlop Blockchain**
*Decentralized Registry*
Ensures media authenticity and provenance. A tamper-proof ledger that proves **you** created your content. 🛡️

### 🕸️ **NoSlop Social Network**
*The Mesh*
A peer-to-peer, decentralized social graph. Share content directly from your node to others without intermediate servers.
- **Ad-Free & Cost-Free**: No corporate overlords, no subscription fees, no data mining, and no advertisements. 🚫💰
- **Your Feed, Your Rules**: You control the algorithm. Tweak the parameters to see exactly what you want, not what an engagement engine forces on you. 🎛️
- **Guardian LLM**: A dedicated AI agent that protects you and the network. Combined with community up/down voting, it ensures a safe environment without censorship. 🛡️🗳️

---

## 🏗️ Architecture & Flow

1.  **Deploy**: Run **NoSlop Seed** to turn your home devices into a supercomputer. 🚀
2.  **Create**: Chat with your **Admin AI** to brainstorm ideas. 💡
3.  **Orchestrate**: The **Project Manager Agent** breaks down your vision into tasks. 📋
4.  **Execute**: **Worker Agents** generate images, edit video, and compose audio using local tools. ⚙️
5.  **Refine**: Iterate with the AI until it's perfect. ✨
6.  **Share**: Publish to the **NoSlop Network**, verified by the **Blockchain**. 📢

---

## 🛠️ Tech Stack

### **Backend**
- 🐍 **Python** (FastAPI)
- 🦙 **Ollama** (Local LLM Inference)
- 🎨 **ComfyUI** (Generative AI)
- 🎥 **FFmpeg & OpenCV** (Media Processing)

### **Frontend**
- ⚛️ **Next.js** (React Framework)
- 💅 **Tailwind CSS** (Styling)
- 📱 **Mobile App** (Planned)

### **Data & Storage**
- 🗄️ **PostgreSQL / SQLite**
- 📦 **Local Mesh Storage**

---

## 🚀 Getting Started

> ✅ **Status**: NoSlop deployment is **operational**! **Phase 0: Shared Storage** and **Deployment Stabilization** are complete. The smart installer successfully deploys across heterogeneous multi-device networks.

### Quick Start (Recommended)

Use the **NoSlop Seed** smart installer for automated deployment even across multiple devices:

```bash
# Clone the repository
git clone https://github.com/gaborkukucska/NoSlop.git
cd NoSlop

# Run the installer (dependencies auto-install on first run)
# Deploy on current device (all-in-one mode)
python3 -m seed.seed_cli --single-device

# OR deploy across multiple devices (requires ssh access to other devices on the local network)
python3 -m seed.seed_cli
```

The installer will:
- 🔍 **Detect Hardware**: Analyzes CPU, RAM, GPU, and Disk to assign optimal roles.
- 🌐 **Discover Services**: Scans your network for existing Ollama, ComfyUI, or PostgreSQL instances to reuse.
- 📦 **Install Services**: Automatically installs and configures:
    - **PostgreSQL** (Database)
    - **Ollama** (LLM Inference)
    - **ComfyUI** (Generative AI with GPU support)
    - **FFmpeg/OpenCV** (Media Processing)
    - **NoSlop Backend** (FastAPI)
    - **NoSlop Frontend** (Next.js)
- ⚙️ **Configure**: Generates `.env` files and systemd services for auto-start.

See [`seed/README.md`](seed/README.md) for detailed usage instructions.

### Managing Your Deployment

After deployment, you can manage your NoSlop services using these commands:

```bash
# Check status of all services
python3 -m seed.seed_cli --status

# Stop all services
python3 -m seed.seed_cli --stop

# Start all services
python3 -m seed.seed_cli --start

# Restart all services
python3 -m seed.seed_cli --restart

# Uninstall NoSlop completely (requires confirmation)
python3 -m seed.seed_cli --uninstall

# Manage a specific deployment (by ID)
python3 -m seed.seed_cli --status --deployment-id 20251203_121141
```

### System Requirements

**Minimum (Basic)**:
- 2+ CPU cores
- 4 GB RAM
- 100 GB disk space
- Linux, macOS, or Windows (WSL2)

**Recommended (Good Performance)**:
- 4+ CPU cores
- 16 GB RAM
- 8 GB VRAM (NVIDIA/AMD GPU)
- 500 GB disk space

**Optimal (Best Experience)**:
- 8+ CPU cores
- 32 GB RAM
- 12+ GB VRAM (NVIDIA RTX series)
- 1 TB SSD

---

## 📝 Logging & Troubleshooting

NoSlop uses a comprehensive logging system to help you debug and monitor your deployment:

### Log File Locations

All logs are saved to the `logs/` folder with dated filenames for easy tracking:

```
logs/
├── seed_installer_20251203_191118.log          # Main installer logs
├── postgresql_installer_20251203_191118.log    # PostgreSQL installation
├── ollama_installer_20251203_191118.log        # Ollama installation
├── comfyui_installer_20251203_191118.log       # ComfyUI installation
├── ffmpeg_installer_20251203_191118.log        # FFmpeg installation
├── backend_installer_20251203_191118.log       # Backend installation
├── frontend_installer_20251203_191118.log      # Frontend installation
├── service_manager_20251203_191118.log         # Service management operations
└── backend_20251203_191118.log                 # Backend API runtime logs
```

### Log Levels

- **File logs**: Always capture DEBUG level for comprehensive troubleshooting
- **Console logs**: Respect user-specified log level (INFO by default)

### Viewing Logs

```bash
# View latest installer log
tail -f logs/seed_installer_*.log | tail -1

# View specific service installer logs
tail -f logs/postgresql_installer_*.log | tail -1

# View backend runtime logs
tail -f logs/backend_*.log | tail -1

# Search for errors across all logs
grep -r "ERROR" logs/

# Search for warnings
grep -r "WARNING" logs/
```

### Common Issues

Check the relevant log files if you encounter issues:
- **Installation failures**: Check `{service}_installer_*.log` files
- **Service startup issues**: Check `backend_*.log` or systemd logs with `journalctl -u noslop-backend`
- **Service management**: Check `service_manager_*.log`

---

## 📜 License
Open Source. Built for the people. ❤️

