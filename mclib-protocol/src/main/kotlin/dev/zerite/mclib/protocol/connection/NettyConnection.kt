package dev.zerite.mclib.protocol.connection

import dev.zerite.mclib.protocol.connection.io.EncryptionCodec
import dev.zerite.mclib.protocol.version.MinecraftProtocol
import dev.zerite.mclib.protocol.version.PacketDirection
import dev.zerite.mclib.protocol.version.ProtocolVersion
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.util.AttributeKey
import javax.crypto.Cipher
import javax.crypto.SecretKey

/**
 * Instance of a connection to a remote server using Netty.
 *
 * @author Koding
 * @since  0.1.0-SNAPSHOT
 */
@Suppress("UNUSED")
open class NettyConnection(val direction: PacketDirection) : SimpleChannelInboundHandler<Any>() {

    companion object {
        /**
         * The key which references a connection.
         */
        val attribute: AttributeKey<NettyConnection> = AttributeKey.valueOf("connection")
    }

    /**
     * True when this connection has been terminated.
     */
    @Suppress("UNUSED")
    var disconnected = false
        private set

    /**
     * Reference to the current channel.
     */
    @Suppress("UNUSED")
    lateinit var channel: Channel

    /**
     * The current packet handler which should process any major events
     * in this connection.
     */
    @Suppress("UNUSED")
    var handler: PacketHandler? = null
        set(value) {
            handler?.assigned(this)
            field = value
        }

    /**
     * The current protocol state for this connection.
     */
    var state = MinecraftProtocol.HANDSHAKE

    /**
     * The current protocol version for this connection.
     * Used for encoding and decoding packets.
     */
    var version = ProtocolVersion.UNKNOWN

    /**
     * Send a packet to the remote connection and optionally listen
     * for when it was sent.
     *
     * TODO: Add packet queue / bulk writes
     *
     * @param  packet      The packet we're sending.
     * @param  listener    Callback function for when the packet was sent.
     *
     * @author Koding
     * @since  0.1.0-SNAPSHOT
     */
    fun <T : Any> send(packet: T, listener: T.() -> Unit = {}) =
        PacketSendingEvent(this, packet).let {
            handler?.sending(this, it)
            if (it.cancelled) return@let null
            if (!this::channel.isInitialized) return@let null

            channel.writeAndFlush(it.packet)
                ?.addListener { _ ->
                    handler?.sent(this, it.packet)
                    @Suppress("UNCHECKED_CAST")
                    listener(it.packet as T)
                }
        }

    /**
     * Enables encryption in the pipeline and will begin encrypting and
     * decrypting packets from now on.
     *
     * @author Koding
     * @since  0.1.0-SNAPSHOT
     */
    @Suppress("UNUSED")
    fun enableEncryption(secret: SecretKey): ChannelPipeline =
        channel.pipeline().addBefore("length", "crypto", EncryptionCodec(this, secret))

    /**
     * Closes the connection and disconnects from the host.
     *
     * @param  reason     The reason for closing this connection.
     * @author Koding
     * @since  0.1.0-SNAPSHOT
     */
    @Suppress("UNUSED")
    fun close(reason: String) =
        channel
            .takeIf { !disconnected }
            ?.apply { disconnected = true }
            ?.close()
            ?.addListener {
                handler?.disconnected(this, reason)
            }

    /**
     * Fired when the channel becomes active and begins connecting.
     *
     * @param  ctx        The channel context.
     * @author Netty
     */
    override fun channelActive(ctx: ChannelHandlerContext) {
        channel = ctx.channel()
        channel.attr(attribute).set(this)
        handler?.connected(this)
    }

    /**
     * Fired when the channel becomes inactive after a disconnect.
     *
     * @param  ctx     The context for this channel.
     * @author Netty
     */
    override fun channelInactive(ctx: ChannelHandlerContext) {
        // Run the handler
        close("{ \"text\": \"Disconnected\" }")
    }

    /**
     * Handles reading messages from the channel.
     *
     * @param  ctx     The context for this channel.
     * @param  packet  The packet which was read.
     * @author Netty
     */
    override fun channelRead0(ctx: ChannelHandlerContext, packet: Any) {
        handler?.received(this, packet)
    }

    /**
     * Fired when an exception is encountered in this connection.
     *
     * @param  ctx       The context for this channel.
     * @param  cause     The cause for this exception.
     * @author Netty
     */
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        handler?.exception(this, cause)
    }
}

/**
 * Gets the connection reference from this channel handler context.
 */
val ChannelHandlerContext.connection: NettyConnection?
    get() = channel().attr(NettyConnection.attribute).get()