package com.hopper.finagle.apns
package protocol

import org.scalatest.WordSpec

class CodecSpec extends WordSpec {
  
  import Codec._
  
  "A Payload JSON encoder" when {
    "empty" should {
      "be empty" in {
        assert(Json.encode(Payload()) === None)
      }
    }

    "has a simple alert" should {
      "have a simple alert" in {
        assert(Json.encode(Payload(alert = Some(SimpleAlert("hi")))) === Some("""{"aps":{"alert":"hi"}}"""))
      }
    }

    "has quotes" should {
      "espace them" in {
        assert(Json.encode(Payload(alert = Some(SimpleAlert("""hi "bob"""")))) === Some("""{"aps":{"alert":"hi \"bob\""}}"""))
      }
    }

    "has content-available" should {
      "include content-available as an int" in {
        assert(Json.encode(Payload(contentAvailable = true)) === Some("""{"aps":{"content-available":1}}"""))
      }
    }

    "has a rich alert" should {
      "encode all members" in {
        assert(Json.encode(
          Payload(alert = Some(
            RichAlert(body = "body", actionLocKey = Some("actionLocKey"), locKey = Some("locKey"), locArgs = Seq("a", "b"), launchImage = Some("image"))
          ))) === Some("""{"aps":{"alert":{"body":"body","loc-args":["a","b"],"launch-image":"image","action-loc-key":"actionLocKey","loc-ley":"locKey"}}}"""))
      }
    }

    "has a custom key-value map" should {
      "encode it as json" in {
        assert(
          Json.encode(Payload(custom = Map(
            "foo" -> "bar",
            "quotes" -> "\"\"",
            "baz" -> 123,
            "foobar" -> 123.4,
            "food" -> true,
            "more" -> Map("a" -> "b", "foo" -> Seq("bar", 123, Map("c" -> false)))
          ))) === Some("""{"quotes":"\"\"","food":true,"more":{"a":"b","foo":["bar",123,{"c":false}]},"baz":123,"foobar":123.4,"foo":"bar"}""")
        )
      }
    }
  }

}
