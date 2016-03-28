package com.hopper.finagle.apns
package protocol

import com.twitter.finagle.netty3.{BufChannelBuffer, ChannelBufferBuf}
import com.twitter.finagle.transport.Transport
import com.twitter.io.Buf
import com.twitter.util.{Future, Time}
import com.twitter.concurrent.{SpoolSource, Spool}
import org.jboss.netty.buffer.ChannelBuffer

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

  case object PayloadItem extends Item[Payload] {

    val ItemId = Buf.ByteArray(0x02.toByte)

    def apply(payload: Payload) = {
      ItemId concat
      FramedByteArray(Utf8(Json.encode(payload).getOrElse("")))
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
      PayloadItem(n.payload) concat
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

  /**
   *  poor-man's JSON encoding. Supports the following types as values:
   *  String, Boolean, Number, Map[String, T], Traversable[T] and Option[T] where T is one of those types.
   *
   *  None and null values are omitted from the encoded JSON objects and arrays. Empty objects and arrays are also omitted.
   *
   *  Thus, encoding Map("foo" -> None, "foobar" -> Seq.empty, "bar" -> "baz") results in {"bar":"baz"}
   */
  object Json {

    def escape(str: String) = str.replaceAllLiterally("\"", """\"""")

    def encode(value: Any): Option[String] = {
      value match {
        case Payload(alert, badge, sound, contentAvailable, more) => encode {
          Map("aps" ->
            Map("alert" -> alert, "badge" -> badge, "sound" -> sound, "content-available" -> (if(contentAvailable) Some(1) else None))
          ) ++ more
        }
        case SimpleAlert(str) => encode(str)
        case RichAlert(title, body, actionLocKey, locKey, locArgs, launchImage) =>
          encode(Map("title" -> title, "body" -> body, "action-loc-key" -> actionLocKey, "loc-key" -> locKey, "loc-args" -> locArgs, "launch-image" -> launchImage))
        case s: String => Some(""""%s"""" format escape(s))
        case _: Boolean => Some(value.toString)
        case _: Number => Some(value.toString)
        case Some(v) => encode(v)
        case None => None
        case null => None
        case obj: Map[_, _] => {
          Some(
            obj.flatMap {
              case (k, v) =>
                encode(v).map { encoded =>
                  """"%s":%s""" format (k, encoded)
                }
            }
          ).filter(_.nonEmpty)
            .map(_.mkString("{", ",", "}"))
        }
        case values: Traversable[_] => Some(values.flatMap(encode _)).filter(_.nonEmpty).map(_.mkString("[", ",", "]"))
      }
    }
  }

}

trait ApnsTransport {
  
  val trans: Transport[ChannelBuffer, ChannelBuffer]

  def isOpen = trans.isOpen
  val onClose = trans.onClose
  def localAddress = trans.localAddress
  def remoteAddress = trans.remoteAddress
  def close(deadline: Time) = trans.close(deadline)

  @volatile private[this] var buf = Buf.Empty
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

case class ApnsPushTransport(
  trans: Transport[ChannelBuffer, ChannelBuffer]
) extends Transport[SeqNotification, SeqRejection] with ApnsTransport {

  import Codec._

  def write(req: SeqNotification): Future[Unit] = {
    trans.write(BufChannelBuffer(NotificationBuf(req)))
  }

  def read(): Future[SeqRejection] =
    read(2) flatMap { case RejectionBuf(code) => 
      read(4) map { case Buf.U32BE(id, _) =>
        id -> code
      } 
    }

}

case class ApnsFeedbackTransport(
  trans: Transport[ChannelBuffer, ChannelBuffer]
) extends Transport[Unit, Spool[Feedback]] with ApnsTransport {

  def write(req: Unit): Future[Unit] = Future.Done

  def read(): Future[Spool[Feedback]] = {
    val source = new SpoolSource[Feedback]
    onClose ensure {
      source.close
    }
    def readAll() {
      read(4) flatMap { case Buf.U32BE(ts, _) =>
        read(34) map { buf =>
          val token = new Array[Byte](32)
          buf.write(token, 2)
          source.offer(Feedback(ts, token))
          readAll()
        }
      }
    }
    readAll
    source()
  }
}
