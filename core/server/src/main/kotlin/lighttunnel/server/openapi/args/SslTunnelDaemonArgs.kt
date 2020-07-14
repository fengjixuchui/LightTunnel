package lighttunnel.server.openapi.args

import io.netty.handler.ssl.SslContext
import lighttunnel.base.util.SslContextUtil
import lighttunnel.server.openapi.TunnelRequestInterceptor

class SslTunnelDaemonArgs(
    val bindAddr: String? = null,
    val bindPort: Int? = null,
    val tunnelRequestInterceptor: TunnelRequestInterceptor = TunnelRequestInterceptor.emptyImpl,
    val sslContext: SslContext = SslContextUtil.forBuiltinServer()
)