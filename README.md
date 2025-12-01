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

> 🚧 **Note**: NoSlop is currently in **Active Development (Phase 1)**.

### Prerequisites
- Linux / macOS / Windows (WSL2)
- Python 3.11+
- Node.js 18+
- **Ollama** running locally

### Installation
1.  **Clone the repo**:
    ```bash
    git clone https://github.com/gaborkukucska/NoSlop.git
    cd NoSlop
    ```
2.  **Setup Backend**:
    ```bash
    python3 -m venv backend/venv
    source backend/venv/bin/activate
    pip install -r backend/requirements.txt
    python backend/main.py
    ```
3.  **Setup Frontend**:
    ```bash
    cd frontend
    npm install
    npm run dev
    ```

---

## 📜 License
Open Source. Built for the people. ❤️
