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

//validate renvoie un objet ValidationResult qui contient un booleen 'valid' et une liste d'erreurs de validation.
//une erreur de validation est des informations sur l'emplacement de l'instance, l'emplacement du mot-clé et le message d'erreur.
//repair utilise ces informations pour corriger les données en remplaçant les valeurs invalides par des valeurs par défaut basées sur le schéma.

case class ValidationResult(
  valid: Boolean,
  errors: List[ValidationError]
)

case class ValidationError(
  instanceLocation: String,
  keywordLocation: String,
  error: String
)

private def defaultValue(jsonType: String): Json = jsonType match {
    case "string"  => "".asJson
    case "integer" => 0.asJson
    case "number"  => 0.0.asJson
    case "boolean" => false.asJson
    case "array"   => Json.arr()
    case "object"  => Json.obj()
    case _         => Json.Null
  }


def repairType(data: Json, schema: Json, error: ValidationError): Json =
  // definir Repair(J, S) by structral inductions. Start with string, number, object, arrays assertions. Assumption for objects: not editing the labels.
  
  //pour chaque ValidationError, on la repare en utilisant les informations de l'erreur et le schéma
  val path = error.instanceLocation.split("/").toList.tail
  val keyword = error.keywordLocation.split("/").toList.tail.last
  val jsonType = schema.hcursor.downField("properties").downField(path.head).downField("type").as[String].getOrElse("")
  val defaultVal = defaultValue(jsonType)

  if (keyword == "type") {
    // erreur de type, on remplace la valeur par la valeur par défaut basée sur le schéma
    data.hcursor.downField(path.head).withFocus(_ => defaultVal).top.get
  }
  if(keyword == "required" ) {
    // il manque une propriété requise, on l'ajoute avec la valeur par défaut basée sur le schéma}
    val missingProperty = error.error.split("'")(1) // extraire le nom de la propriété manquante
    val missingPropertyType = schema.hcursor.downField("properties").downField(missingProperty).downField("type").as[String].getOrElse("")
    val missingPropertyDefaultVal = defaultValue(missingPropertyType)
    data.hcursor.downField(path.head).withFocus(_.mapObject(_.add(missingProperty, missingPropertyDefaultVal))).top.get
  }
  if(keyword == "minimum"){
    // la valeur est inférieure au minimum, on la remplace par le minimum
    val minimum = schema.hcursor.downField("properties").downField(path.head).downField("minimum").as[Double].getOrElse(0.0).asJson
    data.hcursor.downField(path.head).withFocus(_ => minimum).top.get
  }
  if(keyword == "maximum"){
    // la valeur est supérieure au maximum, on la remplace par le maximum
    val maximum = schema.hcursor.downField("properties").downField(path.head).downField("maximum").as[Double].getOrElse(0.0).asJson
    data.hcursor.downField(path.head).withFocus(_ => maximum).top.get
  }

