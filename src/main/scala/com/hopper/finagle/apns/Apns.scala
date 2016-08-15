package com.hopper.finagle
package apns

import protocol._
import com.twitter.util._
import com.twitter.concurrent.{Broker, Offer, Spool}
import com.twitter.finagle.{Service, ServiceFactory}
import com.twitter.finagle.client._
import com.twitter.finagle.dispatch.GenSerialClientDispatcher
import com.twitter.finagle.netty3.{Netty3Transporter, Netty3TransporterTLSConfig}
import com.twitter.finagle.ssl.JSSE
import com.twitter.finagle.stats.{ClientStatsReceiver, StatsReceiver}
import com.twitter.finagle.transport.Transport
import java.security.KeyStore
import javax.net.ssl.{SSLContext, KeyManagerFactory}
import java.util.concurrent.atomic.AtomicInteger
import org.jboss.netty.channel.{Channels, ChannelPipelineFactory}
import org.jboss.netty.buffer.ChannelBuffer

sealed trait Alert

case class RichAlert(
  title: Option[String] = None,
  body: Option[String] = None,
  actionLocKey: Option[String] = None,
  locKey: Option[String] = None,
  locArgs: Seq[String] = Seq.empty,
  launchImage: Option[String] = None
) extends Alert

case class SimpleAlert(alert: String) extends Alert

case class Payload(alert: Option[Alert] = None, badge: Option[Int] = None, sound: Option[String] = None, contentAvailable: Boolean = false, custom: Map[String, Any] = Map.empty)

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

case class Feedback(timestamp: Int, token: Array[Byte])

trait ApnsEnvironment {
  val pushHostname: String

  val feedbackHostname: String

  def sslContext(): Option[SSLContext]

  def tlsConfig(hostname: String) = {
    sslContext.map { ssl =>
      Netty3TransporterTLSConfig(
        newEngine = () => JSSE.client(ssl),
        verifyHost = Some(hostname.split(":").head))
    }
  }
}

object ApnsEnvironment {

  def apply(ph: String, fh: String, ctx: Option[SSLContext]): ApnsEnvironment = {
    new ApnsEnvironment {
      val pushHostname = ph
      val feedbackHostname = fh
      def sslContext = ctx
    }
  }

  def apply(ph: String, fh: String, keystore: KeyStore, password: Array[Char]): ApnsEnvironment = {
    apply(ph, fh, Some(sslContext(keystore, password)))
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
    apply("gateway.push.apple.com:2195", "feedback.push.apple.com:2196", Some(ctx))
  }

  def Sandbox(keystore: KeyStore, password: Array[Char]): ApnsEnvironment = {
    Sandbox(sslContext(keystore, password))
  }

  def Sandbox(ctx: SSLContext) = {
    apply("gateway.sandbox.push.apple.com:2195", "feedback.sandbox.push.apple.com:2196", Some(ctx))
  }

}

private[apns] object PipelineFactory extends ChannelPipelineFactory {
  def getPipeline = Channels.pipeline()
}

private[apns] class NettyTransport(name: String, tls: Option[Netty3TransporterTLSConfig], tcpConnectTimeout: Duration) extends Netty3Transporter[ChannelBuffer, ChannelBuffer](
  name = name,
  pipelineFactory = PipelineFactory,
  tlsConfig = tls,
  channelOptions = Netty3Transporter.defaultChannelOptions ++ Map("connectTimeoutMillis" -> (tcpConnectTimeout.inMillis: java.lang.Long)) // TODO: Use StackClient
)

class ApnsPushClient(
  env: ApnsEnvironment,
  bufferSize: Int,
  tcpConnectTimeout: Duration,
  private val broker: Broker[Rejection] = new Broker[Rejection]
) extends DefaultClient[Notification, Unit](
  name = "apns-push",
  endpointer = Bridge[SeqNotification, SeqRejection, Notification, Unit](
    new NettyTransport("apns-push", env.tlsConfig(env.pushHostname), tcpConnectTimeout)(_, _) map { ApnsPushTransport(_) }, new ApnsPushDispatcher(broker, bufferSize, _)
  ),
  pool = DefaultPool()
) {

  val rejectionOffer = broker.recv

  def newClient() = {
    super.newClient(env.pushHostname)
  }
}

class ApnsPushDispatcher(broker: Broker[Rejection], bufferSize: Int, trans: Transport[SeqNotification, SeqRejection])
extends GenSerialClientDispatcher[Notification, Unit, SeqNotification, SeqRejection](trans) {

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

  import GenSerialClientDispatcher.wrapWriteException

  override protected def dispatch(req: Notification, p: Promise[Unit]): Future[Unit] = {
    val seqNotification = seq.incrementAndGet -> req
    trans.write(seqNotification) rescue(
      wrapWriteException
    ) respond {
      p.updateIfEmpty(_)
    }
  }

}

class ApnsFeedbackClient(
  env: ApnsEnvironment,
  tcpConnectTimeout: Duration
) extends DefaultClient[Unit, Spool[Feedback]](
  name = "apns-feedback",
  endpointer = Bridge[Unit, Spool[Feedback], Unit, Spool[Feedback]](
    new NettyTransport("apns-feedback", env.tlsConfig(env.feedbackHostname), tcpConnectTimeout)(_, _) map { ApnsFeedbackTransport(_) }, new ApnsFeedbackDispatcher(_)
  ),
  pool = DefaultPool(low = 0, high = 1)
) {

  def newClient() = {
    super.newClient(env.feedbackHostname)
  }
}

class ApnsFeedbackDispatcher(trans: Transport[Unit, Spool[Feedback]]) extends Service[Unit, Spool[Feedback]] {
  def apply(unit: Unit) = {
    trans.read
  }
}

class Client(rejectedOffer: Offer[Rejection], push: ServiceFactory[Notification, Unit], feedback: ServiceFactory[Unit, Spool[Feedback]], stats: StatsReceiver = ClientStatsReceiver) extends Service[Notification, Unit] {
  
  private[this] val clientBroker = new Broker[Rejection]
  
  private[this] val rejected = stats.scope("rejected")
  private[this] val resent = stats.counter("resent")

  rejectedOffer.foreach { case r@Rejection(code, _, failed) =>
      rejected.counter(code.toString).incr
      resent.incr(failed.size)
      // TODO: any of these may fail, clients have no way of seeing these failures right now.
      // Reconsider resending automatically, or return a Seq[Future[Unit]]
      Future.collect(failed.map(apply(_)).toList)
        .flatMap { _ => clientBroker ! r }
    }

  val rejectedNotifications: Offer[Rejection] = clientBroker.recv

  private[this] val pushService = push.toService
  private[this] val feedbackService = feedback.toService

  def apply(notification: Notification) = {
    pushService(notification)
  }

  def fetchFeedback(): Future[Spool[Feedback]] = {
    feedbackService()
  }

  override def close(deadline: Time) = {
    Closable.all(push, feedback).close(deadline)
  }

}
