package lighttunnel.server

import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import lighttunnel.base.RemoteConnection
import lighttunnel.base.TunnelRequest
import lighttunnel.base.TunnelType
import lighttunnel.base.proto.*
import lighttunnel.base.utils.loggerDelegate
import lighttunnel.server.conn.impl.TunnelConnImpl
import lighttunnel.server.extra.ChannelInactiveExtra
import lighttunnel.server.listener.OnRemoteConnectionListener
import lighttunnel.server.local.LocalTcpClient
import lighttunnel.server.utils.*
import java.nio.charset.StandardCharsets

internal class TunnelClientDaemonChannelHandler(
    private val localTcpClient: LocalTcpClient,
    private val onChannelStateListener: OnChannelStateListener,
    private val onRemoteConnectListener: OnRemoteConnectionListener?
) : SimpleChannelInboundHandler<ProtoMsg>() {

    private val logger by loggerDelegate()

    @Throws(Exception::class)
    override fun channelInactive(ctx: ChannelHandlerContext) {
        logger.trace("channelInactive: {}", ctx)
        val tunnelId = ctx.channel().attr(AK_TUNNEL_ID).get()
        val sessionId = ctx.channel().attr(AK_SESSION_ID).get()
        if (tunnelId != null && sessionId != null) {
            localTcpClient.removeLocalChannel(tunnelId, sessionId)?.close()
        }
        val conn = ctx.channel().attr(AK_TUNNEL_CONN).get()
        val extra = ctx.channel().attr(AK_CHANNEL_INACTIVE_EXTRA).get()
        onChannelStateListener.onChannelInactive(ctx, conn, extra)
        super.channelInactive(ctx)
    }

    @Throws(Exception::class)
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable?) {
        logger.trace("exceptionCaught: {}", ctx, cause)
        ctx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
    }

    @Throws(Exception::class)
    override fun channelRead0(ctx: ChannelHandlerContext, msg: ProtoMsg) {
        logger.trace("channelRead0 : {}, {}", ctx, msg)
        when (msg.type) {
            ProtoMsgType.HEARTBEAT_PING -> doHandlePingMessage(ctx, msg as HeartbeatPingMsg)
            ProtoMsgType.RESPONSE_OK -> doHandleResponseOkMessage(ctx, msg as ResponseOkMsg)
            ProtoMsgType.RESPONSE_ERR -> doHandleResponseErrMessage(ctx, msg as ResponseErrMsg)
            ProtoMsgType.TRANSFER -> doHandleTransferMessage(ctx, msg as TransferMsg)
            ProtoMsgType.REMOTE_CONNECTED -> doHandleRemoteConnectedMessage(ctx, msg as RemoteConnectedMsg)
            ProtoMsgType.REMOTE_DISCONNECT -> doHandleRemoteDisconnectMessage(ctx, msg as RemoteDisconnectMsg)
            ProtoMsgType.FORCE_OFF -> doHandleForceOffMessage(ctx, msg as ForceOffMsg)
            else -> {
                // Nothing
            }
        }
    }

    /** Ping */
    @Throws(Exception::class)
    private fun doHandlePingMessage(ctx: ChannelHandlerContext, msg: HeartbeatPingMsg) {
        logger.trace("doHandlePingMessage : {}, {}", ctx, msg)
        ctx.writeAndFlush(ProtoMsg.HEARTBEAT_PONG())
    }

    /** 隧道建立成功 */
    @Throws(Exception::class)
    private fun doHandleResponseOkMessage(ctx: ChannelHandlerContext, msg: ResponseOkMsg) {
        logger.trace("doHandleResponseOkMessage : {}, {}", ctx, msg)
        val tunnelRequest = TunnelRequest.fromBytes(msg.data)
        ctx.channel().attr(AK_TUNNEL_ID).set(msg.tunnelId)
        ctx.channel().attr(AK_TUNNEL_REQUEST).set(tunnelRequest)
        ctx.channel().attr(AK_CHANNEL_INACTIVE_EXTRA).set(null)
        val conn = ctx.channel().attr(AK_TUNNEL_CONN).get()
        conn?.finalTunnelRequest = tunnelRequest
        logger.debug("Opened Tunnel: {}", tunnelRequest)
        onChannelStateListener.onChannelConnected(ctx, conn)
    }

    /** 隧道建立失败 */
    @Throws(Exception::class)
    private fun doHandleResponseErrMessage(ctx: ChannelHandlerContext, msg: ResponseErrMsg) {
        logger.trace("doHandleResponseErrMessage : {}, {}", ctx, msg)
        val errMsg = String(msg.data, StandardCharsets.UTF_8)
        ctx.channel().attr(AK_TUNNEL_ID).set(null)
        ctx.channel().attr(AK_TUNNEL_REQUEST).set(null)
        ctx.channel().attr(AK_CHANNEL_INACTIVE_EXTRA).set(ChannelInactiveExtra(false, Exception(errMsg)))
        ctx.channel().close()
        logger.debug("Open Tunnel Error: {}", errMsg)
    }

    /** 数据透传消息 */
    @Throws(Exception::class)
    private fun doHandleTransferMessage(ctx: ChannelHandlerContext, msg: TransferMsg) {
        logger.trace("doHandleTransferMessage : {}, {}", ctx, msg)
        ctx.channel().attr(AK_TUNNEL_ID).set(msg.tunnelId)
        ctx.channel().attr(AK_SESSION_ID).set(msg.sessionId)
        val tunnelRequest = ctx.channel().attr(AK_TUNNEL_REQUEST).get()
        when (tunnelRequest?.tunnelType) {
            TunnelType.TCP, TunnelType.HTTP, TunnelType.HTTPS -> {
                localTcpClient.acquireLocalChannel(
                    tunnelRequest.localAddr, tunnelRequest.localPort,
                    msg.tunnelId, msg.sessionId,
                    ctx.channel(),
                    object : LocalTcpClient.OnArriveLocalChannelCallback {
                        override fun onArrived(localChannel: Channel) {
                            logger.trace("onArrived: {}", localChannel)
                            localChannel.writeAndFlush(Unpooled.wrappedBuffer(msg.data))
                        }

                        override fun onUnableArrive(cause: Throwable) {
                            super.onUnableArrive(cause)
                            ctx.writeAndFlush(ProtoMsg.LOCAL_DISCONNECT(msg.tunnelId, msg.sessionId))
                        }
                    })
            }
            else -> {
                // Nothing
            }
        }
    }

    /** 连接本地隧道消息 */
    @Throws(Exception::class)
    private fun doHandleRemoteConnectedMessage(ctx: ChannelHandlerContext, msg: RemoteConnectedMsg) {
        logger.trace("doHandleRemoteConnectedMessage : {}, {}", ctx, msg)
        ctx.channel().attr(AK_TUNNEL_ID).set(msg.tunnelId)
        ctx.channel().attr(AK_SESSION_ID).set(msg.sessionId)
        val conn = try {
            RemoteConnection.fromBytes(msg.data)
        } catch (e: Exception) {
            null
        }
        if (conn != null) {
            onRemoteConnectListener?.onRemoteConnected(conn)
        }
        val tunnelRequest = ctx.channel().attr(AK_TUNNEL_REQUEST).get()
        if (tunnelRequest != null) {
            localTcpClient.acquireLocalChannel(
                tunnelRequest.localAddr, tunnelRequest.localPort,
                msg.tunnelId, msg.sessionId,
                ctx.channel()
            )
        }
    }

    /** 用户隧道断开消息 */
    @Throws(Exception::class)
    private fun doHandleRemoteDisconnectMessage(ctx: ChannelHandlerContext, msg: RemoteDisconnectMsg) {
        logger.trace("doHandleRemoteDisconnectMessage : {}, {}", ctx, msg)
        val conn = try {
            RemoteConnection.fromBytes(msg.data)
        } catch (e: Exception) {
            null
        }
        if (conn != null) {
            onRemoteConnectListener?.onRemoteDisconnect(conn)
        }
        localTcpClient.removeLocalChannel(msg.tunnelId, msg.sessionId)
            ?.writeAndFlush(Unpooled.EMPTY_BUFFER)
            ?.addListener(ChannelFutureListener.CLOSE)
    }

    /** 强制下线消息 */
    @Throws(Exception::class)
    private fun doHandleForceOffMessage(ctx: ChannelHandlerContext, msg: ForceOffMsg) {
        logger.trace("doHandleForceOffMessage : {}, {}", ctx, msg)
        ctx.channel().attr(AK_TUNNEL_ID).set(null)
        ctx.channel().attr(AK_TUNNEL_REQUEST).set(null)
        ctx.channel().attr(AK_CHANNEL_INACTIVE_EXTRA).set(ChannelInactiveExtra(true, Exception("ForceOff")))
        ctx.channel().writeAndFlush(ProtoMsg.FORCE_OFF_REPLY()).addListener(ChannelFutureListener.CLOSE)
    }

    interface OnChannelStateListener {
        fun onChannelInactive(
            ctx: ChannelHandlerContext,
            conn: TunnelConnImpl?,
            extra: ChannelInactiveExtra?
        ) {
        }

        fun onChannelConnected(ctx: ChannelHandlerContext, conn: TunnelConnImpl?) {}
    }

}
