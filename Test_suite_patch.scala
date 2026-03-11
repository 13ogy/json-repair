//> using scala "3.3.1"
//> using dep "org.gnieh::diffson-circe:4.1.1"
//> using dep "io.circe::circe-parser:0.14.6"
//> using dep "io.circe::circe-core:0.14.6"
//> using dep "org.typelevel::cats-core:2.10.0"

import diffson.circe._
import diffson.jsonpatch._
import diffson.jsonpatch.lcsdiff._
import diffson.jsonpatch.simplediff.JsonDiffDiff
import diffson.lcs._ 
import diffson.diff 
import io.circe._
import io.circe.parser._
import cats.implicits._
import scala.util.Try
import diffson.circe.given
import io.circe.syntax._
import cats.implicits._
import scala.io.Source

@main def run() = {

	val jsonString = Source.fromFile("test_suite/minimum.json").mkString
	val json = parse(jsonString).toOption.get

	val groups = json.asArray.get

	for group <- groups do {

		val tests = group.hcursor.downField("tests").focus.get.asArray.get

		val validTests =
		tests.filter(t => t.hcursor.downField("valid").as[Boolean].getOrElse(false))

		val invalidTests =
		tests.filter(t => !t.hcursor.downField("valid").as[Boolean].getOrElse(false))

		for v <- validTests do{
			for iv <- invalidTests do {

			val dataV = v.hcursor.downField("data").focus.get
			val dataIV = iv.hcursor.downField("data").focus.get

			val patch = diff(dataV, dataIV)
			val distance = patch.ops.size

			println(s"valid data = ${v.hcursor.downField("description").as[String].toOption.getOrElse("")}")
			println(s"invalid data = ${iv.hcursor.downField("description").as[String].toOption.getOrElse("")}")
			println(s"distance valid-invalid = $distance")
			}
		}
	}
}