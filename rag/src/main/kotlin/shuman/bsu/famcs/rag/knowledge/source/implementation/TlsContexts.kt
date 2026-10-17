package shuman.bsu.famcs.rag.knowledge.source.implementation

import org.springframework.core.io.Resource
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Контекст TLS: системные доверенные сертификаты плюс заданные в настройках.
 *
 * Зачем: сайт fpmi.bsu.by отдаёт только свой сертификат и не отдаёт промежуточный (GlobalSign RSA OV SSL CA 2018).
 * Браузеры и curl достраивают цепочку сами, а Java и Python — нет. В Python-версии это «решили» отключением проверки
 * (`verify=False`), что открывало подмену сайта. Здесь недостающий публичный сертификат добавляется в доверенные,
 * а проверка остаётся включённой.
 */
object TlsContexts {
    /**
     * Строит TLS-контекст, который доверяет системным сертификатам и дополнительным (промежуточным), которые сервер сайта не присылает.
     *
     * @param extra файлы сертификатов в формате PEM.
     * @return контекст по умолчанию, если дополнительных сертификатов нет.
     */
    fun withExtraCertificates(extra: List<Resource>): SSLContext {
        if (extra.isEmpty()) return SSLContext.getDefault()

        val algorithm = TrustManagerFactory.getDefaultAlgorithm()
        val defaults = TrustManagerFactory
            .getInstance(algorithm)
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .flatMap { it.acceptedIssuers.toList() }

        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        defaults.forEachIndexed { index, certificate -> store.setCertificateEntry("default-$index", certificate) }
        val factory = CertificateFactory.getInstance("X.509")
        extra.forEachIndexed { resourceIndex, resource ->
            resource.inputStream.use { stream ->
                factory.generateCertificates(stream).forEachIndexed { index, certificate ->
                    store.setCertificateEntry("extra-$resourceIndex-$index", certificate)
                }
            }
        }
        val trust = TrustManagerFactory.getInstance(algorithm).apply { init(store) }
        return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
    }
}
