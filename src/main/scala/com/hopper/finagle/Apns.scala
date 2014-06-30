package com.hopper.finagle

import com.hopper.finagle.apns.{ApnsPushClient, ApnsEnvironment, Notification, Rejection}
import com.twitter.concurrent.Broker
import javax.net.ssl.{ SSLContext, KeyManagerFactory }
import java.security.KeyStore

object ApnsRichClient {

  def newRichClient(env: ApnsEnvironment): apns.Client = {
    val apnsClient = new ApnsPushClient(env)
    new apns.Client(apnsClient.rejectionOffer, apnsClient.newClient(env.pushHostname))
  }
}
