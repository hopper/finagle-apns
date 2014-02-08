package com.hopper.finagle

import com.hopper.finagle.apns.{ApnsClient, ApnsEnvironment, Notification, Rejection}
import com.twitter.concurrent.Broker
import javax.net.ssl.{ SSLContext, KeyManagerFactory }
import java.security.KeyStore

object ApnsRichClient {

  def newRichClient(client: ApnsClient) = {
    val broker = new Broker[Rejection]
    val sf = client.newClient()
    new apns.Client(broker, sf, client.bufferSize, client.statsReceiver)
  }

  def newRichClient(env: ApnsEnvironment, sslContext: SSLContext): apns.Client = {
    val broker = new Broker[Rejection]
    val sf = new ApnsClient(env, sslContext, broker).newClient(env.hostname)
    new apns.Client(broker, sf)
  }

  def newRichClient(env: ApnsEnvironment, keyStore: KeyStore, password: Array[Char]): apns.Client = {
    val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(keyStore, password)
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagerFactory.getKeyManagers(), null, null)
    newRichClient(env, sslContext)
  }
}
