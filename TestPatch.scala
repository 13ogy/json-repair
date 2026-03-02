//> using scala "3.3.1"
//> using dep "org.gnieh::diffson-circe:4.1.1"
//> using dep "io.circe::circe-parser:0.14.6"
//> using dep "io.circe::circe-core:0.14.6"
//> using dep "org.typelevel::cats-core:2.10.0"

import diffson._
import diffson.circe._
import diffson.jsonmergepatch._

import io.circe.parser._
import io.circe.syntax._

@main def run() = {
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

  println(patch.asJson.spaces2)  
}