package com.hopper.finagle

import com.hopper.finagle.apns._

object Apns {

  def newRichClient(env: ApnsEnvironment): apns.Client = {
    val pushClient = new ApnsPushClient(env)
    val feedbackClient = new ApnsFeedbackClient(env)
    new apns.Client(pushClient.rejectionOffer, pushClient.newClient(), feedbackClient.newClient())
  }

}
