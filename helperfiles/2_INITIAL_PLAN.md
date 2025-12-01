<!-- # START OF FILE helperfiles/2_INITIAL_PLAN.md -->
# NoSlop Overview

...

## System Overview

...

## Core Principles

...
---

# NoSlop Components

### Smart Installer Architecture

The NoSlop Seed installer is designed to be **intelligent and adaptive**, distributing components based on device capabilities:

**Key Features:**
- 🔍 **Network Discovery**: Automatically scans local network for SSH-enabled devices (nmap integration)
- 🧠 **Capability Assessment**: Evaluates each device's hardware (CPU, RAM, GPU, disk, OS, architecture)
- 🎯 **Master Election**: Assigns roles based on weighted capability scoring (RAM 40%, GPU 30%, CPU 20%, Disk 10%)
- 🔐 **SSH Key Management**: Generates Ed25519 keys for passwordless authentication
- 📦 **Optimized Deployment**: Installs only necessary components per device role

**Installation Workflow:**
...

**Device Roles:**

**Master Node**
...

**Slave Nodes**
...

**Mobile Devices**
...

### Local Services Infrastructure
...

**Core Services:**

**Service Deployment Strategy:**

...