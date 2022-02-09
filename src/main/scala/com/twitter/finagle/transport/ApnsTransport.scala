package com.twitter.finagle.transport

import java.security.cert.Certificate

import com.twitter.finagle.netty3.ChannelBufferBuf
import com.twitter.io.Buf
import com.twitter.util.{Future, Time}
import org.jboss.netty.buffer.ChannelBuffer

// TODO: there's a way to write our apns codec without relying on netty Transport. It used to be experimental in finagle 6.22, but should be available now in 6.27
trait ApnsTransport {

  val trans: Transport[ChannelBuffer, ChannelBuffer]

  def peerCertificate: Option[Certificate] = trans.peerCertificate
  def status                               = trans.status
  val onClose                              = trans.onClose
  def localAddress                         = trans.localAddress
  def remoteAddress                        = trans.remoteAddress
  def close(deadline: Time)                = trans.close(deadline)

  @volatile private[this] var buf                 = Buf.Empty
  protected[this] def read(len: Int): Future[Buf] =
    if (buf.length < len) {
      trans.read flatMap { chanBuf =>
        buf = buf.concat(ChannelBufferBuf(chanBuf))
        read(len)
      }
    } else {
      val out = buf.slice(0, len)
      buf = buf.slice(len, buf.length)
      Future.value(out)
    }
}
