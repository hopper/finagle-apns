package com.hopper.finagle

import java.util.concurrent.atomic.AtomicInteger

package object apns {
  
  // A notification with its sequential ID
  type SeqNotification = (Int, Notification)

  // A rejection code with the notification ID that caused it
  type SeqRejection = (Int, RejectionCode)

}
