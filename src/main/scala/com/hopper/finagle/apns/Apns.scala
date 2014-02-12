package com.hopper.finagle
package apns

import protocol._
import com.twitter.util._
import com.twitter.concurrent.{ Broker, Offer }
import com.twitter.finagle.{ ClientCodecConfig, Service, ServiceFactory }
import com.twitter.finagle.client._
import com.twitter.finagle.netty3.{ Netty3Transporter, Netty3TransporterTLSConfig, SimpleChannelSnooper }
import com.twitter.finagle.pool.ReusingPool
import com.twitter.finagle.ssl.JSSE
import com.twitter.finagle.stats.{ DefaultStatsReceiver, StatsReceiver }
import com.twitter.finagle.transport.Transport
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.KeyManagerFactory

case class Payload(alert: Option[String] = None, badge: Option[Int] = None, sound: Option[String] = None) {
  override def toString = {
    val payload = Seq(
      alert.map { """"alert":"%s"""" format _ },
      badge.map { """"badge":%d""" format _ },
      sound.map { """"sound":"%s"""" format _ }
    ).flatten.mkString(",")
    """{"aps":{%s}}""" format payload
  }
}

case class Notification(token: Array[Byte], payload: Payload) {
  val id = Sequence.next
}

sealed trait RejectionCode

case object NoErrorsEncountered extends RejectionCode
case object ProcessingError extends RejectionCode
case object MissingDeviceToken extends RejectionCode
case object MissingTopic extends RejectionCode
case object MissingPayload extends RejectionCode
case object InvalidTokenSize extends RejectionCode
case object InvalidTopicSize extends RejectionCode
case object InvalidPayloadSize extends RejectionCode
case object InvalidToken extends RejectionCode
case object Shutdown extends RejectionCode
case object Unknown extends RejectionCode

case class Rejection(code: RejectionCode, id: Int)

sealed trait ApnsEnvironment {
  val pushHostname: String
}
case object Sandbox extends ApnsEnvironment {
  val pushHostname = "gateway.sandbox.push.apple.com:2195"
}
case object Production extends ApnsEnvironment {
  val pushHostname = "gateway.push.apple.com:2195"
}

class ApnsPushClient(
  env: ApnsEnvironment,
  sslContext: SSLContext,
  broker: Broker[Rejection],
  val bufferSize: Int = 100) 
  extends DefaultClient[Notification, Unit](
    name = "apnsPush", 
    endpointer = Bridge[Notification, Rejection, Notification, Unit](new ApnsPushStreamTransporter(env, sslContext), new ApnsPushDispatcher(broker, _)),
    pool = (sr: StatsReceiver) => new ReusingPool(_, sr)
  ) {
  def newClient() = {
    super.newClient(env.pushHostname)
  }
}

class ApnsPushStreamTransporter(env: ApnsEnvironment, sslContext: SSLContext) extends Netty3Transporter[Notification, Rejection](
  name = "apnsPush",
  pipelineFactory = ApnsPush().client(ClientCodecConfig("apnsclient")).pipelineFactory,
  tlsConfig = Some(Netty3TransporterTLSConfig(
    newEngine = () => JSSE.client(sslContext),
    verifyHost = Some(env.pushHostname.split(":").head))
  ),
  channelSnooper = Some(new SimpleChannelSnooper("apns"))
)

class ApnsPushDispatcher[Req](broker: Broker[Rejection], trans: Transport[Req, Rejection])
  extends Service[Req, Unit] {
  
  trans.read
    .flatMap { r =>
      trans
        .close
        .map(_ => r)
    }
    .flatMap {
      broker ! _
    }

  def apply(req: Req): Future[Unit] = {
    trans.write(req)
  }
  
  override def isAvailable = trans.isOpen
  override def close(deadline: Time) = trans.close()

}

class Client(broker: Broker[Rejection], sf: ServiceFactory[Notification, Unit], bufferSize: Int = 100, stats: StatsReceiver = DefaultStatsReceiver) extends Service[Notification, Unit] {
  
  private[this] val notifications = new RingBuffer[Notification](bufferSize)
  private[this] val clientBroker = new Broker[Rejection]
  
  private[this] val rejected = stats.counter("rejected")
  private[this] val resent = stats.counter("resent")

  // Handles re-sending of failed notifications
  broker.recv.sync
    .flatMap { r =>
      rejected.incr
      var resend: List[Notification] = Nil
      notifications.synchronized {
        notifications.removeWhere { n =>
          if(n.id >= r.id) {
            if(n.id > r.id) resend = resend :+ n
            true
          } else false
        }
      }
      Future.collect(resend.map(this(_)))
        .map { _ =>
          resent.incr(resend.size)
          r
        }
    }
    .flatMap { r =>
      clientBroker ! r
    }

  val rejectedNotifications: Offer[Rejection] = clientBroker.recv

  def apply(notification: Notification) = {
    sf().flatMap { service =>
      service.apply(notification)
        .onSuccess { _ =>
          notifications.synchronized {
            notifications += notification
          }
        }
    }
  }

}