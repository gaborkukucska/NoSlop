import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.security.MessageDigest

val p = BouncyCastleProvider()
p.services.filter { it.type == "MessageDigest" }.map { it.algorithm }.filter { it.contains("SHA3", ignoreCase=true) }.forEach { println(it) }
