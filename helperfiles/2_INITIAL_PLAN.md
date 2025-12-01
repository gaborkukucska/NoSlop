<!-- # START OF FILE helperfiles/2_INITIAL_PLAN.md -->
# NoSlop Initial Plan

## NoSlop Overview

NoSlop is a self-hosted, decentralized media creation and sharing social network. It empowers users to create high-quality content using local AI tools and share it within a private, secure mesh network. The system leverages consumer hardware to deploy a sophisticated media production cluster, ensuring privacy and freedom from centralized control.

## System Overview

The NoSlop ecosystem consists of four main modules:
1.  **NoSlop Seed**: A smart installer that deploys the platform on local devices.
2.  **NoSlop Media Creator**: An AI-driven suite for media production (Video, Image, Audio).
3.  **NoSlop Blockchain**: A decentralized registry for media credentials and provenance.
4.  **NoSlop Social Network**: A peer-to-peer network for sharing and interaction.

## Core Principles

-   **Decentralization**: No central servers; data lives on user devices.
-   **Privacy**: User data and created media are stored locally and shared only with authorization.
-   **AI-First**: Deep integration of LLMs (Ollama) and Generative AI (ComfyUI) for content creation.
-   **Resource Optimization**: Utilizes available consumer hardware (CPU, GPU, RAM) efficiently.
-   **Ease of Use**: "Admin AI" guides users through complex processes, making high-end media creation accessible.

---

# NoSlop Components

### Smart Installer Architecture

The NoSlop Seed installer is designed to be **intelligent and adaptive**, distributing components based on device capabilities:

**Key Features:**
-   🔍 **Network Discovery**: Automatically scans local network for SSH-enabled devices (nmap integration).
-   🧠 **Capability Assessment**: Evaluates each device's hardware (CPU, RAM, GPU, disk, OS, architecture).
-   🎯 **Master Election**: Assigns roles based on weighted capability scoring (RAM 40%, GPU 30%, CPU 20%, Disk 10%).
-   🔐 **SSH Key Management**: Generates Ed25519 keys for passwordless authentication.
-   📦 **Optimized Deployment**: Installs only necessary components per device role.

**Device Roles:**

-   **Master Node**: The orchestrator. Runs the core logic, database, and Admin AI.
    -   *Requirements*: High RAM, stable CPU.
-   **Compute Node (GPU Worker)**: Dedicated to heavy AI tasks (Image/Video Generation).
    -   *Requirements*: High VRAM GPU (NVIDIA preferred).
-   **Storage Node**: Stores raw media and the blockchain ledger.
    -   *Requirements*: Large HDD/SSD.
-   **Client Node**: The user interface access point (Web/Mobile).
    -   *Requirements*: Any device with a browser.

NOTE: When only a single device is available, it will be assigned all roles and system will be limited based on the capabilities of that single device.

### Local Services Infrastructure

NoSlop bundles and orchestrates a powerful stack of open-source tools:

**Core Services:**
-   **Ollama**: Local LLM inference for the Admin AI and creative writing assistance.
-   **ComfyUI**: Node-based stable diffusion GUI for image and video generation.
-   **FFmpeg**: Industrial-strength video and audio processing.
-   **OpenCV**: Computer vision tasks for media analysis and editing.
-   **PostgreSQL/SQLite**: Local data storage for user profiles and project metadata.

**Service Deployment Strategy:**
-   Services are containerized or managed as systemd services for reliability.
-   The **Master Node** manages the service registry and load balancing.

### AI Agent Architecture

The system is driven by a hierarchy of AI agents:

1.  **Admin AI (The Interface)**:
    -   Personalized to the user's preference.
    -   Acts as the primary interface for all interactions.
    -   Guides the user through the "NoSlop Creator Workflow".
    -   Proactively manages network content fetching.

2.  **Project Manager (PM) Agent**:
    -   Spun up for each new media project.
    -   Creates implementation plans and assigns tasks.
    -   Monitors worker agents and reports to the Admin AI.

3.  **Worker Agents**:
    -   Specialized agents for specific tasks (e.g., "Script Writer", "Prompt Engineer", "Video Editor", "Color Grader").
    -   Execute tasks using the underlying tools (ComfyUI, FFmpeg, etc.).
    -   **Advanced Editing**: Capable of ingesting existing media, performing non-linear editing, and applying professional color grading to meet industry standards.

4.  **Guardian LLM Agent**:
    -   **Safety & Moderation**: Analyzes incoming and outgoing content for safety based on user and community standards.
    -   **Network Health**: Monitors network interactions and protects against spam or malicious actors.

### Frontend & User Experience

-   **Web UI**: A responsive, modern web interface for desktop and tablet use.
    -   Dashboard for network status and recent activity.
    -   Media Creator Studio for the creative workflow.
    -   Social Feed for viewing shared content.
-   **Mobile App**: Companion app for capturing real-world media (camera/mic) and consuming content.

### Networking & Social

-   **Local Mesh Network**: Devices discover each other to form a local cluster to run NoSlop.
-   **P2P Content Delivery**: Media is streamed directly between authorized nodes orchestrated by the backend communication of the Admin AI's of the various nodes.
    -   **Ad-Free & Cost-Free**: No central servers means no ads and no fees.
    -   **User-Controlled Algorithm**: Users define the parameters for their content feed.
    -   **Community Safety**: Guardian LLM + Up/Down Voting system for decentralized moderation.
-   **Blockchain Registry**:
    -   Ensures authenticity and ownership of created media.
    -   Manages permissions and sharing rules.