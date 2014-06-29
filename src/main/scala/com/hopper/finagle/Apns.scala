package com.hopper.finagle

import com.hopper.finagle.apns.{ApnsPushClient, ApnsEnvironment, Notification, Rejection}
import com.twitter.concurrent.Broker
import javax.net.ssl.{ SSLContext, KeyManagerFactory }
import java.security.KeyStore

object ApnsRichClient {

  def newRichClient(env: ApnsEnvironment, sslContext: SSLContext): apns.Client = {
    val apnsClient = new ApnsPushClient(env, sslContext)
    new apns.Client(apnsClient.rejectionOffer, apnsClient.newClient(env.pushHostname))
  }

  def newRichClient(env: ApnsEnvironment, keyStore: KeyStore, password: Array[Char]): apns.Client = {
    val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(keyStore, password)
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagerFactory.getKeyManagers(), null, null)
    newRichClient(env, sslContext)
  }
}
