// FILE: app/src/main/java/com/noslop/app/net/SshDeployer.kt
package com.noslop.app.net

import com.jcraft.jsch.JSch
import com.noslop.app.crypto.CryptoService
import com.noslop.app.debug.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object SshDeployer {
    private const val TAG = "SSH_DEPLOYER"

    suspend fun deployHaiNetHub(
        ip: String,
        user: String,
        pass: String,
        sharedFolder: String,
        identity: CryptoService.IdentityKeys? = null,
        onLog: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            onLog("Connecting to $user@$ip:22...\n")
            Logger.info(TAG, "Connecting to $user@$ip:22")

            val jsch = JSch()
            val session = jsch.getSession(user, ip, 22)
            session.setPassword(pass)
            session.setConfig("StrictHostKeyChecking", "no")
            session.serverAliveInterval = 10000 // Keep connection alive during long idle periods
            session.connect(15000)

            onLog("SSH connected. Preparing deployment...\n")
            Logger.info(TAG, "SSH session established to $ip")

            // Build the hub_config.json payload using JSONObject for safe escaping
            val configJson = JSONObject().apply {
                put("shared_folder", sharedFolder)
                if (identity != null) {
                    put("identity", JSONObject().apply {
                        put("public_key", identity.publicKeyB64)
                        put("private_key", identity.privateKeyB64)
                        put("enc_public_key", identity.encPublicKeyB64)
                        put("enc_private_key", identity.encPrivateKeyB64)
                        put("onion_address", identity.onionAddress)
                        put("display_name", identity.displayName)
                    })
                }
            }

            // Base64-encode the JSON to avoid all shell escaping issues
            val configB64 = android.util.Base64.encodeToString(
                configJson.toString().toByteArray(), 
                android.util.Base64.NO_WRAP
            )
            
            // Base64-encode the password to safely pass to sudo without escaping issues
            val passB64 = android.util.Base64.encodeToString(
                pass.toByteArray(), 
                android.util.Base64.NO_WRAP
            )

            val script = """
                #!/bin/bash
                set -e
                exec 2>&1
                echo '=== HAI-Net Hub Deployment ==='
                echo "Target: ${'$'}(hostname) (${'$'}(uname -m))"
                echo ""
                
                # Setup sudo helper
                SUDO_PASS=${'$'}(echo "$passB64" | base64 -d)
                
                run_sudo() {
                    if [ "${'$'}(id -u)" -eq 0 ]; then
                        "${'$'}@"
                    else
                        echo "${'$'}SUDO_PASS" | sudo -S "${'$'}@"
                    fi
                }

                # Step 0: Ensure essential build tools are present
                echo '[STEP 0/5] Checking build prerequisites...'
                NEED_INSTALL=""
                command -v cc &> /dev/null || NEED_INSTALL="yes"
                command -v git &> /dev/null || NEED_INSTALL="yes"
                command -v curl &> /dev/null || NEED_INSTALL="yes"
                command -v cmake &> /dev/null || NEED_INSTALL="yes"
                command -v npm &> /dev/null || NEED_INSTALL="yes"
                
                if [ -n "${'$'}NEED_INSTALL" ]; then
                    echo '  Installing build-essential, git, curl, pkg-config, libssl-dev, cmake, nodejs, npm...'
                    
                    if command -v apt-get &> /dev/null; then
                        run_sudo apt-get update -qq
                        run_sudo env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq build-essential git curl pkg-config libssl-dev protobuf-compiler cmake nodejs npm
                    elif command -v dnf &> /dev/null; then
                        run_sudo dnf install -y gcc gcc-c++ make git curl openssl-devel pkgconfig protobuf-compiler cmake nodejs npm
                    elif command -v pacman &> /dev/null; then
                        run_sudo pacman -Sy --noconfirm base-devel git curl openssl pkgconf protobuf cmake nodejs npm
                    else
                        echo '[ERROR] Unsupported package manager. Please install dependencies manually.'
                        exit 1
                    fi
                    echo '  Build tools installed.'
                else
                    echo '  Build tools already present.'
                fi
                echo ""
                
                # Step 1: Check for / install Rust
                if ! command -v cargo &> /dev/null; then
                    echo '[STEP 1/5] Rust/Cargo not found. Installing Rust...'
                    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
                    export PATH="${'$'}HOME/.cargo/bin:${'$'}PATH"
                    echo '  Rust installed successfully.'
                else
                    echo '[STEP 1/5] Rust/Cargo is already installed.'
                    export PATH="${'$'}HOME/.cargo/bin:${'$'}PATH"
                fi
                echo "  cargo: ${'$'}(cargo --version)"
                echo ""
                
                # Step 2: Clone or update the repository
                echo '[STEP 2/5] Cloning/updating HAI-Net repository...'
                if [ -d "hai" ]; then
                    cd hai && git pull && cd ..
                else
                    git clone https://github.com/gaborkukucska/hai.git
                fi
                echo ""
                
                # Step 3: Write config from base64 payload
                echo '[STEP 3/5] Writing hub configuration...'
                echo "$configB64" | base64 -d > hai/hub_config.json
                echo "  Config written (${'$'}(wc -c < hai/hub_config.json) bytes)"
                
                # Pre-create /etc/hainet so the installer doesn't fail with permissions/os error 2
                run_sudo mkdir -p /etc/hainet
                run_sudo chown -R "${'$'}(id -un):${'$'}(id -gn)" /etc/hainet
                echo ""
                
                # Step 4: Run the seed installer
                echo '[STEP 4/5] Running HAI-Net seed installer (this may take several minutes)...'
                cd hai
                
                # Start a background keep-alive loop to prevent NAT idle timeouts during long silent builds
                (while true; do echo -n "."; sleep 30; done) &
                KEEPALIVE_PID=${'$'}!
                
                # Run the installer
                set +e # Disable exit on error temporarily so we can reliably kill the keepalive
                export PATH="${'$'}HOME/.cargo/bin:${'$'}PATH"
                cargo run --package hainet-seed --bin hainet-seed -- install --config hub_config.json
                CARGO_EXIT=${'$'}?
                set -e
                
                # Kill the keepalive process
                kill ${'$'}KEEPALIVE_PID 2>/dev/null || true
                echo ""
                
                # Cleanup
                rm -f hub_config.json
                
                if [ ${'$'}CARGO_EXIT -ne 0 ]; then
                    echo "Installer failed with exit code ${'$'}CARGO_EXIT"
                    exit ${'$'}CARGO_EXIT
                fi
                echo ""
                
                # Step 5: Build and Start Portal Web UI
                echo '[STEP 5/5] Building and starting HAI-Net Portal Web UI...'
                if [ -d "hainet-portal" ]; then
                    cd hainet-portal
                    echo '  Installing NPM dependencies...'
                    npm install
                    echo '  Building React frontend...'
                    npm run build
                    cd ..
                    
                    echo '  Building Portal backend...'
                    export PATH="${'$'}HOME/.cargo/bin:${'$'}PATH"
                    cargo build --release --package hainet-portal
                    
                    echo '  Setting up systemd service for Portal...'
                    cat << 'EOF' | run_sudo tee /etc/systemd/system/hainet-portal.service > /dev/null
[Unit]
Description=HAI-Net Portal Web UI
After=network.target hainet-core.service

[Service]
Type=simple
User=${'$'}(id -un)
WorkingDirectory=${'$'}PWD
ExecStart=${'$'}PWD/target/release/hainet-portal
Restart=always

[Install]
WantedBy=multi-user.target
EOF
                    run_sudo systemctl daemon-reload
                    run_sudo systemctl enable hainet-portal.service
                    run_sudo systemctl restart hainet-portal.service
                    echo '  Portal Web UI is now running on port 3000.'
                else
                    echo '  [WARNING] hainet-portal directory not found. Skipping Web UI setup.'
                fi
                
                echo ""
                echo '=== Deployment Complete ==='
            """.trimIndent()

            val channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
            // Pipe the script via stdin to avoid all quoting issues
            channel.setCommand("bash -s")
            channel.setErrStream(null)

            val outputStream = channel.outputStream
            val inputStream = channel.inputStream
            channel.connect(10000)

            // Write the script to stdin and close it
            outputStream.write(script.toByteArray())
            outputStream.flush()
            outputStream.close()

            val output = StringBuilder()
            val buf = ByteArray(4096)

            while (true) {
                while (inputStream.available() > 0) {
                    val len = inputStream.read(buf)
                    if (len > 0) {
                        val chunk = String(buf, 0, len)
                        output.append(chunk)
                        Logger.info(TAG, chunk.trimEnd())
                        withContext(Dispatchers.Main) { onLog(chunk) }
                    }
                }
                if (channel.isClosed) {
                    // Drain any remaining bytes
                    while (inputStream.available() > 0) {
                        val len = inputStream.read(buf)
                        if (len > 0) {
                            val chunk = String(buf, 0, len)
                            output.append(chunk)
                            Logger.info(TAG, chunk.trimEnd())
                            withContext(Dispatchers.Main) { onLog(chunk) }
                        }
                    }
                    break
                }
                kotlinx.coroutines.delay(200)
            }

            val exitStatus = channel.exitStatus
            channel.disconnect()
            session.disconnect()

            Logger.info(TAG, "Deployment finished with exit code: $exitStatus")

            if (exitStatus != 0) {
                val errorMsg = "Deployment failed (exit code $exitStatus)"
                withContext(Dispatchers.Main) { onLog("\n[ERROR] $errorMsg\n") }
                return@withContext Result.failure(Exception(errorMsg))
            }

            Result.success(output.toString())
        } catch (e: Exception) {
            Logger.error(TAG, "SSH Deployment failed: ${e.message}")
            withContext(Dispatchers.Main) { onLog("\n[ERROR] ${e.message}\n") }
            Result.failure(e)
        }
    }
}
