package com.hopper.finagle

import java.util.concurrent.atomic.AtomicInteger

package object apns {
  
  // A notification with its sequential ID
  type SeqNotification = (Int, Notification)

  private[this] val seq = new AtomicInteger(0)
  
  private[apns] val Sequence = Iterator.continually { seq.getAndIncrement() }

}