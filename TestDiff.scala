//> using scala "3.3.1"
//> using dep "org.gnieh::diffson-circe:4.1.1"
//> using dep "io.circe::circe-parser:0.14.6"
//> using dep "io.circe::circe-core:0.14.6"
//> using dep "org.typelevel::cats-core:2.10.0"

import diffson.circe._
import diffson.jsonpatch._
import diffson.jsonpatch.lcsdiff._
import diffson.lcs._ 
import diffson.diff 
import io.circe._
import io.circe.parser._
import cats.implicits._
import scala.util.Try
import diffson.circe.given
import io.circe.syntax._
import cats.implicits._

@main def run() = {

  given lcs: Lcs[Json] = new Patience[Json]

  val json1 = parse("""{
    "title": "Star Wars - A New Hope",
    "running time": 125,
    "cast": {
      "Han": "Ford",
      "Leia": "Fisher"
    }
  }""").toOption.get

  val json2 = parse("""{
    "cast": [
      "Ford",
      "Fisher"
    ],
    "running time": 125,
    "name": "Star Wars -A New Hope"
  }""").toOption.get

  val patch = diff(json1, json2)

  val json2R = patch[Try](json1)

  println(patch.asJson.spaces2)
}