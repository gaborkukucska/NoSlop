<!-- # START OF FILE README.md -->
<!-- # IT IS CRITICAL THAT ALL AIs and LLMs FOLLOW THE DEVELOPMENT INSTRUCTIONS IN THE `helperfiles/0_DEVELOPMENT_RULES.md` FILE WHEN FURTHER DEVELOPING THIS FRAMEWORK!!! -->

# NoSlop 🚫🥣
### **Self-hosted media making and sharing decentralized social network.**
*Power to the creators!* ✊✨

---

## 🎯 Vision

**NoSlop** is a revolutionary framework designed to empower humanity to create high-quality content without the "slop" of centralized control or algorithmic manipulation. It leverages **your** consumer hardware to build a private, secure, and AI-driven media production studio and social network.

**Own your data. Own your creativity. Share freely.** 🌍🔓

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
- **Ad-Free & Cost-Free**: No corporate overlords, no subscription fees, no data mining. 🚫💰
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

> 🚧 **Status**: NoSlop is in **Active Development (Phase 2)**. Core infrastructure and smart installer are complete!

### Quick Start (Recommended)

Use the **NoSlop Seed** smart installer for automated deployment:

```bash
# Clone the repository
git clone https://github.com/gaborkukucska/NoSlop.git
cd NoSlop

# Install dependencies
pip3 install -r seed/requirements.txt

# Deploy on current device (all-in-one mode)
python3 seed/seed_cli.py --single-device

# OR deploy across multiple devices (interactive wizard)
python3 seed/seed_cli.py
```

The installer will:
- 🔍 Detect your hardware capabilities
- 🌐 Scan your network for available devices (multi-device mode)
- 🎯 Assign optimal roles (Master, Compute, Storage, Client)
- ⚙️ Generate configuration files
- 📦 Prepare deployment artifacts

See [`seed/README.md`](seed/README.md) for detailed usage instructions.

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

### Manual Installation (Advanced)

If you prefer manual setup:

#### Prerequisites
- Python 3.11+
- Node.js 18+
- **Ollama** running locally

#### Backend Setup
```bash
cd backend
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
python main.py
```

#### Frontend Setup
```bash
cd frontend
npm install
npm run dev
```

---

## 📜 License
Open Source. Built for the people. ❤️
