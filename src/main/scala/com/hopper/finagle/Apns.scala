package com.hopper.finagle

import com.hopper.finagle.apns._
import com.twitter.conversions.time._
import com.twitter.util.Duration

object Apns {

  def newRichClient(
      env: ApnsEnvironment,
      bufferSize: Int = 100,
      tcpConnectTimeout: Duration = 5.seconds
  ): apns.Client = {
    val pushClient     = new ApnsPushClient(env, bufferSize, tcpConnectTimeout)
    val feedbackClient = new ApnsFeedbackClient(env, tcpConnectTimeout)
    new apns.Client(pushClient.rejectionOffer, pushClient.newClient(), feedbackClient.newClient())
  }

}
