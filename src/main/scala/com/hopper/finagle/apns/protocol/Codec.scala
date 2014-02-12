package com.hopper.finagle.apns
package protocol

import com.twitter.finagle.CodecFactory
import com.twitter.finagle.Codec
import com.twitter.util.Future
import org.jboss.netty.channel.ChannelPipelineFactory
import org.jboss.netty.channel.Channels
import org.jboss.netty.handler.codec.oneone.OneToOneEncoder
import org.jboss.netty.channel.ChannelHandlerContext
import org.jboss.netty.channel.Channel
import org.jboss.netty.buffer.ChannelBuffers
import org.jboss.netty.handler.codec.frame.FrameDecoder
import org.jboss.netty.buffer.ChannelBuffer
import org.jboss.netty.channel.SimpleChannelHandler
import org.jboss.netty.channel.MessageEvent
import com.twitter.concurrent.Offer

class NotificationEncoder extends OneToOneEncoder {
  
  val SEND = 0x02.toByte
  
  sealed trait Item {
    val size: Int
    def write(buffer: ChannelBuffer)
  }

  case class DeviceToken(token: Array[Byte]) extends Item {
    val size = 1 + 2 + token.length
    def write(buffer: ChannelBuffer) = {
      buffer.writeByte(0x01)
      buffer.writeShort(token.length)
      buffer.writeBytes(token)
    }
  }
  case class Payload(payload: String) extends Item {
    val size = 1 + 2 + payload.getBytes.length
    def write(buffer: ChannelBuffer) = {
      buffer.writeByte(0x02)
      buffer.writeShort(payload.getBytes.length)
      buffer.writeBytes(payload.getBytes)
    }
  }
  case class NotificationId(id: Int) extends Item {
    val size = 1 + 2 + 4
    def write(buffer: ChannelBuffer) = {
      buffer.writeByte(0x03)
      buffer.writeShort(4)
      buffer.writeInt(id)
    }
  }
  case class Expiration(date: Int) extends Item {
    val size = 1 + 2 + 4
    def write(buffer: ChannelBuffer) = {
      buffer.writeByte(0x04)
      buffer.writeShort(4)
      buffer.writeInt(date)
    }
  }
  case class Priority(priority: Byte) extends Item {
    val size = 1 + 2 + 1
    def write(buffer: ChannelBuffer) = {
      buffer.writeByte(0x05)
      buffer.writeShort(1)
      buffer.writeByte(priority)
    }
  }

  def encode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) = {
    msg match {
      case n: Notification => {
        val values = Seq(DeviceToken(n.token), Payload(n.payload.toString), NotificationId(n.id))
        val frameLength = values.map(_.size).sum
        val buffer = ChannelBuffers.dynamicBuffer(1 + 4 + frameLength)
        buffer.writeByte(SEND)
        buffer.writeInt(frameLength)
        values.foreach { item =>
          item.write(buffer)
        }
        buffer
      }
      case _ => throw new IllegalArgumentException("unknown msg: " + msg)
    }
  }
}

class RejectionDecoder extends FrameDecoder {

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
      Rejection(code, msg.readInt)
    } else {
      msg.resetReaderIndex
      null
    }
  }
}

class ApnsPushChannelHandler extends SimpleChannelHandler {
  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) = {
    e.getMessage match {
      case r: Rejection =>
        
    } 
  }
}

case class ApnsPush extends CodecFactory[Notification, Rejection] {

  def server = Function.const { throw new UnsupportedOperationException }

  def client = Function.const {
    new Codec[Notification, Rejection] {
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