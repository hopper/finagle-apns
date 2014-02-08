package com.hopper.finagle

import java.util.concurrent.atomic.AtomicInteger

package object apns {

  private[this] val seq = new AtomicInteger(0)
  
  private[apns] val Sequence = Iterator.continually { seq.getAndIncrement() }

}