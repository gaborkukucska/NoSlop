package com.noslop.app.net

import com.jcraft.jsch.JSch
import com.noslop.app.debug.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SshDeployer {
    private const val TAG = "SSH_DEPLOYER"

    suspend fun deployHaiNetHub(
        ip: String,
        user: String,
        pass: String,
        cloudflareToken: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val jsch = JSch()
            val session = jsch.getSession(user, ip, 22)
            session.setPassword(pass)
            session.setConfig("StrictHostKeyChecking", "no")
            session.connect(10000)

            val commands = listOf(
                "echo 'Starting HAI-Net deployment...'",
                "curl -sL https://github.com/gaborkukucska/hai/releases/latest/download/hainet-seed-linux-amd64 -o hainet-seed || echo 'Skipped download'",
                "chmod +x hainet-seed",
                "echo '{\"cloudflare_token\":\"$cloudflareToken\"}' > hub_config.json",
                "./hainet-seed install --config hub_config.json",
                "rm hub_config.json",
                "echo 'Deployment complete!'"
            )

            val output = StringBuilder()
            
            for (cmd in commands) {
                Logger.info(TAG, "Running: $cmd")
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
