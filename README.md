Finagle APNS
===

An implementation of Apple Push Notification Service on Finagle.

## Getting it

It is currently not available in Maven Central, so you'll have to compile it and deploy it locally:
```
# git clone https://github.com/Hopper/finagle-apns
# cd finagle-apns
# git checkout v6.18-1
# sbt +publish-local
```

Then add it as a dependency to your project

```libraryDependencies += "com.hopper" %% "finagle-apns" % "6.18-1"```

## Using it

```scala
import com.hopper.finagle._
import com.hopper.finagle.apns._

// Load the KeyStore for Apple's Sandbox environment
val keystore: java.security.KeyStore = ???
val password: Array[Char] = ???

// Create your client
val apns = ApnsRichClient.newRichClient(ApnsEnvironment.Sandbox(keystore, password))

// Obtain a device token to send a notification to
val deviceToken: Array[Byte] = ???

// Create a notification
val notification = Notification(deviceToken, Payload(alert = Some(SimpleAlert("Hi!"))))

// Send the notification
apns(notification)
  .onSuccess { _ =>
    println("Notification was sent to Apple... Maybe.")
    println("We can only guarantee that we wrote the notification to your network interface.")
  }
```

### Handling Rejected Notifications

When APNS rejects one of your notifications, it sends a message asynchronously. This is represented as an ```Offer[Rejection]```. Here's how to use it:

```scala
[...]
val apns = ApnsRichClient.newRichClient(ApnsEnvironment.Sandbox(keystore, password))

val rejections: Offer[Rejection] = apns.rejectedNotifications

rejections.foreach { case Rejection(reason, rejected, resent) =>
  println("The following notification was rejected (%s):" format reason)
  println(rejected)

  println("The rejection forced the client to resend the following notifications")
  println(resent.mkString(","))
}
```

As few notes:

* When APNS rejects one of your notifications, it closes the connection and will *not deliver* any subsequent notifications sent on that connection. It is up to your process to resend any subsequent notifications. Currently, this client will handle that for you (I may reconsider that decision, so beware).
* In order to provide the rejected notification and to be able to re-send subsequent notifications, the client needs to hold on to the last few notifications you send in memory. This buffer's size is configurable.
* The consequence is that the client may not be able to provide you with the rejected notification and it may also not be able to resend all notifications that might be necessary. You'll need to adjust the buffer size with the rate at which you're sending notifications.


