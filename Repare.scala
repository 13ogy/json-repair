//> using scala "3.3.1"
//> using dep "org.gnieh::diffson-circe:4.1.1"
//> using dep "io.circe::circe-parser:0.14.6"
//> using dep "io.circe::circe-core:0.14.6"
//> using dep "org.typelevel::cats-core:2.10.0"
//> using dep "io.circe::circe-generic:0.14.6"

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
import scala.sys.process._
import io.circe.generic.auto._  

def validate(data: Json, schema: Json): ValidationResult =
  // strip $schema key from schema before passing to Python
  val cleanSchema = schema.hcursor
    .downField("$schema")
    .delete
    .top
    .getOrElse(schema)

  val output = try //appelle le script python
    Seq("python3", "validate.py", cleanSchema.noSpaces, data.noSpaces).!!
  catch
    case e: RuntimeException =>
      println(s"Python validator failed: ${e.getMessage}")
      return ValidationResult(valid = true, errors = List.empty)

  parse(output).flatMap(_.as[ValidationResult]) match
    case Right(result) => result
    case Left(err)     =>
      println(s"Failed to parse output: $err")
      ValidationResult(valid = true, errors = List.empty)
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


def defaultValue(jsonType: String): Json = jsonType match {
    case "string"  => "".asJson
    case "integer" => 0.asJson
    case "number"  => 0.0.asJson
    case "boolean" => false.asJson
    case "array"   => Json.arr()
    case "object"  => Json.obj()
    case _         => Json.Null
  }

def partialPriority(jsonType: String): Int = jsonType match {
  case "boolean" => 0
  case "integer" => 0
  case "number"  => 0
  case "string"  => 0
  case "array"   => 1
  case "object"  => 2
  case _         => Int.MaxValue
}

def stringToBool(v: String): Json = v.toLowerCase() match {
  case "ok"|"yes"|"oui"|"true"|"vrai" => true.asJson
  case "no"|"non"|"false"|"faux" => false.asJson
  case _ => defaultValue("boolean")
}

def stringToInt(v: String, errorNode: ACursor): Json =
  val min = errorNode.downField("minimum").as[Int].getOrElse(Int.MinValue)
  val max = errorNode.downField("maximum").as[Int].getOrElse(Int.MaxValue)
  Try(v.toInt).orElse(Try(v.toDouble.toInt)).toOption match
    case Some(i) => i.max(min).min(max).asJson
    case None    => defaultValue("integer")

def stringToDouble(v: String, errorNode: ACursor): Json = 
  val min = errorNode.downField("minimum").as[Double].getOrElse(Double.MinValue)
  val max = errorNode.downField("maximum").as[Double].getOrElse(Double.MaxValue)
  Try(v.toDouble).toOption match
    case Some(d) => d.max(min).min(max).asJson
    case None    => defaultValue("number")


def stringToArray(v: String, errorNode: ACursor): Json =
  val minItems = errorNode.downField("minItems").as[Int].getOrElse(0)
  val baseItem = Json.fromString(v) 
  val padding  = List.fill(math.max(0, minItems - 1))(Json.Null) 
  Json.arr((baseItem :: padding)*)


//verifier que l'erreur n'est pas un noeud interne de l'arbre
def isLeafError(error: ValidationError): Boolean =
  error.keywordLocation.split("/").last != "properties" 

// navigue le schema pour qu'on pointe sur le bon champs
def navigateSchema(schema: Json, path: List[String]): ACursor =
  path.foldLeft(schema.hcursor: ACursor) { (cur, key) =>
    cur.downField("properties").downField(key)
  }

// navigue le json pour pointer sur le champs où se trouve l'erreur
def navigateData(cur: ACursor, path: List[String]): ACursor =
  path.foldLeft(cur) { (c, key) => c.downField(key) }


def repairOne(data: Json, schema: Json, error: ValidationError): Json =
  // definir Repair(J, S) by structral inductions. Start with string, number, object, arrays assertions. Assumption for objects: not editing the labels.
  
  //pour chaque ValidationError, on la repare en utilisant les informations de l'erreur et le schéma
  val path = error.instanceLocation.split("/").toList.tail
  val keyword = error.keywordLocation.split("/").last
  val errorNode = navigateSchema (schema, path)

  keyword match
    case "type" =>
      val t = errorNode.downField("type").as[String].getOrElse("")
      val currentValue = navigateData(data.hcursor, path).focus
      val repairedValue = (t, currentValue.flatMap(_.asString)) match
        case ("boolean", Some(s)) => stringToBool(s)
        case ("integer", Some(s)) => stringToInt(s, errorNode)
        case ("number",  Some(s)) => stringToDouble(s, errorNode)
        case ("array", Some(s)) => stringToArray(s, errorNode)
        case _  => defaultValue(t)
      navigateData(data.hcursor, path).withFocus(_ => repairedValue).top.getOrElse(data)

    case "minimum" =>
      val min: Json = errorNode.downField("minimum").as[Int] match
        case Right(i) => i.asJson
        case Left(_)  => errorNode.downField("minimum").as[Double].getOrElse(0.0).asJson
      navigateData(data.hcursor, path).withFocus(_ => min.asJson).top.getOrElse(data)

    case "maximum" =>
      val max: Json = errorNode.downField("maximum").as[Int] match
        case Right(i) => i.asJson
        case Left(_)  => errorNode.downField("maximum").as[Double].getOrElse(0.0).asJson
      navigateData(data.hcursor, path).withFocus(_ => max.asJson).top.getOrElse(data)

    // multiple of 
    case "multipleOf" =>
      val multipleOf = errorNode.downField("multipleOf").as[Int] match
        case Right(i) => i.toDouble
        case Left(_)  => errorNode.downField("multipleOf").as[Double].getOrElse(1.0)
      
      val min     = errorNode.downField("minimum").as[Double].getOrElse(Double.MinValue)
      val max     = errorNode.downField("maximum").as[Double].getOrElse(Double.MaxValue)
      val current = navigateData(data.hcursor, path).focus
        .flatMap(_.as[Double].toOption).getOrElse(min)

      val below      = math.floor(current / multipleOf) * multipleOf
      val above      = below + multipleOf
      val candidates = List(below, above).filter(v => v >= min && v <= max)

      // si il y a des candidats valides, alors on ne peut pas réparer
      val repairedJson = candidates
        .minByOption(v => math.abs(v - current))
        .map(repaired =>
          if repaired == repaired.toLong then repaired.toLong.asJson
          else repaired.asJson
        )
        .getOrElse(Json.Null)  // impossible to repair

      navigateData(data.hcursor, path).withFocus(_ => repairedJson).top.getOrElse(data)

    case "minLength" =>
      val minLength = errorNode.downField("minLength").as[Int].getOrElse(0)
      val current   = navigateData(data.hcursor, path).focus
        .flatMap(_.asString).getOrElse("")
      val padded    = current.padTo(minLength, 'x')  // pad with 'x' to meet minLength
      navigateData(data.hcursor, path).withFocus(_ => padded.asJson).top.getOrElse(data)

    case "maxLength" =>
      val maxLength = errorNode.downField("maxLength").as[Int].getOrElse(0)
      val current   = navigateData(data.hcursor, path).focus
        .flatMap(_.asString).getOrElse("")
      navigateData(data.hcursor, path)
        .withFocus(_ => current.take(maxLength).asJson).top.getOrElse(data)

    //besoin de generateur aléatoire
    case "required" =>
      val missingField = error.error.stripPrefix("The object is missing required properties [\'").takeWhile(_ != '\'')
      val t = errorNode.downField("properties").downField(missingField).downField("type").as[String].getOrElse("")
      navigateData(data.hcursor, path).withFocus(_.mapObject(_.add(missingField, defaultValue(t)))).top.getOrElse(data)

    case _ => data

def repair(data: Json, schema: Json, result: ValidationResult): Json =
  result.errors.filter(isLeafError).foldLeft(data) { (current, error) => repairOne(current, schema, error)}


def repairAll(data: Json, schema: Json, result: ValidationResult, maxIterations: Int = 10): Either[String, Json] =
  var instance = data
  var errors = result.errors.filter(isLeafError)
  var iterations = 0

  while (errors.nonEmpty && iterations < maxIterations) {
    // on prend la premiere erreur
    val error = errors.head 
    val repaired = repairOne(instance, schema, error)

    // si la réparation a changé l'instance, on revalide pour voir les nouvelles erreurs
    if repaired != instance then
      instance = repaired
      val newResult = validate(instance, schema)
      errors = newResult.errors.filter(isLeafError)
    else
      // si rien n'a changé, elle n'est pas réparable, on passe à l'erreur suivante
      errors = errors.tail
    iterations += 1
  }

  if (errors.isEmpty) {
    println(s"Successfully repaired the JSON in $iterations iterations.")
    Right(instance)
  } else {
    Left("Could not fully repair the JSON, remaining errors: " + errors.map(_.error).mkString(", "))
  }


@main def test(): Unit =

  // le schema
  val schemaStr = """
  {
    "required": ["active"],
    "properties": {
      "user": {
        "properties": {
          "id":     { "type": "integer", "minimum": 7, "multipleOf": 3 },
          "emails": { "type": "array" ,"items": { "type": "string", "format": "email" }}
        }
      },
      "active": { "type": "boolean" }  
    }
  }
  """

  // le json cassé
  val dataStr = """
  {
    "user": {
      "id": 1,
      "emails": "alice@example.com"
    },
    "active": "yes"  
  }
  """

  val schema = parse(schemaStr).getOrElse(Json.Null)
  val data   = parse(dataStr).getOrElse(Json.Null)


  println("avant réparation:")
  println(data.spaces2)

  val result   = validate(data, schema)   // calls Python
  //val repaired = repair(data, schema, result)
  val repaired = repairAll(data, schema, result) match
    case Right(repaired) => 
      println("\naprès réparation:")
      println(repaired.spaces2)
    case Left(err)   => println(s"\nréparation échouée: $err")


  

  
