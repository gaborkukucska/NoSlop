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
            val jsch = JSch()
            val session = jsch.getSession(user, ip, 22)
            session.setPassword(pass)
            session.setConfig("StrictHostKeyChecking", "no")
            session.connect(10000)

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

            // Escape single quotes for safe shell echo
            val escapedJson = configJson.toString().replace("'", "'\\''")

            val script = """
                set -e
                exec 2>&1
                echo 'Starting HAI-Net deployment...'
                
                if ! command -v cargo &> /dev/null; then
                    echo 'Rust/Cargo not found. Installing Rust...'
                    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
                    source ${"$"}HOME/.cargo/env
                else
                    echo 'Rust/Cargo is already installed.'
                    source ${"$"}HOME/.cargo/env || true
                fi
                
                echo 'Cloning or pulling repository...'
                git clone https://github.com/gaborkukucska/hai.git || (cd hai && git pull)
                
                echo 'Writing configuration...'
                cd hai
                echo '$escapedJson' > hub_config.json
                
                echo 'Running HAI-Net seed installer...'
                cargo run --package hainet-seed --bin hainet-seed install -- --config hub_config.json
                
                echo 'Cleaning up...'
                rm -f hub_config.json
                echo 'Deployment complete!'
            """.trimIndent()

            onLog("Connecting to SSH at $user@$ip...")
            
            val output = StringBuilder()
            val channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
            channel.setCommand("bash -c '$script'")
            channel.inputStream = null
            // we use 2>&1 in the script, so stderr will go to stdout
            channel.setErrStream(null) 
            
            val inputStream = channel.inputStream
            channel.connect(10000)
            
            val reader = inputStream.bufferedReader()
            val buffer = CharArray(1024)
            var read: Int
            
            while (true) {
                read = reader.read(buffer)
                if (read > 0) {
                    val chunk = String(buffer, 0, read)
                    output.append(chunk)
                    onLog(chunk)
                }
                if (channel.isClosed) {
                    if (inputStream.available() > 0) continue
                    break
                }
                kotlinx.coroutines.delay(100)
            }
            
            val exitStatus = channel.exitStatus
            channel.disconnect()
            session.disconnect()
            
            if (exitStatus != 0) {
                val errorMsg = "Deployment failed with exit code $exitStatus"
                onLog("\n[ERROR] $errorMsg")
                return@withContext Result.failure(Exception(errorMsg))
            }
            
            Result.success(output.toString())
        } catch (e: Exception) {
            Logger.error(TAG, "SSH Deployment failed: ${e.message}")
            onLog("\n[CRITICAL ERROR] ${e.message}")
            Result.failure(e)
        }
    }
}
