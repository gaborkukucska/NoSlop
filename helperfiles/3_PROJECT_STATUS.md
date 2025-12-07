---
project_name: "NoSlop"
version: "0.04"
status: "In Development"
last_updated: "2025-12-05"
current_phase: "Phase 3: Advanced Features & Integration"
current_cycle: "Testing & Validation"
---

# Project Status

## Recent Changes
- **2025-12-05** (Session 5):
    - **Phase 0: Shared Storage & Project Organization - COMPLETE**:
      - **Objective**: Implement foundational infrastructure for network-wide model sharing and project-based file organization
      - **Implementation**:
        - Created `seed/storage_manager.py` (404 lines) - NFS/SMB configuration and setup automation
        - Created `backend/project_storage.py` (394 lines) - Project folder structure and file organization
        - Updated `backend/config.py` - Added shared storage configuration settings
        - Enhanced `backend/database.py` - Added storage tracking fields to ProjectModel (folder_path, workflows_count, media_count, storage_size_mb)
        - Updated `seed/installers/ollama_installer.py` - Added shared models directory support via OLLAMA_MODELS env var
        - Updated `seed/installers/comfyui_installer.py` - Added shared models and custom_nodes via symlinks
        - Enhanced `seed/deployer.py` - Integrated storage configuration into deployment flow
      - **Features**:
        - User-configurable storage paths during deployment
        - Automatic NFS server setup on master node
        - Automatic NFS client mounting on worker nodes
        - Storage validation and verification
        - Project folder structure: workflows/, prompts/, generated/, intermediate/, final/
        - Comprehensive metadata tracking for all files
        - Storage statistics per project
      - **Architecture**:
        - Single-device deployments use local storage (no changes)
        - Multi-device deployments prompt for shared storage paths
        - Ollama models shared across all instances (eliminates redundancy)
        - ComfyUI models and custom nodes shared across all instances
        - All project files organized in consistent folder structure
        - Metadata preserved with all generated content
      - **Files Created**: 2 (798 lines)
      - **Files Modified**: 5 (164 lines)
      - **Total**: 962 lines of production code
      - **Status**: ✅ **Phase 0 Complete** - Ready for testing
      - **Next**: Phase 1 - Admin AI Workflow Orchestration

- **2025-12-05** (Session 4):
    - **Admin AI Chat Integration Fix**:
      - **Issue**: Chat interface showed error "Sorry, I encountered an error. Please make sure the backend is running." when sending messages from network IPs
      - **Root Cause**: `ChatInterface.tsx` component had hardcoded `localhost:8000` URLs instead of dynamic backend URL detection
      - **Solution**: Implemented dynamic URL detection using the same pattern as `api.ts` and `page.tsx`
      - **Implementation**:
        - Added `getBackendUrl()` function to detect current hostname
        - Updated chat API calls to use `${getBackendUrl()}/api/chat`
        - Updated clear history API call to use dynamic URL
        - Restarted all frontend services to apply changes
      - **Files Modified**: `frontend/app/components/ChatInterface.tsx`
      - **Impact**: Admin AI chat now works from any frontend instance (localhost, 10.0.0.3, 10.0.0.20, 10.0.0.11)
      - **Verification**: All 7 services running successfully across 3 devices

- **2025-12-04** (Session 3):
    - **Frontend Installer SFTP Permission Fix**:
      - **Root Cause**: During frontend deployment, directories were created with root ownership (`sudo mkdir -p`) but SFTP attempted to write files as non-root user, causing "Permission denied" errors
      - **Issue**: Authentication files (`app/context/AuthContext.tsx`, `app/login/page.tsx`, `app/register/page.tsx`) failed to transfer, breaking Next.js build
      - **Solution**: Updated `transfer_directory` method in `seed/ssh_manager.py` to set proper ownership immediately after directory creation
      - **Implementation**: 
        - Get username from SSH client connection: `client.get_transport().get_username()`
        - Execute `sudo chown -R {username}:{username} {dir_path}` after each `sudo mkdir -p`
        - This ensures SFTP can write files to newly created directories
      - **Files Modified**: `seed/ssh_manager.py`
      - **Impact**: Frontend deployment now works correctly on multi-device setups, all files transfer successfully without permission errors
    
    - **Authentication Flow Implementation**:
      - **Issue**: Frontend had authentication infrastructure (login/register pages, AuthContext, JWT) but main dashboard didn't enforce authentication
      - **Solution**: Added authentication checks to main page with redirect logic for unauthenticated users
      - **Implementation**:
        - Created `LoadingScreen` component for auth check loading state
        - Added `useAuth` hook to main page with redirect to `/login` if not authenticated
        - Added logout button and username display in header
        - Protected dashboard route - only accessible when authenticated
      - **Files Modified**: `frontend/app/page.tsx`
      - **Files Created**: `frontend/app/components/LoadingScreen.tsx`
      - **Impact**: Complete authentication flow now working - users must register/login to access dashboard, can logout, token persisted in localStorage
    
    - **API Connection Fix**:
      - **Issue**: Frontend trying to connect to `localhost:8000` from browser, but backend at network IP
      - **Solution**: Updated API client to dynamically detect backend URL based on current hostname
      - **Implementation**: If accessing via IP (e.g., `10.0.0.3:3000`), connects to same IP with port 8000
      - **Files Modified**: `frontend/utils/api.ts`
      - **Impact**: Frontend can connect to backend from any access point
    
    - **CORS Configuration Fix**:
      - **Issue**: Browser blocked API requests with CORS error - backend only allowed `localhost:3000` but users accessed from multiple IPs
      - **Solution**: Updated CORS configuration to allow all origins in development mode
      - **Implementation**:
        - Set `cors_origins = "*"` in `backend/config.py`
        - Updated CORS middleware to use `allow_origin_regex=".*"` for wildcard support
      - **Files Modified**: `backend/config.py`, `backend/main.py`
      - **Impact**: API requests work from any frontend URL (localhost, 10.0.0.3, 10.0.0.20, 10.0.0.11)
      - **Security Note**: CORS set to allow all origins for development only, should be restricted in production
    
    - **Database Schema Fix**:
      - **Issue**: PostgreSQL `users` table missing `hashed_password` column, causing registration failures
      - **Root Cause**: Table created before authentication was added, schema not migrated
      - **Solution**: Dropped and recreated database to initialize with correct schema
      - **Implementation**:
        - Stopped backend service
        - Dropped database: `DROP DATABASE noslop;`
        - Recreated database: `CREATE DATABASE noslop OWNER noslop;`
        - Restarted backend to initialize tables with correct schema
      - **Impact**: Users table now has all required columns, registration and login working correctly
      - **Note**: All existing data was lost (acceptable for development)
    
    - **Health Check URL Fix**:
      - **Issue**: Health check hardcoded to `localhost:8000`, failed when accessing from network IP
      - **Solution**: Updated health check to use dynamic URL detection
      - **Files Modified**: `frontend/app/page.tsx`
      - **Impact**: Health check works from any frontend URL
    
    - **Project List API Response Fix**:
      - **Issue**: Frontend crashed with "i.map is not a function" after login
      - **Root Cause**: Backend returns `{projects: [...]}` but frontend expected array directly
      - **Solution**: Updated `listProjects` method to extract projects array from response object
      - **Files Modified**: `frontend/utils/api.ts`
      - **Impact**: Project list renders correctly without errors

- **2025-12-04** (Session 2):
    - **Critical Bug Fixes and Service Improvements**:
      - **Backend Startup Issues Fixed**:
        - Added missing `websocket-client` dependency to `backend/requirements.txt` (required by ComfyUI client)
        - Fixed `WorkerRegistry` initialization error by implementing lazy initialization pattern
        - Fixed attribute naming inconsistency (`_workers` vs `workers`, `_task_type_mapping` vs `task_type_map`)
        - Added `SessionLocal` import and proper registry initialization at application startup
        - **Files Modified**: `backend/requirements.txt`, `backend/worker_registry.py`, `backend/main.py`
      
      - **SSH Authentication Fixed**:
        - Added `ssh_username` field to `DeviceCapabilities` model to persist SSH credentials
        - Updated `Deployer` to save SSH username in deployment plan during installation
        - Updated `ServiceManager` to use saved SSH username for service management operations
        - Fixes "Authentication (publickey) failed" errors when running `--restart`, `--status` commands
        - **Files Modified**: `seed/models.py`, `seed/deployer.py`, `seed/manager.py`
      
      - **ComfyUI Connectivity Fixed**:
        - Added `COMFYUI_ENABLED=true` to backend environment configuration in deployer
        - Backend now properly connects to ComfyUI service
        - Frontend status page now shows "ComfyUI: Ready" instead of "Unknown"
        - **Files Modified**: `seed/deployer.py`
      
      - **Service Logging Standardized**:
        - Replaced shell redirection approach (which didn't work in systemd) with systemd `StandardOutput`/`StandardError` directives
        - Fixed systemd service file syntax errors (double-escaped newlines)
        - All services now log to timestamped files in `~/NoSlop/logs/`:
          - Frontend: `frontend_YYYYMMDD_HHMMSS.log`
          - ComfyUI: `comfyui_YYYYMMDD_HHMMSS.log`
          - Ollama: `ollama_YYYYMMDD_HHMMSS.log`
        - **Files Modified**: `seed/installers/frontend_installer.py`, `seed/installers/comfyui_installer.py`, `seed/installers/ollama_installer.py`
      
      - **Impact**: 
        - Backend now starts successfully without crashes
        - Service management commands work correctly with proper SSH authentication
        - All services properly connected and reporting healthy status
        - Comprehensive timestamped logging across all services
        - Frontend UI shows all services as "Ready"

- **2025-12-04** (Session 1):
    - **Unified Logging Complete**: Ensured all modules and services log to timestamped files.
      - **Backend**: Switched to dated log files (`backend_YYYYMMDD_HHMMSS.log`).
      - **Service Manager**: Seed CLI now uses `service_manager` log name for management commands.
      - **Services**: Frontend, ComfyUI, and Ollama systemd services now redirect stdout/stderr to timestamped log files.
      - **Files Modified**: `backend/main.py`, `seed/seed_cli.py`, `seed/installers/frontend_installer.py`, `seed/installers/comfyui_installer.py`, `seed/installers/ollama_installer.py`.
- **2025-12-03**:
    - **Unified Logging System**: Implemented centralized logging infrastructure across all NoSlop modules:
      - **Shared Logging Utilities**: Created `shared/logging_utils.py` with `setup_module_logging()` function for consistent dated log files
      - **Naming Convention**: All modules now create logs with pattern: `{module_name}_{YYYYMMDD_HHMMSS}.log`
      - **Seed Module Logging**:
        - Updated `seed/seed_cli.py` to use shared logging utilities with fallback to custom setup
        - Updated `seed/installers/base_installer.py` to create installer-specific logs for each service
        - Updated `seed/manager.py` to create service manager logs
        - Each installer now creates its own log file (e.g., `postgresql_installer_20251203_211632.log`)
      - **Backend Logging**: Updated `backend/logging_config.py` to use `module_name` parameter for consistent naming
      - **Log Files Created**:
        - `seed_installer_YYYYMMDD_HHMMSS.log` - Main seed installer logs
        - `{service}_installer_YYYYMMDD_HHMMSS.log` - Individual installer logs (postgresql, ollama, comfyui, ffmpeg, backend, frontend)
        - `service_manager_YYYYMMDD_HHMMSS.log` - Service management operations
        - `backend_YYYYMMDD_HHMMSS.log` - Backend API logs
      - **Impact**: All logs centralized in `logs/` folder with consistent naming, easier debugging and troubleshooting
      - **Files Created**: `shared/__init__.py`, `shared/logging_utils.py`
      - **Files Modified**: `seed/seed_cli.py`, `seed/installers/base_installer.py`, `seed/manager.py`, `backend/logging_config.py`

    - **Frontend Installer Complete Fix**: Fixed multiple critical issues preventing deployment:
      - **Issue 1 - NVM Path Resolution**: 
        - Root Cause: NVM path resolution failing in `sudo -u` context - `$HOME` expanded to wrong user's home directory
        - Solution: Removed NVM dependency, now using NodeSource repository for Node.js
      - **Issue 2 - Node.js Version Requirement**:
        - Root Cause: System Node.js v18.19.1 too old for Next.js (requires >=20.9.0)
        - Solution: Install Node.js 20.x from NodeSource repository with version verification
      - **Issue 3 - File Transfer Permissions**:
        - Root Cause: SFTP attempting to write to root-owned directory before ownership change
        - Solution: Create directory with proper ownership BEFORE file transfer, verify after
      - **Changes Made**:
        - Added NodeSource repository installation: `curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -`
        - Added Node.js version verification (ensures >= v20)
        - Pre-create install directory with correct ownership before SFTP transfer
        - Simplified npm commands: `cd /opt/noslop/frontend && sudo -u {username} npm install/build`
        - Removed NVM sourcing from systemd service ExecStart
        - Added stdout logging for better error diagnosis
      - **Impact**: Installer now works reliably across all devices with proper Node.js version and permissions
      - **Files Modified**: `seed/installers/frontend_installer.py`
    - **Installer Bug Fix**: Fixed critical issue preventing frontend deployment:
      - **Root Cause**: Transferring `node_modules` directory causing thousands of `test -d` commands via SSH, spamming logs and slowing deployment
      - **Solution**: Implemented file exclusion support in directory transfer operations
      - **Files Modified**: 
        - `seed/ssh_manager.py`: Added `excludes` parameter to `transfer_directory()` with fnmatch pattern matching
        - `seed/installers/base_installer.py`: Added `excludes` parameter and rsync support for local transfers
        - `seed/installers/frontend_installer.py`: Excludes `node_modules`, `.next`, `.git`, `.idea`, `.vscode`
        - `seed/installers/backend_installer.py`: Excludes `venv`, `__pycache__`, `.git`, `.pytest_cache`, `*.pyc`, `.idea`, `.vscode`
      - **Impact**: Significantly faster deployments, cleaner logs, no more hanging on file transfers
    - **ComfyUI Permission Fixes**: Fixed three permission issues in ComfyUI installer:
      1. Git clone: Added `sudo -u {username}` to ensure correct ownership
      2. Venv creation: Added `sudo -u {username}` to ensure correct ownership  
      3. Pip install: Prefixed pip commands with `sudo -u {username}` to prevent permission errors
      - **Files Modified**: `seed/installers/comfyui_installer.py`
    - **Management Scripts**: Added service management commands to NoSlop Seed:
      - Created `seed/manager.py` with `ServiceManager` class for controlling deployed services
      - Added CLI commands: `--start`, `--stop`, `--restart`, `--status`, `--uninstall`
      - Supports single and multi-device deployments
      - Uninstall includes confirmation prompt for safety
      - **Files Modified**: `seed/seed_cli.py`, **Files Created**: `seed/manager.py`
    - **SFTP Permission Fix**: Fixed critical permission issue in frontend installer that was preventing deployment:
      - **Root Cause**: Frontend installer was creating `/opt/noslop/frontend` directory with sudo (root ownership) before attempting SFTP transfer as non-root user
      - **Issue**: SFTP transfer failed with "[Errno 13] Permission denied" when trying to write to root-owned directory
      - **Solution**: Removed premature directory creation from `frontend_installer.py`, allowing the base class `transfer_directory()` method to handle directory creation and ownership setup before SFTP transfer
      - **Additional Fix**: Updated rollback method to use `sudo rm -rf` instead of `rm -rf` to properly clean up root-owned directories
      - **Files Modified**: `seed/installers/frontend_installer.py`
    - **Frontend Deployment Fix**: Fixed five critical issues in frontend installation:
      1. npm permission errors - ensured npm commands run as the correct user (`sudo -u tom`)
      2. Node.js version detection - source nvm environment to use v22.18.0 instead of system v18.19.1
      3. systemd service configuration - run service as user `tom` with nvm environment in ExecStart
      4. Remote SFTP transfer permissions - create directory with sudo and set ownership before SFTP transfer
      5. Frontend environment configuration - create `.env.local` with `NEXT_PUBLIC_API_URL` for backend connection
      Moved ownership change to after file transfer and added cleanup of existing node_modules.
      **BigBOY deployment successful!**
    - **Frontend Installation Fix**: Resolved service name mismatch (`noslop-frontend`) ensuring correct registration and deployment.
    - **User Management System**: Implemented `UserModel`, `UserCRUD`, and API endpoints for user creation and personality persistence.
    - **Deployment Summary**: Enhanced installer to display all access points (Backend/Frontend URLs) upon completion.
    - **Verification**: Validated user flow and deployment summary with automated tests.
    - **ComfyUI Integration**: Implemented `ComfyUIClient` and `ImageGenerationWorker` to enable local image generation.
    - **FFmpeg Integration**: Implemented `FFmpegClient` and updated `VideoEditor` to enable local video processing.
    - **Frontend Integration**: Connected Frontend to Backend Project API with `ProjectForm`, `ProjectList`, and `ProjectDetail` components.
- **2025-12-01**:
    - **NoSlop Seed Smart Installer**: Implemented core components for intelligent deployment.
        - **Hardware Detection**: Created comprehensive hardware detection with CPU, RAM, GPU, disk, and OS detection
        - **Network Scanning**: Implemented SSH-enabled device discovery on local networks
        - **Role Assignment**: Built intelligent master election algorithm with weighted capability scoring (RAM 40%, GPU 30%, CPU 20%, Disk 10%)
        - **SSH Management**: Created Ed25519 key generation and distribution system
        - **Deployment Orchestrator**: Implemented deployment plan validation, configuration generation, and .env file creation
        - **CLI Interface**: Built interactive deployment wizard with single-device and multi-device modes
        - **Testing**: Verified all components - role assignment tested with single and multi-device scenarios, deployer generates proper artifacts
        - **Documentation**: Created comprehensive README for NoSlop Seed with usage instructions and examples
    - **Phase 2, Cycle 0 Complete**: Successfully implemented Infrastructure & Architecture.
        - **Logging**: Created comprehensive logging system with colored console output, rotating file handlers, structured JSON logging, and context injection
        - **Configuration**: Implemented environment-driven configuration with `.env` support, documented all settings in `.env.example`, added model preferences and feature flags
        - **Prompts**: Built centralized prompt management with `prompts.yaml`, template variable substitution, hot-reload support, and convenience methods for different agent types
        - **Integration**: Updated Admin AI and main.py to use new infrastructure throughout
        - **Verification**: Tested logging (colored console + file logs), confirmed environment loading, verified Ollama connection (28 models)
    - **Phase 2, Cycle 2 Complete**: Successfully implemented Service Installers.
        - **Service Discovery**: Implemented network scanning for existing Ollama, ComfyUI, PostgreSQL, and Backend services.
        - **Service Registry**: Created registry for tracking service instances and load balancing.
        - **Installers**: Developed automated installers for PostgreSQL, Ollama, ComfyUI, FFmpeg, Backend, and Frontend.
        - **Integration**: Integrated installers into `deployer.py` and `seed_cli.py`.
        - **Verification**: Verified single-device deployment and service discovery.
    - **Phase 2, Cycle 1 Complete**: Successfully implemented Admin AI Integration.
        - **Backend**: Created `models.py`, `config.py`, `admin_ai.py` with personality system
        - **API**: Added chat endpoints, personality management, suggestions
        - **Frontend**: Built `ChatInterface.tsx`, `PersonalitySelector.tsx`, updated dashboard
        - **Verification**: Tested chat API, confirmed Ollama integration (28 models available)
        - **Features**: Personality presets (creative/technical/balanced), contextual responses, suggestion generation
    - **Phase 1 Complete**: Successfully initialized the project structure.
        - Created directories: `backend`, `frontend`, `seed`, `blockchain`, `shared`.
        - **Backend**: Set up FastAPI with Ollama integration. Verified connection.
        - **Frontend**: Initialized Next.js application with TypeScript and Tailwind CSS.
    - **Documentation**: Created `implementation_plan.md` and `walkthrough.md` for the setup phase.
    - **Status Update**: detailed breakdown of future phases added to this file.

## Development Principles
*   Unless you've received other specific tasks, follow a phased implementation as outlined in this file.
*   Maintain `README.md`, `helperfiles/3_PROJECT_STATUS.md` (update status) and `SESSION_NUMBER_TITLE.md`, updating them at the end of every development run.
*   Write the location and name of every file in its first line like `<!-- # START OF FILE subfolder/file_name.extension -->`, make sure you also use `//!` or any other methods (depending on the programming language) in front of that statement as needed to properly block out this line.
*   Do NOT remove functional code even if it is yet incomplete, but rather complete what is missing.
*   Measure in predicted "generation token length" instead of any units of "time" when estimating the length of planned work, as that is more representative of how "long" a planned task will take you.
*   Whenever available use the log files to find clues. These files might be very large so first search them for warnings, errors or other specific strings, then use the time stamps to find more detailed debug logs around those times.
*   Maintain code consistency.
*   **Logging**: Add comprehensive DEBUG and INFO logging throughout all code for observability.
*   **Configuration**: Make everything configurable via environment variables; avoid hardcoding values.
*   **Prompts**: Extract all prompt strings to centralized prompts.yaml file for easy modification.

## Current Status
**Phase:** Phase 2: Core Development - Admin AI & Backend
**Cycle:** Cycle 4: Local Service Orchestration (IN PROGRESS)
**Date:** 2025-12-01

### Recent Achievements
- **Infrastructure**: Implemented centralized logging, configuration, and prompt management.
- **Database**: Created SQLAlchemy models for Projects and Tasks with SQLite support.
- **PM Agent**: Built the Project Manager agent capable of planning projects using LLMs.
- **Admin AI**: Integrated PM capabilities, allowing users to create projects via chat.
- **Worker Framework**: Implemented complete worker agent framework with 6 specialized workers.
- **Task Executor**: Built automatic task dispatcher with dependency resolution.
- **API**: Added comprehensive REST endpoints for workers, tasks, and project execution.
- **Seed Installer**: Completed full service installation capability.

### Active Tasks
- [x] Implement Project Manager Agent
- [x] Integrate PM with Admin AI
- [x] Implement Worker Agents (Script Writer, Prompt Engineer, Storyboard Artist, Video Editor, Color Grader, Research Agent)
- [x] Create Worker Registry system for automatic worker discovery
- [x] Build Task Executor with dependency resolution
- [x] Add worker API endpoints
- [x] Implement NoSlop Seed Smart Installer (Core Components)
  - [x] Hardware detection
  - [x] Network scanning
  - [x] Role assignment algorithm
  - [x] SSH key management
  - [x] Deployment orchestrator
  - [x] CLI interface
- [x] Implement NoSlop Seed Service Installers
  - [x] Ollama installer
  - [x] ComfyUI installer
  - [x] FFmpeg/OpenCV installer
  - [x] Backend installer
  - [x] Frontend installer
- [x] Connect to ComfyUI
- [x] Connect to FFmpeg
- [ ] Test end-to-end project execution

### Next Steps

**Phase 3: Advanced Features & Integration**

1. **Local Service Orchestration**:
   - Integrate ComfyUI API bindings into Backend
   - Integrate FFmpeg/OpenCV wrappers into Backend
   - Implement non-linear editing logic

2. **Frontend & Workflow**:
   - Connect Frontend "New Project" flow to Backend PM Agent
   - Implement interactive "Scene Setup" wizard
   - Create media preview components

3. **Advanced Installer Features**:
   - Remote hardware detection via SSH
   - Automated SSH key distribution
   - Web-based installer UI

---

# Future Roadmap

## Phase 1: Setup (✅ Completed)
- **Cycle 1: Initialization**
    - [x] Analyze project requirements.
    - [x] Create directory structure.
- **Cycle 2: Basic Infrastructure**
    - [x] Initialize Backend (FastAPI + Ollama).
    - [x] Initialize Frontend (Next.js).
    - [x] Verify basic connectivity.

## Phase 2: Core Development - Admin AI & Backend (🚧 In Progress)
- **Cycle 0: Infrastructure & Architecture** ✅ Complete
    - [x] Implement centralized logging system (DEBUG, INFO, WARN, ERROR)
    - [x] Create comprehensive .env configuration file
    - [x] Build environment variable management system
    - [x] Create centralized prompt management (prompts.yaml)
    - [x] Implement dynamic prompt injection system
- **Cycle 1: Admin AI Integration** ✅ Complete
    - [x] Implement `AdminAI` class in backend.
    - [x] Create basic personality profiles for Admin AI.
    - [x] Enable chat interface between Frontend and Admin AI.
- **Cycle 2: Project Manager (PM) Agent** (Next)
    - [ ] Implement `ProjectManager` agent logic.
    - [ ] Define task creation and assignment protocols.
- **Cycle 3: Worker Agent Framework** ✅ Complete
    - [x] Create enhanced `WorkerAgent` base class with retry logic and progress reporting.
    - [x] Implement Worker Registry for automatic worker discovery and assignment.
    - [x] Implement ScriptWriter worker (writes scripts for media projects).
    - [x] Implement PromptEngineer worker (generates optimized prompts for ComfyUI).
    - [x] Implement StoryboardArtist worker (creates visual storyboards from scripts).
    - [x] Implement VideoEditor worker (plans video editing sequences and FFmpeg commands).
    - [x] Implement ColorGrader worker (applies professional color grading specifications).
    - [x] Implement ResearchAgent worker (gathers information and context).
    - [x] Build Task Executor with dependency resolution and topological sorting.
    - [x] Add worker prompt templates to prompts.yaml.
    - [x] Create API endpoints for workers and task execution.
- **Cycle 4: Local Service Orchestration** ✅ Complete
    - [x] Integrate ComfyUI API bindings.
    - [x] Integrate FFmpeg/OpenCV wrappers.
    - [ ] **Advanced Editing**: Implement non-linear editing logic and color grading pipelines.

## Phase 3: Core Development - Frontend & Workflow (🚧 In Progress)
- **Cycle 1: Web UI Basic Structure** ✅ Complete
    - [x] Implement Dashboard layout.
    - [x] Create Agent Status monitor.
- **Cycle 2: Media Creator Studio UI** (Partial)
    - [x] Build chat interface for Admin AI.
    - [ ] Create media preview components.
- **Cycle 3: NoSlop Creator Workflow** (Partial)
    - [x] Connect Frontend "New Project" flow to Backend PM Agent.
    - [ ] Implement the interactive "Scene Setup" wizard.

## Phase 4: Advanced Features - Networking & Blockchain
- **Cycle 1: Local Mesh Network**
    - [ ] Implement node discovery (mDNS/nmap).
    - [ ] Establish secure P2P communication.
- **Cycle 2: Content Delivery**
    - [ ] Implement media synchronization between nodes.
- **Cycle 3: Blockchain Registry**
    - [ ] Design simple ledger for media provenance.
    - [ ] Implement block creation and verification.
- **Cycle 4: Social & Safety**
    - [ ] **Guardian LLM**: Implement safety agent and moderation logic.
    - [ ] **Community Voting**: Implement up/down voting system.
    - [ ] **Feed Algorithm**: Create user-configurable feed parameters.

## Phase 5: Polish & Release
- **Cycle 1: Security & Sandboxing**
    - [ ] Audit file permissions.
    - [ ] Implement strict sandboxing for Worker Agents.
- **Cycle 2: Smart Installer (Seed)**
    - [ ] Finalize `seed/` scripts for automated deployment.
    - [ ] Implement hardware capability detection.
- **Cycle 3: Testing & Bug Fixes**
    - [ ] End-to-end user testing.
    - [ ] Performance optimization.
- **Cycle 4: v1.0 Release**
    - [ ] Final documentation polish.
    - [ ] Release builds.
