## Next Steps: Phase 2 - Service Installers

NoSlop Seed core components (hardware detection, network scanning, role assignment, SSH management, deployment orchestration, CLI) are complete!

### What's Working:
- ✅ Dynamic role assignment based on device specs
- ✅ Service mapping (MASTER→Ollama/Backend/DB, COMPUTE→ComfyUI/FFmpeg, CLIENT→Frontend)
- ✅ Configuration generation (.env files for each node)
- ✅ SSH credential collection and key distribution framework

### What Needs Implementation:
**Phase 2: Automated Service Installation**

1. **Base Installer Framework** (`seed/installers/base_installer.py`)
   - Abstract base class with methods: check_installed(), install(), configure(), start(), verify()
   - OS-specific installation logic (Linux/macOS/Windows)
   - Error handling and rollback support

2. **Individual Service Installers**:
   - `ollama_installer.py` - Install Ollama on MASTER nodes, pull required models
   - `comfyui_installer.py` - Install ComfyUI on COMPUTE nodes, configure for GPU (CUDA/ROCm/Metal)
   - `ffmpeg_installer.py` - Install FFmpeg/OpenCV on COMPUTE nodes
   - `postgresql_installer.py` - Install PostgreSQL on MASTER nodes, initialize database
   - `backend_installer.py` - Deploy NoSlop backend (FastAPI) on MASTER nodes
   - `frontend_installer.py` - Deploy NoSlop frontend (Next.js) on CLIENT nodes

3. **Remote Installation Execution**:
   - SSH-based remote command execution
   - File transfer to remote nodes
   - Service startup and verification
   - Health checks

4. **Integration**:
   - Update `deployer.py` to call service installers
   - Add progress tracking and logging
   - Implement rollback on failure

See `seed/README.md` for current usage. Service installation will be added in Phase 2.