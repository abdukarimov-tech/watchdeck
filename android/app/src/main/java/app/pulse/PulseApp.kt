package app.pulse

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class PulseApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // sshj запрашивает X25519/Ed25519 у JCE-провайдера по имени "BC".
        // Встроенный в Android "BC" — урезанная древняя копия BouncyCastle без
        // этих алгоритмов, из-за чего SSH-рукопожатие падает с
        // "no such algorithm: X25519 for provider BC". Современный bcprov-jdk18on
        // уже идет зависимостью sshj — заменяем им встроенный провайдер.
        // Conscrypt и AndroidKeyStore стоят выше по приоритету и не затрагиваются.
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())
    }
}
