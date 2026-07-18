// FILE: app/src/main/java/com/noslop/app/net/SshDeployer.kt
package com.noslop.app.net

import com.jcraft.jsch.JSch
import com.noslop.app.crypto.CryptoService
import com.noslop.app.debug.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class OverwriteStrategy {
    PROMPT,
    RESET_IDENTITY,
    FULL_WIPE,
    UPDATE_HUB
}

class ExistingDeploymentException(message: String = "Existing HAI-Net deployment found") : Exception(message)

object SshDeployer {
    private const val TAG = "SshDeployer"

    suspend fun deployHaiNetHub(
        ip: String,
        user: String,
        pass: String,
        sharedFolder: String,
        identity: CryptoService.IdentityKeys?,
        strategy: OverwriteStrategy = OverwriteStrategy.PROMPT,
        onLog: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            onLog("Connecting to $user@$ip:22...\n")
            Logger.info(TAG, "Connecting to $user@$ip:22")

            val jsch = JSch()
            val session = jsch.getSession(user, ip, 22)
            session.setPassword(pass)
            
            session.userInfo = object : com.jcraft.jsch.UserInfo {
                override fun getPassphrase() = null
                override fun getPassword() = pass
                override fun promptPassword(message: String) = true
                override fun promptPassphrase(message: String) = true
                override fun promptYesNo(message: String): Boolean {
                    Logger.info(TAG, "SSH Host Key Prompt: $message")
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { onLog("\n[SECURITY] SSH Host Key Fingerprint:\n$message\nAuto-accepting for deployment...\n") }
                    return true
                }
                override fun showMessage(message: String) {
                    Logger.info(TAG, "SSH Message: $message")
                }
            }
            session.setConfig("StrictHostKeyChecking", "ask")
            // Provide a dummy known_hosts file so JSch can save and do TOFU
            val knownHostsFile = java.io.File(System.getProperty("java.io.tmpdir"), "noslop_known_hosts")
            if (!knownHostsFile.exists()) knownHostsFile.createNewFile()
            jsch.setKnownHosts(knownHostsFile.absolutePath)

            session.serverAliveInterval = 10000 // Keep connection alive during long idle periods (like model downloads)
            session.connect(15000)

            onLog("SSH connected. Preparing deployment...\n")
            Logger.info(TAG, "SSH session established to $ip")

            // Build the hub_config.json payload using JSONObject for safe escaping
            val configJson = JSONObject().apply {
                put("shared_folder", sharedFolder)
                if (identity != null) {
                    val idJson = JSONObject().apply {
                        put("public_key", identity.publicKeyB64)
                        put("private_key", identity.privateKeyB64)
                        put("enc_public_key", identity.encPublicKeyB64)
                        put("enc_private_key", identity.encPrivateKeyB64)
                        put("onion_address", identity.onionAddress)
                        put("display_name", identity.displayName)
                    }
                    put("identity", idJson)
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
                        echo "${'$'}SUDO_PASS" | sudo -S -p "" "${'$'}@"
                    fi
                }
                
                STRATEGY="${strategy.name}"
                if [ "${'$'}STRATEGY" == "PROMPT" ]; then
                    if [ -f "/etc/systemd/system/hainet-core.service" ] || [ -d "hai" ] || [ -d "${'$'}HOME/.hainet" ]; then
                        echo "EXISTING_DEPLOYMENT_FOUND"
                        exit 99
                    fi
                fi
                
                if [ "${'$'}STRATEGY" == "FULL_WIPE" ]; then
                    echo "Wiping existing deployment..."
                    run_sudo systemctl stop hainet-core.service 2>/dev/null || true
                    run_sudo systemctl disable hainet-core.service 2>/dev/null || true
                    run_sudo rm -f /etc/systemd/system/hainet-core.service
                    rm -rf hai
                    run_sudo rm -rf ~/.hainet /var/lib/hainet /etc/hainet
                fi

                if [ "${'$'}STRATEGY" == "RESET_IDENTITY" ]; then
                    echo "Resetting identity for existing deployment..."
                    cat << EOF > reset_ident.py
import json, base64, os, sys
try:
    config_b64 = "$configB64"
    config_json = json.loads(base64.b64decode(config_b64).decode('utf-8'))
    identity = config_json.get('identity')
    if not identity:
        print("No identity found in config")
        sys.exit(0)
    ident_dir = os.path.expanduser('~/.hainet/identity')
    os.makedirs(ident_dir, exist_ok=True)
    
    with open(os.path.join(ident_dir, 'ed25519_pub.b64'), 'w') as f:
        f.write(identity['public_key'])
    with open(os.path.join(ident_dir, 'ed25519_priv.b64'), 'w') as f:
        f.write(identity['private_key'])
    with open(os.path.join(ident_dir, 'x25519_pub.b64'), 'w') as f:
        f.write(identity['enc_public_key'])
    with open(os.path.join(ident_dir, 'x25519_priv.b64'), 'w') as f:
        f.write(identity['enc_private_key'])
    with open(os.path.join(ident_dir, 'onion_address'), 'w') as f:
        f.write(identity['onion_address'])
    with open(os.path.join(ident_dir, 'display_name'), 'w') as f:
        f.write(identity['display_name'])
except Exception as e:
    print("Error resetting identity:", e)
    sys.exit(1)
EOF
                    python3 reset_ident.py
                    rm -f reset_ident.py
                    
                    # Remove the stale auth state so the Web UI auto-initializes QR login
                    # for the new identity instead of showing a passphrase form
                    rm -f ~/.hainet/auth.json
                    
                    cat << 'PYEOF' > gen_tor.py
import base64, hashlib, os
try:
    with open(os.path.expanduser("~/.hainet/identity/ed25519_priv.b64"), "r") as f:
        b64_key = f.read().strip()
    pkcs8 = base64.b64decode(b64_key)
    seed = pkcs8[-32:]
    expanded = bytearray(hashlib.sha512(seed).digest())
    expanded[0] &= 248
    expanded[31] &= 127
    expanded[31] |= 64
    header = b"== ed25519v1-secret: type0 ==" + bytes([0, 0, 0])
    with open("hs_ed25519_secret_key", "wb") as f:
        f.write(header + expanded)
except Exception as e:
    print("Error generating tor key:", e)
PYEOF
                    python3 gen_tor.py
                    run_sudo mkdir -p /var/lib/tor/hainet/
                    run_sudo mv hs_ed25519_secret_key /var/lib/tor/hainet/hs_ed25519_secret_key
                    TOR_USER=${'$'}(id -u debian-tor >/dev/null 2>&1 && echo "debian-tor" || echo "tor")
                    run_sudo chown -R ${'$'}TOR_USER:${'$'}TOR_USER /var/lib/tor/hainet/
                    run_sudo chmod 700 /var/lib/tor/hainet/
                    run_sudo chmod 600 /var/lib/tor/hainet/hs_ed25519_secret_key
                    rm -f gen_tor.py
                    
                    run_sudo systemctl restart tor || true
                    run_sudo systemctl restart hainet-core.service || true
                    
                    echo "Identity reset complete!"
                    exit 0
                fi

                if [ "${'$'}STRATEGY" == "UPDATE_HUB" ]; then
                    echo "Updating existing deployment..."
                    if [ ! -d "hai" ]; then
                        echo "No deployment found to update in ${'$'}PWD/hai!"
                        exit 1
                    fi
                    cd hai
                    
                    echo "Pulling latest changes from Git..."
                    git pull
                    export PATH="${'$'}HOME/.cargo/bin:${'$'}PATH"
                    
                    echo "Building React UI..."
                    if [ -d "hainet-portal" ]; then
                        cd hainet-portal
                        npm install
                        npm run build
                        cd ..
                    fi
                    
                    echo "Building HAI-Net Core..."
                    (while true; do echo -n "."; sleep 15; done) &
                    KEEPALIVE_PID=${'$'}!
                    
                    set +e
                    cargo build --release --package hainet-core
                    CARGO_EXIT=${'$'}?
                    set -e
                    
                    kill ${'$'}KEEPALIVE_PID 2>/dev/null || true
                    echo ""
                    
                    if [ ${'$'}CARGO_EXIT -ne 0 ]; then
                        echo "Core build failed with exit code ${'$'}CARGO_EXIT"
                        exit ${'$'}CARGO_EXIT
                    fi
                    
                    run_sudo systemctl restart hainet-core.service
                    
                    # Ensure full HiddenService block exists in torrc for existing deployments
                    if ! run_sudo grep -q "HiddenServiceDir.*hainet" /etc/tor/torrc; then
                        # Detect which directory holds the key material
                        HS_DIR="/var/lib/tor/hainet/"
                        if run_sudo test -d /var/lib/tor/hainet_hidden_service; then
                            HS_DIR="/var/lib/tor/hainet_hidden_service/"
                        fi
                        echo "" | run_sudo tee -a /etc/tor/torrc >/dev/null
                        echo "# HAI-Net Hidden Service" | run_sudo tee -a /etc/tor/torrc >/dev/null
                        echo "HiddenServiceDir ${'$'}HS_DIR" | run_sudo tee -a /etc/tor/torrc >/dev/null
                        echo "HiddenServicePort 8080 127.0.0.1:8080" | run_sudo tee -a /etc/tor/torrc >/dev/null
                        echo "HiddenServicePort 9999 127.0.0.1:9999" | run_sudo tee -a /etc/tor/torrc >/dev/null
                        run_sudo systemctl restart tor || true
                        echo "Injected full HiddenService block into Tor configuration."
                    elif ! run_sudo grep -q "HiddenServicePort 9999" /etc/tor/torrc; then
                        # Dir exists but port 9999 is missing (legacy deploy)
                        run_sudo sed -i '/HiddenServicePort 8080/a HiddenServicePort 9999 127.0.0.1:9999' /etc/tor/torrc
                        run_sudo systemctl restart tor || true
                        echo "Injected missing port 9999 to existing Tor configuration."
                    fi

                    echo "Hub Update Complete!"
                    exit 0
                fi

                # Step 0: Ensure essential build tools are present
                echo '[STEP 0/5] Checking build prerequisites...'
                NEED_INSTALL=""
                command -v cc &> /dev/null || NEED_INSTALL="yes"
                command -v git &> /dev/null || NEED_INSTALL="yes"
                command -v curl &> /dev/null || NEED_INSTALL="yes"
                command -v cmake &> /dev/null || NEED_INSTALL="yes"
                command -v npm &> /dev/null || NEED_INSTALL="yes"
                
                if [ -n "${'$'}NEED_INSTALL" ]; then
                    echo '  Installing build-essential, git, curl, pkg-config, libssl-dev, cmake, nodejs, npm, python3-venv...'
                    
                    if command -v apt-get &> /dev/null; then
                        run_sudo apt-get update -qq
                        run_sudo env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq build-essential git curl pkg-config libssl-dev protobuf-compiler cmake nodejs npm python3-venv
                    elif command -v dnf &> /dev/null; then
                        run_sudo dnf install -y gcc gcc-c++ make git curl openssl-devel pkgconfig protobuf-compiler cmake nodejs npm python3
                    elif command -v pacman &> /dev/null; then
                        run_sudo pacman -Sy --noconfirm base-devel git curl openssl pkgconf protobuf cmake nodejs npm python
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
                
                # Pre-create /etc/hainet and /var/lib/hainet so the installer doesn't fail with permissions errors
                run_sudo mkdir -p /etc/hainet /var/lib/hainet
                run_sudo chown -R "${'$'}(id -un):${'$'}(id -gn)" /etc/hainet /var/lib/hainet
                run_sudo chmod 777 /var/lib/hainet
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
                
                # Step 5: Build and Start Core Daemon (which includes Web UI)
                echo '[STEP 5/5] Building React UI and HAI-Net Core...'
                if [ -d "hainet-portal" ]; then
                    cd hainet-portal
                    echo '  Installing NPM dependencies...'
                    npm install
                    echo '  Building React frontend...'
                    npm run build
                    cd ..
                fi
                
                echo '  Building Core daemon...'
                export PATH="${'$'}HOME/.cargo/bin:${'$'}PATH"
                
                # Start a keep-alive loop to prevent Android emulator NAT timeouts during linking
                (while true; do echo -n "."; sleep 15; done) &
                KEEPALIVE_PID=${'$'}!
                
                set +e
                cargo build --release --package hainet-core
                CARGO_EXIT=${'$'}?
                set -e
                
                kill ${'$'}KEEPALIVE_PID 2>/dev/null || true
                echo ""
                
                if [ ${'$'}CARGO_EXIT -ne 0 ]; then
                    echo "Core build failed with exit code ${'$'}CARGO_EXIT"
                    exit ${'$'}CARGO_EXIT
                fi
                
                echo '  Setting up systemd service for Core...'
                
                # Ensure existing services are cleanly stopped
                run_sudo systemctl stop hainet-portal.service 2>/dev/null || true
                run_sudo systemctl disable hainet-portal.service 2>/dev/null || true
                run_sudo systemctl stop hainet-core.service 2>/dev/null || true
                run_sudo systemctl disable hainet-core.service 2>/dev/null || true
                run_sudo rm -f /etc/systemd/system/hainet-core.service 2>/dev/null || true
                
                # Create the unit file locally with variable expansion (unquoted EOF)
                cat << EOF > hainet-core.service
[Unit]
Description=HAI-Net Core Daemon
After=network.target

[Service]
Type=simple
User=${'$'}(id -un)
WorkingDirectory=${'$'}PWD
Environment="NODE_ENV=production"
Environment="RUST_LOG=info"
ExecStart=${'$'}PWD/target/release/hainet-core
Restart=always
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF
                
                # Securely move it into systemd directory
                run_sudo mv hainet-core.service /etc/systemd/system/hainet-core.service
                run_sudo systemctl daemon-reload
                run_sudo systemctl enable hainet-core.service
                run_sudo systemctl restart hainet-core.service
                
                echo '  Core Daemon is now running on port 8080.'
                
                # --- TOR SETUP INJECTION ---
                echo '  Setting up Tor Hidden Service for Global Connectivity...'
                run_sudo apt-get update -qq >/dev/null 2>&1 || true
                run_sudo apt-get install -y -qq tor >/dev/null 2>&1 || true
                
                cat << 'PYEOF' > gen_tor.py
import base64, hashlib, os
try:
    with open(os.path.expanduser("~/.hainet/identity/ed25519_priv.b64"), "r") as f:
        b64_key = f.read().strip()
    pkcs8 = base64.b64decode(b64_key)
    seed = pkcs8[-32:]
    expanded = bytearray(hashlib.sha512(seed).digest())
    expanded[0] &= 248
    expanded[31] &= 127
    expanded[31] |= 64
    header = b"== ed25519v1-secret: type0 ==" + bytes([0, 0, 0])
    with open("hs_ed25519_secret_key", "wb") as f:
        f.write(header + expanded)
except Exception as e:
    print("Error generating tor key:", e)
PYEOF
                python3 gen_tor.py
                
                run_sudo mkdir -p /var/lib/tor/hainet/
                run_sudo mv hs_ed25519_secret_key /var/lib/tor/hainet/hs_ed25519_secret_key
                TOR_USER=${'$'}(id -u debian-tor >/dev/null 2>&1 && echo "debian-tor" || echo "tor")
                run_sudo chown -R ${'$'}TOR_USER:${'$'}TOR_USER /var/lib/tor/hainet/
                run_sudo chmod 700 /var/lib/tor/hainet/
                run_sudo chmod 600 /var/lib/tor/hainet/hs_ed25519_secret_key
                rm -f gen_tor.py
                
                run_sudo grep -q "HAI-Net Hidden Service" /etc/tor/torrc || (
                    echo "" | run_sudo tee -a /etc/tor/torrc >/dev/null
                    echo "# HAI-Net Hidden Service" | run_sudo tee -a /etc/tor/torrc >/dev/null
                    echo "HiddenServiceDir /var/lib/tor/hainet/" | run_sudo tee -a /etc/tor/torrc >/dev/null
                    echo "HiddenServicePort 8080 127.0.0.1:8080" | run_sudo tee -a /etc/tor/torrc >/dev/null
                    echo "HiddenServicePort 9999 127.0.0.1:9999" | run_sudo tee -a /etc/tor/torrc >/dev/null
                )
                
                # Check for missing port 9999 in existing deployments
                if ! run_sudo grep -q "HiddenServicePort 9999" /etc/tor/torrc; then
                    run_sudo sed -i '/HiddenServicePort 8080/a HiddenServicePort 9999 127.0.0.1:9999' /etc/tor/torrc
                fi
                
                run_sudo systemctl restart tor || true
                echo '  Tor Hidden Service configured!'
                # --- END TOR SETUP ---
                
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
                if (exitStatus == 99) {
                    val errorMsg = "Existing deployment found. Please resolve via UI."
                    withContext(Dispatchers.Main) { onLog("\n[ERROR] $errorMsg\n") }
                    return@withContext Result.failure(ExistingDeploymentException())
                }
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
