package com.hopper.finagle.apns
package protocol

import com.twitter.finagle.{Codec, CodecFactory}
import com.twitter.finagle.netty3.BufChannelBuffer
import com.twitter.io.{Buf, Charsets}
import com.twitter.util.Future
import com.twitter.concurrent.Offer
import org.jboss.netty.buffer.{ChannelBuffer, ChannelBuffers}
import org.jboss.netty.channel.{Channel, Channels, ChannelHandlerContext, ChannelPipelineFactory, MessageEvent, SimpleChannelHandler}
import org.jboss.netty.handler.codec.oneone.OneToOneEncoder
import org.jboss.netty.handler.codec.frame.FrameDecoder

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

private[protocol] class NotificationEncoder extends OneToOneEncoder {
  
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

  val SEND = ByteArray(0x02.toByte)

  def encode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) = {
    msg match {
      case (id: Int, n: Notification) => {
        val msg = DeviceToken(n.token) concat
          Payload(n.payload.toString) concat
          NotificationId(id)

        val buffer = SEND concat
          U32BE(msg.length) concat
          msg

        BufChannelBuffer(buffer)
      }
      case _ => throw new IllegalArgumentException("unknown msg: " + msg)
    }
  }
}

private[protocol] class RejectionDecoder extends FrameDecoder {

  val COMMAND = 0x08.toByte
  
  def decode(ctx: ChannelHandlerContext, channel: Channel, msg: ChannelBuffer) = {
    msg.markReaderIndex()
    if(msg.readableBytes() >= 6 && msg.readByte == COMMAND) {
      val code = msg.readByte() match {
        case 0x00 => NoErrorsEncountered
        case 0x01 => ProcessingError
        case 0x02 => MissingDeviceToken
        case 0x03 => MissingTopic
        case 0x04 => MissingPayload
        case 0x05 => InvalidTokenSize
        case 0x06 => InvalidTopicSize
        case 0x07 => InvalidPayloadSize
        case 0x08 => InvalidToken
        case 0x0A => Shutdown
        case 0xFF => Unknown
      }
      msg.readInt -> code
    } else {
      msg.resetReaderIndex
      null
    }
  }
}

case class ApnsPush extends CodecFactory[SeqNotification, SeqRejection] {

  def server = Function.const { throw new UnsupportedOperationException }

  def client = Function.const {
    new Codec[SeqNotification, SeqRejection] {
      def pipelineFactory = new ChannelPipelineFactory {
        def getPipeline() = {
          val pipeline = Channels.pipeline()
          pipeline.addLast("encoder", new NotificationEncoder)
          pipeline.addLast("decoder", new RejectionDecoder)
          pipeline
        }
      }
    }
  }

}
