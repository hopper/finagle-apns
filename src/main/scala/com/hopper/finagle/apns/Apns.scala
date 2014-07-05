package com.hopper.finagle
package apns

import protocol._
import com.twitter.util._
import com.twitter.concurrent.{Broker, Offer}
import com.twitter.finagle.{Service, ServiceFactory}
import com.twitter.finagle.client._
import com.twitter.finagle.netty3.{Netty3Transporter, Netty3TransporterTLSConfig, SimpleChannelSnooper}
import com.twitter.finagle.pool.ReusingPool
import com.twitter.finagle.ssl.JSSE
import com.twitter.finagle.stats.{ClientStatsReceiver, StatsReceiver}
import com.twitter.finagle.transport.Transport
import java.security.KeyStore
import javax.net.ssl.{SSLContext, TrustManagerFactory, KeyManagerFactory}
import java.util.concurrent.atomic.AtomicInteger
import org.jboss.netty.channel.{Channels, ChannelPipelineFactory}
import org.jboss.netty.buffer.ChannelBuffer

sealed trait Alert

case class RichAlert(
  body: String,
  actionLocKey: Option[String] = None,
  locKey: Option[String] = None,
  locArgs: Seq[String] = Seq.empty,
  launchImage: Option[String] = None
) extends Alert

case class SimpleAlert(alert: String) extends Alert

case class Payload(alert: Option[Alert] = None, badge: Option[Int] = None, sound: Option[String] = None, custom: Map[String, Any] = Map.empty)

case class Notification(token: Array[Byte], payload: Payload)

sealed trait RejectionCode

object RejectionCode {
  def unapply(byte: Byte): Option[RejectionCode] = {
    byte match {
      case 0x00 => Some(NoErrorsEncountered)
      case 0x01 => Some(ProcessingError)
      case 0x02 => Some(MissingDeviceToken)
      case 0x03 => Some(MissingTopic)
      case 0x04 => Some(MissingPayload)
      case 0x05 => Some(InvalidTokenSize)
      case 0x06 => Some(InvalidTopicSize)
      case 0x07 => Some(InvalidPayloadSize)
      case 0x08 => Some(InvalidToken)
      case 0x0A => Some(Shutdown)
      case 0xFF => Some(Unknown)
      case _ => None
    }
  }
}

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

trait ApnsEnvironment {
  val pushHostname: String
  
  def sslContext(): Option[SSLContext]
  
  def tlsConfig() = {
    sslContext.map { ssl =>
      Netty3TransporterTLSConfig(
        newEngine = () => JSSE.client(ssl),
        verifyHost = Some(pushHostname.split(":").head))
    }
  }
}

object ApnsEnvironment {

  def apply(hostname: String, ctx: Option[SSLContext]): ApnsEnvironment = {
    new ApnsEnvironment {
      val pushHostname = hostname
      def sslContext = ctx
    }
  }

  def apply(hostname: String, keystore: KeyStore, password: Array[Char]): ApnsEnvironment = {
    apply(hostname, Some(sslContext(keystore, password)))
  }

  def sslContext(keystore: KeyStore, password: Array[Char]) = {
    val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(keystore, password)
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagerFactory.getKeyManagers(), null, null)
    sslContext
  }

  def Production(keystore: KeyStore, password: Array[Char]): ApnsEnvironment = {
    Production(sslContext(keystore, password))
  }

  def Production(ctx: SSLContext) = {
    apply("gateway.push.apple.com:2195", Some(ctx))
  }

  def Sandbox(keystore: KeyStore, password: Array[Char]): ApnsEnvironment = {
    Sandbox(sslContext(keystore, password))
  }

  def Sandbox(ctx: SSLContext) = {
    apply("gateway.sandbox.push.apple.com:2195", Some(ctx))
  }

}

private[apns] object PipelineFactory extends ChannelPipelineFactory {
  def getPipeline = Channels.pipeline()
}

private[apns] class NettyTransport(env: ApnsEnvironment) extends Netty3Transporter[ChannelBuffer, ChannelBuffer](
  name = "apns",
  pipelineFactory = PipelineFactory,
  tlsConfig = env.tlsConfig)

class ApnsPushClient(
  env: ApnsEnvironment,
  bufferSize: Int = 100,
  private val broker: Broker[Rejection] = new Broker[Rejection]
) extends DefaultClient[Notification, Unit](
  name = "apns",
  endpointer = Bridge[SeqNotification, SeqRejection, Notification, Unit](
    new NettyTransport(env)(_, _) map { ApnsTransport(_) }, new ApnsPushDispatcher(broker, bufferSize, _)     
  ),
  pool = (sr: StatsReceiver) => new ReusingPool(_, sr)
) {

  val rejectionOffer = broker.recv

  def newClient() = {
    super.newClient(env.pushHostname)
  }
}

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
      // TODO: any of these may fail, clients have no way of seeing these failures right now.
      // Reconsider resending automatically, or return a Seq[Future[Unit]]
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
