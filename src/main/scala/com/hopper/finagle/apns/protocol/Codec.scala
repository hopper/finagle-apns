package com.hopper.finagle.apns
package protocol

import com.twitter.finagle.netty3.{BufChannelBuffer, ChannelBufferBuf}
import com.twitter.finagle.transport.Transport
import com.twitter.io.Buf
import com.twitter.util.{Future, Time}
import org.jboss.netty.buffer.{ChannelBuffer, ChannelBuffers}

private[protocol] object Bufs {
  import Buf._

  object FramedByteArray {
    def apply(bytes: Array[Byte]) = {
      U16BE(bytes.length.toShort) concat
      ByteArray(bytes)
    }
    def apply(buf: Buf) = {
      U16BE(buf.length.toShort) concat
      buf
    }
  }

  object U16BE {
    def apply(s: Short) = {
      ByteArray(
        ((s >> 8) & 0xff).toByte,
        (s & 0xff).toByte
      )
    }
  }
}

private[protocol] object Codec {
  
  import Bufs._
  import Buf._
  
  sealed trait Item[T] {
    def apply(t: T): Buf
  }
  
  case object DeviceToken extends Item[Array[Byte]] {
    
    val ItemId = Buf.ByteArray(0x01.toByte)

    def apply(token: Array[Byte]) = {
      ItemId concat
      FramedByteArray(token)
    }
  }

  case object Payload extends Item[String] {

    val ItemId = Buf.ByteArray(0x02.toByte)

    def apply(payload: String) = {
      ItemId concat
      FramedByteArray(Utf8(payload))
    }
  }
  
  trait IntItem extends Item[Int] {

    val ItemId: Buf
    
    def apply(i: Int) = {
      ItemId concat
      U16BE(0x04.toShort) concat
      U32BE(i)
    }
  }

  case object NotificationId extends IntItem {
    val ItemId = Buf.ByteArray(0x03.toByte)
  }

  case object Expiration extends IntItem {
    val ItemId = Buf.ByteArray(0x04.toByte)
  }

  case object Priority extends Item[Byte] {
    val ItemId = Buf.ByteArray(0x05.toByte)
    def apply(b: Byte) = {
      ItemId concat
      U16BE(1) concat
      ByteArray(b)
    }
  }

  object NotificationBuf {
    val SEND = ByteArray(0x02.toByte)
    def apply(req: SeqNotification): Buf = {
      val (id, n) = req

      val msg = DeviceToken(n.token) concat
      Payload(n.payload.toString) concat
      NotificationId(id)

      SEND concat
      Buf.U32BE(msg.length) concat
      msg
    }
  }
  
  object RejectionBuf {
    val COMMAND = 0x08.toByte
    def unapply(buf: Buf): Option[RejectionCode] = {
      if (buf.length < 2) None else {
        val bytes = new Array[Byte](2)
        buf.slice(0, 2).write(bytes, 0)
        bytes match {
          case Array(COMMAND, RejectionCode(code)) => Some(code)
          case _ => None
        }
      }
    }
  }
}

case class ApnsTransport(
  trans: Transport[ChannelBuffer, ChannelBuffer]
) extends Transport[SeqNotification, SeqRejection] {

  import Codec._

  def isOpen = trans.isOpen
  val onClose = trans.onClose
  def localAddress = trans.localAddress
  def remoteAddress = trans.remoteAddress
  def close(deadline: Time) = trans.close(deadline)

  def write(req: SeqNotification): Future[Unit] = {
    trans.write(BufChannelBuffer(NotificationBuf(req)))
  }

  def read(): Future[SeqRejection] =
    read(2) flatMap { case RejectionBuf(code) => 
      read(4) map { case Buf.U32BE(id, _) =>
        id -> code
      } 
    }

  @volatile private[this] var buf = Buf.Empty
  private[this] def read(len: Int): Future[Buf] =
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
