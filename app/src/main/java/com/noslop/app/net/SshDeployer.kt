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
        identity: CryptoService.IdentityKeys? = null
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

            val commands = listOf(
                "echo 'Starting HAI-Net deployment...'",
                "git clone https://github.com/gaborkukucska/hai.git || (cd hai && git pull)",
                "cd hai && echo '$escapedJson' > hub_config.json",
                "cd hai && cargo run --package hainet-seed --bin hainet-seed install -- --config hub_config.json",
                "cd hai && rm -f hub_config.json",
                "echo 'Deployment complete!'"
            )

            val output = StringBuilder()
            
            for (cmd in commands) {
                Logger.info(TAG, "Running: ${cmd.take(120)}...")
                val channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
                channel.setCommand(cmd)
                channel.inputStream = null
                channel.setErrStream(System.err)
                
                val inputStream = channel.inputStream
                channel.connect()
                
                val reader = inputStream.bufferedReader()
                var line: String? = reader.readLine()
                while (line != null) {
                    output.appendLine(line)
                    line = reader.readLine()
                }
                channel.disconnect()
            }

            session.disconnect()
            Result.success(output.toString())
        } catch (e: Exception) {
            Logger.error(TAG, "SSH Deployment failed: ${e.message}")
            Result.failure(e)
        }
    }
}
