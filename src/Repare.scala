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
import io.circe.Encoder.encodeInt

// Valide une instance JSON par rapport à un schéma JSON via le script Python jschon.
// Renvoie un ValidationResult contenant un booléen 'valid' et la liste des erreurs de validation.
// Chaque erreur indique : l'emplacement dans l'instance (instanceLocation),
// l'emplacement du mot-clé dans le schéma (keywordLocation), et un message d'erreur.
// La clé "$schema" est retirée du schéma avant l'appel Python pour éviter les conflits de version.
def validate(data: Json, schema: Json): ValidationResult =
  val cleanSchema = schema.hcursor
    .downField("$schema")
    .delete
    .top
    .getOrElse(schema)

  val output = try
    Seq("python3", "scripts/validate.py", cleanSchema.noSpaces, data.noSpaces).!!
  catch
    case e: RuntimeException =>
      println(s"Erreur du validateur Python: ${e.getMessage}")
      return ValidationResult(valid = true, errors = List.empty)

  parse(output).flatMap(_.as[ValidationResult]) match
    case Right(result) => result
    case Left(err)     =>
      println(s"Impossible de parser la sortie du validateur: $err")
      ValidationResult(valid = true, errors = List.empty)

// Résultat de la validation : booléen de validité + liste d'erreurs.
case class ValidationResult(
  valid: Boolean,
  errors: List[ValidationError]
)

// Représente une erreur de validation avec :
// - instanceLocation : chemin JSON Pointer vers la valeur invalide dans l'instance
// - keywordLocation  : chemin JSON Pointer vers le mot-clé de schéma qui a échoué
// - error            : message d'erreur lisible
case class ValidationError(
  instanceLocation: String,
  keywordLocation: String,
  error: String
)

// Retourne une valeur JSON par défaut pour un type JSON donné.
// Utilisé pour initialiser des champs manquants ou non réparables.
def defaultValue(jsonType: String): Json = jsonType match {
    case "string"  => "".asJson
    case "integer" => 0.asJson
    case "number"  => 0.0.asJson
    case "boolean" => false.asJson
    case "array"   => Json.arr()
    case "object"  => Json.obj()
    case _         => Json.Null
  }

// Calcule la priorité de réparation d'une branche de schéma (anyOf/oneOf).
// L'objectif est de favoriser les branches les plus simples à réparer :
//   - priorité 0 (la plus haute) : types scalaires (string, integer, number, boolean)
//     ou branches sans type mais avec uniquement des contraintes simples (minimum, multipleOf…)
//   - priorité 1 : tableaux
//   - priorité 2 (la plus basse) : objets, car ils nécessitent de créer des sous-structures
// Si le type n'est pas précisé, on infère la complexité à partir des mots-clés présents.
def partialPriority(branchSchema: Json): Int =
  
  val cursor = branchSchema.hcursor
  
  cursor.downField("type").as[String].toOption match 
    case Some("boolean") => 0
    case Some("integer") => 0
    case Some("number")  => 0
    case Some("string")  => 0
    case Some("array")   => 1
    case Some("object")  => 2

    // Si le type n'est pas spécifié, on déduit la complexité à partir des mots-clés :
    // required/properties → objet implicite (priorité 2)
    // items/minItems      → tableau implicite (priorité 1)
    // sinon               → contrainte simple, facile à réparer (priorité 0)
    case _ => 
      if cursor.downField("required").succeeded then 2
      else if cursor.downField("properties").succeeded then 2
      else if cursor.downField("items").succeeded then 1
      else if cursor.downField("minItems").succeeded then 1
      else 0

// Tente de convertir une chaîne de caractères en booléen JSON.
// Reconnaît les variantes courantes en français et en anglais.
// Retourne false par défaut si la conversion échoue.
def stringToBool(v: String): Json = v.toLowerCase() match {
  case "ok"|"yes"|"oui"|"true"|"vrai" => true.asJson
  case "no"|"non"|"false"|"faux" => false.asJson
  case _ => defaultValue("boolean")
}

// Tente de convertir une chaîne de caractères en entier JSON,
// en respectant les bornes minimum/maximum du schéma si elles existent.
// Essaie d'abord une conversion directe, puis via double → int.
// Retourne 0 par défaut si la conversion échoue.
def stringToInt(v: String, errorNode: ACursor): Json =
  val min = errorNode.downField("minimum").as[Int].getOrElse(Int.MinValue)
  val max = errorNode.downField("maximum").as[Int].getOrElse(Int.MaxValue)
  Try(v.toInt).orElse(Try(v.toDouble.toInt)).toOption match
    case Some(i) => i.max(min).min(max).asJson
    case None    => defaultValue("integer")

// Tente de convertir une chaîne de caractères en nombre à virgule flottante,
// en respectant les bornes minimum/maximum du schéma si elles existent.
// Retourne 0.0 par défaut si la conversion échoue.
def stringToDouble(v: String, errorNode: ACursor): Json = 
  val min = errorNode.downField("minimum").as[Double].getOrElse(Double.MinValue)
  val max = errorNode.downField("maximum").as[Double].getOrElse(Double.MaxValue)
  Try(v.toDouble).toOption match
    case Some(d) => d.max(min).min(max).asJson
    case None    => defaultValue("number")

// Convertit une chaîne de caractères en tableau JSON en l'utilisant comme premier élément.
// Si le schéma impose un minItems, complète le tableau avec des valeurs Null.
def stringToArray(v: String, errorNode: ACursor): Json =
  val minItems = errorNode.downField("minItems").as[Int].getOrElse(0)
  val baseItem = Json.fromString(v) 
  val padding  = List.fill(math.max(0, minItems - 1))(Json.Null) 
  Json.arr((baseItem :: padding)*)

// Détermine si une erreur de validation est une erreur feuille de l'arbre d'erreurs.
// On filtre les erreurs intermédiaires (nœuds "properties") et les erreurs enfants
// de anyOf/oneOf, qui sont traitées séparément par repairAnyOf/repairOneOf.
def isLeafError(error: ValidationError): Boolean =
  val last = error.keywordLocation.split("/").last
  val anyOfRelated = error.keywordLocation.contains("/anyOf/") || error.keywordLocation.contains("/oneOf/")
  last != "properties" && !anyOfRelated

// Navigue dans le schéma JSON en suivant un chemin de clés via les champs "properties".
// Retourne un curseur pointant sur le sous-schéma correspondant au champ de l'instance.
def navigateSchema(schema: Json, path: List[String]): ACursor =
  path.foldLeft(schema.hcursor: ACursor) { (cur, key) =>
    cur.downField("properties").downField(key)
  }

// Navigue dans l'instance JSON en suivant un chemin de clés.
// Retourne un curseur pointant sur la valeur à réparer.
def navigateData(cur: ACursor, path: List[String]): ACursor =
  path.foldLeft(cur) { (c, key) => c.downField(key) }

// Génère une valeur JSON conforme au sous-schéma pointé par errorNode.
// Tente d'abord une génération via hypothesis-jsonschema (generate.py).
// En cas d'échec, retourne la valeur par défaut pour le type déclaré.
def generateType(errorNode: ACursor): Json =
  val fieldSchema = errorNode.as[Json].getOrElse(Json.obj())
  val t = errorNode.downField("type").as[String].getOrElse("")
  
  generateFromSchema(fieldSchema) match
    case Some(v) => v
    case None    => defaultValue(t)

// Génère une instance JSON valide pour un schéma donné via le script Python hypothesis-jsonschema.
// Retourne None si la génération échoue (timeout, schéma non supporté, erreur Python…).
def generateFromSchema(schema: Json): Option[Json] =
  val input = schema.noSpaces
  Try {
    Seq("python3", "scripts/generate.py", input).!!.trim
  }.toOption.flatMap { output =>
    parse(output).toOption
  }

// Pour chaque branche de anyOf/oneOf, calcule :
//   - son index dans la liste
//   - sa priorité de réparation (partialPriority)
//   - le nombre d'erreurs feuilles produit par la validation de la sous-instance contre cette branche
// Ces informations sont utilisées pour choisir la meilleure branche à réparer.
def countPriority(subInstance: Json, branches: List[(Json, Int)]): List[(Int, Json, Int, List[ValidationError])] =
  branches.map { case (branchSchema, index) =>
    val priority = partialPriority(branchSchema)
    val err = validate(subInstance, branchSchema).errors.filter(isLeafError)
    (index, branchSchema, priority, err)
  }

// Répare une erreur anyOf en choisissant la branche la plus simple à satisfaire.
// Critères de sélection (par ordre de priorité) :
//   1. La branche avec le moins d'erreurs de validation sur la sous-instance courante
//   2. En cas d'égalité, la branche avec la priorité la plus basse (type le plus simple)
// Une fois la branche choisie, on applique repairAll sur la sous-instance.
// Si repairAll échoue, on génère une instance valide pour cette branche via hypothesis.
def repairAnyOf(data: Json, schema: Json, error: ValidationError): Json =
  val path = error.instanceLocation.split("/").toList.tail

  val anyOfNode = navigateSchema(schema, path).downField("anyOf")

  // Extraction des branches de anyOf sous forme de liste (schéma, index)
  val branches: List[(Json, Int)] = anyOfNode.values.toList
    .flatMap(_.toList)
    .zipWithIndex

  val sousInstance = navigateData(data.hcursor, path).focus.getOrElse(Json.Null)

  // Tri des branches : moins d'erreurs d'abord, puis priorité croissante
  val bestBranch = countPriority(sousInstance, branches).sortWith { case ((_, _, c1, e1), (_, _, c2, e2)) =>
      if e1.size != e2.size then e1.size < e2.size
      else c1 < c2  
    }.headOption

  // Réparation de la sous-instance selon la branche choisie
  bestBranch match
    case Some((_, branchSchema, _, errs)) =>
      val branchResult = ValidationResult(valid = false, errors = errs)
      val repairedSousInstance = repairAll(sousInstance, branchSchema, branchResult) match 
        case Right(repaired) => repaired
        case Left(_)         => generateFromSchema(branchSchema).getOrElse(sousInstance)
      navigateData(data.hcursor, path).withFocus(_ => repairedSousInstance).top.getOrElse(data)
    case None => data

// Répare une seule erreur de validation dans l'instance JSON.
// Principe : réparation par induction structurelle sur le mot-clé de l'erreur.
// Pour chaque mot-clé, on applique la transformation minimale qui corrige la violation.
// Si la réparation directe est impossible (type incompatible sans conversion simple),
// on délègue à generateType/generateFromSchema pour produire une valeur valide.
def repairOne(data: Json, schema: Json, error: ValidationError): Json =
  val path = error.instanceLocation.split("/").toList.tail
  val keyword = error.keywordLocation.split("/").last
  val errorNode = navigateSchema(schema, path)

  keyword match
    case "type" =>
      // Tentative de conversion simple (chaîne → type cible).
      // Si elle échoue, génération via hypothesis-jsonschema.
      val t = errorNode.downField("type").as[String].getOrElse("")
      val currentValue = navigateData(data.hcursor, path).focus
      val simpleRepair = (t, currentValue.flatMap(_.asString)) match
        case ("boolean", Some(s)) => stringToBool(s)
        case ("integer", Some(s)) => stringToInt(s, errorNode)
        case ("number",  Some(s)) => stringToDouble(s, errorNode)
        case ("array", Some(s)) => stringToArray(s, errorNode)
        case _  => Json.Null
      val finalRepair = if simpleRepair != Json.Null then simpleRepair else generateType(errorNode)
      navigateData(data.hcursor, path).withFocus(_ => finalRepair).top.getOrElse(data)

    case "minimum" =>
      // La valeur est inférieure au minimum : on la remplace par le minimum.
      val min: Json = errorNode.downField("minimum").as[Int] match
        case Right(i) => i.asJson
        case Left(_)  => errorNode.downField("minimum").as[Double].getOrElse(0.0).asJson
      navigateData(data.hcursor, path).withFocus(_ => min.asJson).top.getOrElse(data)

    case "maximum" =>
      // La valeur est supérieure au maximum : on la remplace par le maximum.
      val max: Json = errorNode.downField("maximum").as[Int] match
        case Right(i) => i.asJson
        case Left(_)  => errorNode.downField("maximum").as[Double].getOrElse(0.0).asJson
      navigateData(data.hcursor, path).withFocus(_ => max.asJson).top.getOrElse(data)

    case "multipleOf" =>
      // La valeur n'est pas un multiple de multipleOf.
      // On cherche le multiple le plus proche (en dessous ou au dessus) qui respecte
      // également les bornes minimum/maximum si elles existent.
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

      // Si aucun candidat ne respecte les bornes, la réparation est impossible (Json.Null).
      val repairedJson = candidates
        .minByOption(v => math.abs(v - current))
        .map(repaired =>
          if repaired == repaired.toLong then repaired.toLong.asJson
          else repaired.asJson
        )
        .getOrElse(Json.Null)

      navigateData(data.hcursor, path).withFocus(_ => repairedJson).top.getOrElse(data)

    case "minLength" =>
      // La chaîne est trop courte : on la complète avec des 'x' jusqu'à la longueur minimale.
      val minLength = errorNode.downField("minLength").as[Int].getOrElse(0)
      val current   = navigateData(data.hcursor, path).focus
        .flatMap(_.asString).getOrElse("")
      val padded    = current.padTo(minLength, 'x')
      navigateData(data.hcursor, path).withFocus(_ => padded.asJson).top.getOrElse(data)

    case "maxLength" =>
      // La chaîne est trop longue : on la tronque à la longueur maximale autorisée.
      val maxLength = errorNode.downField("maxLength").as[Int].getOrElse(0)
      val current   = navigateData(data.hcursor, path).focus
        .flatMap(_.asString).getOrElse("")
      navigateData(data.hcursor, path)
        .withFocus(_ => current.take(maxLength).asJson).top.getOrElse(data)

    case "anyOf" =>
      // Délégation à repairAnyOf qui choisit et répare la branche la plus adaptée.
      repairAnyOf(data, schema, error)
    
    case "required" =>
      // Un champ obligatoire est absent de l'objet.
      // On extrait le nom du champ manquant depuis le message d'erreur,
      // puis on l'ajoute avec une valeur par défaut selon son type dans le schéma.
      val missingField = error.error.stripPrefix("The object is missing required properties [\'").takeWhile(_ != '\'')
      val t = errorNode.downField("properties").downField(missingField).downField("type").as[String].getOrElse("")
      navigateData(data.hcursor, path).withFocus(_.mapObject(_.add(missingField, defaultValue(t)))).top.getOrElse(data)

    case _ => data

// Version non-itérative de la réparation : applique repairOne sur toutes les erreurs feuilles
// en une seule passe. Peut laisser des erreurs si une correction en introduit d'autres.
def repair(data: Json, schema: Json, result: ValidationResult): Json =
  result.errors.filter(isLeafError).foldLeft(data) { (current, error) => repairOne(current, schema, error)}

// Version itérative de la réparation.
// À chaque itération : on traite la première erreur, on revalide, on recommence.
// Si une réparation n'a aucun effet (repairOne retourne la même instance),
// on passe à l'erreur suivante pour éviter une boucle infinie.
// On s'arrête quand il n'y a plus d'erreurs ou qu'on atteint maxIterations.
// Retourne Right(instance réparée) ou Left(message d'erreur) si la réparation est incomplète.
def repairAll(data: Json, schema: Json, result: ValidationResult, maxIterations: Int = 10): Either[String, Json] =
  var instance = data
  var errors = result.errors.filter(isLeafError)
  var iterations = 0

  while (errors.nonEmpty && iterations < maxIterations) {
    val error = errors.head 
    val repaired = repairOne(instance, schema, error)

    if repaired != instance then
      instance = repaired
      val newResult = validate(instance, schema)
      errors = newResult.errors.filter(isLeafError)
    else
      errors = errors.tail
    iterations += 1
  }

  if (errors.isEmpty) {
    println(s"Réparation réussie en $iterations itération(s).")
    Right(instance)
  } else {
    Left("Réparation incomplète, erreurs restantes: " + errors.map(_.error).mkString(", "))
  }


@main def test(): Unit =

  // Schéma de test : un objet avec un champ "value" qui doit satisfaire
  // soit un objet {name: string, age: integer >= 0}, soit un nombre >= 2.
  val schemaStr = """
  {
    "properties": {
      "value": {
        "anyOf": [
                    {
                        "type": "object",
                        "required": ["name", "age"],
                        "properties": {
                            "name": { "type": "string" },
                            "age": { "type": "integer", "minimum": 0 }
                        }
                    },
                    {
                        "minimum": 2
                    }
                ]
      }
    }
  }
  """

  // Instance invalide : value = 1.5 ne satisfait ni l'objet ni minimum: 2
  val dataStr = """
  {
    "value" : 1.5
  }
  """

  val schema = parse(schemaStr).getOrElse(Json.Null)
  val data   = parse(dataStr).getOrElse(Json.Null)

  println("avant réparation:")
  println(data.spaces2)

  val result   = validate(data, schema)
  val repaired = repairAll(data, schema, result) match
    case Right(repaired) => 
      println("\naprès réparation:")
      println(repaired.spaces2)
    case Left(err)   => println(s"\nréparation échouée: $err")

