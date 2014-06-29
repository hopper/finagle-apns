package com.hopper.finagle
package apns

import protocol._
import com.twitter.util._
import com.twitter.concurrent.{Broker, Offer}
import com.twitter.finagle.{ClientCodecConfig, Service, ServiceFactory}
import com.twitter.finagle.client._
import com.twitter.finagle.netty3.{Netty3Transporter, Netty3TransporterTLSConfig, SimpleChannelSnooper}
import com.twitter.finagle.pool.ReusingPool
import com.twitter.finagle.ssl.JSSE
import com.twitter.finagle.stats.{ClientStatsReceiver, StatsReceiver}
import com.twitter.finagle.transport.Transport
import java.security.KeyStore
import javax.net.ssl.{SSLContext, TrustManagerFactory, KeyManagerFactory}
import java.util.concurrent.atomic.AtomicInteger

sealed trait Alert
case class RichAlert(
  body: String,
  actionLocKey: Option[String] = None,
  locKey: Option[String] = None,
  locArgs: Seq[String] = Seq.empty,
  launchImage: Option[String] = None
) extends Alert {
  override def toString = {
    val alert = Seq(
      Some(""""body":"%s"""" format body),
      actionLocKey.map(""""action-loc-key":"%s"""" format _),
      locKey.map(""""loc-key":"%s"""" format _),
      Some(locArgs).filter(_.size > 0).map(""""locArgs":[%s]""" format _.mkString("\"", ",", "\"")),
      launchImage.map(""""launch-image":"%s"""" format _)
    ).flatten.mkString(",")
    """{%s}""" format alert
  }
}

case class SimpleAlert(alert: String) extends Alert {
  override def toString = {
    """"%s"""" format alert
  }
}

case class Payload(alert: Option[Alert] = None, badge: Option[Int] = None, sound: Option[String] = None) {
  override def toString = {
    val payload = Seq(
      alert.map { """"alert":%s""" format _ },
      badge.map { """"badge":%d""" format _ },
      sound.map { """"sound":"%s"""" format _ }
    ).flatten.mkString(",")
    """{"aps":{%s}}""" format payload
  }
}

case class Notification(token: Array[Byte], payload: Payload)

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

/**
 * Holds a rejected notification and all subsequent notifications that were sent.
 * 
 * Depending on the client's buffer size, it's possible that the failed notification is no longer available.
 * 
 * Note that when APNS rejects a notification, all subsequent notifications sent on the same connection are considered failed.
 */
case class Rejection(code: RejectionCode, rejected: Option[Notification], resent: Seq[Notification])

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
  bufferSize: Int = 100,
  private val broker: Broker[Rejection] = new Broker[Rejection])
  extends DefaultClient[Notification, Unit](
    name = "apnsPush",
    endpointer = Bridge[SeqNotification, SeqRejection, Notification, Unit](new ApnsPushStreamTransporter(env, sslContext), new ApnsPushDispatcher(broker, bufferSize, _)), 
    pool = (sr: StatsReceiver) => new ReusingPool(_, sr)
  ) {

  val rejectionOffer = broker.recv

  def newClient() = {
    super.newClient(env.pushHostname)
  }
}

class ApnsPushStreamTransporter(env: ApnsEnvironment, sslContext: SSLContext) extends Netty3Transporter[SeqNotification, SeqRejection](
  name = "apnsPush",
  pipelineFactory = ApnsPush().client(ClientCodecConfig("apnsclient")).pipelineFactory,
  tlsConfig = Some(Netty3TransporterTLSConfig(
    newEngine = () => JSSE.client(sslContext),
    verifyHost = Some(env.pushHostname.split(":").head))
  ),
  channelSnooper = Some(new SimpleChannelSnooper("apns"))
)

class ApnsPushDispatcher(broker: Broker[Rejection], bufferSize: Int, trans: Transport[SeqNotification, SeqRejection])
  extends Service[Notification, Unit] {

  private[this] val seq = new AtomicInteger(0)
  private[this] val notifications = new RingBuffer[SeqNotification](bufferSize)

  for {
    (rejectedId, code) <- trans.read
    _ <- trans.close
  } yield {
    notifications.synchronized {
      val rejected = notifications.find(_._1 == rejectedId).map(_._2)
      val fails = notifications.collect { case (id, n) if (id > rejectedId) => n }
      broker ! Rejection(code, rejected, fails)
    }
  }

  def apply(req: Notification): Future[Unit] = {
    val seqNotification = seq.incrementAndGet -> req
    trans
      .write(seqNotification)
      .onSuccess { _ =>
        notifications.synchronized {
          notifications += seqNotification
        }
      }
  }
  
  override def isAvailable = trans.isOpen
  override def close(deadline: Time) = trans.close(deadline)

}

class Client(rejectedOffer: Offer[Rejection], sf: ServiceFactory[Notification, Unit], bufferSize: Int = 100, stats: StatsReceiver = ClientStatsReceiver) extends Service[Notification, Unit] {
  
  private[this] val clientBroker = new Broker[Rejection]
  
  private[this] val rejected = stats.scope("rejected")
  private[this] val resent = stats.counter("resent")

  rejectedOffer.sync
    .flatMap { case r@Rejection(code, _, failed) =>
      rejected.counter(code.toString).incr
      resent.incr(failed.size)
      Future.collect(failed.map(apply(_)).toList)
        .flatMap { _ => clientBroker ! r }
    }

  val rejectedNotifications: Offer[Rejection] = clientBroker.recv

  def apply(notification: Notification) = {
    sf().flatMap { service =>
      service.apply(notification)
    }
  }

}