import diffson.circe._
import diffson.jsonpatch.lcsdiff._
import diffson.lcs._
import diffson.diff
import io.circe._
import io.circe.parser._
import io.circe.syntax._
import cats.implicits._
import scala.io.Source
import java.io.File

// Résultats agrégés pour une catégorie de tests (un fichier JSON).
case class CategoryResult(
  name:       String,  // nom du fichier de test (sans extension)
  total:      Int,     // nombre total de cas testés
  repaired:   Int,     // nombre de cas réparés avec succès (instance valide après réparation)
  distances:  List[Int] // distances Diffson entre instance originale et instance réparée
)

// Exécute tous les cas de test d'un fichier et retourne les résultats agrégés.
// Chaque fichier contient un tableau de groupes, chaque groupe ayant un schéma et des tests.
def runFile(file: File): CategoryResult =
  val name    = file.getName.stripSuffix(".json")
  val content = Source.fromFile(file).mkString
  val groups  = parse(content).flatMap(_.as[Vector[Json]]).getOrElse(Vector.empty)

  var total    = 0
  var repaired = 0
  var distances: List[Int] = Nil

  given lcs: Lcs[Json] = new Patience[Json]

  for group <- groups do
    val schema = group.hcursor.downField("schema").as[Json].getOrElse(Json.Null)
    val tests  = group.hcursor.downField("tests").as[Vector[Json]].getOrElse(Vector.empty)
    val groupDesc = group.hcursor.downField("description").as[String].getOrElse("?")

    for test <- tests do
      val desc    = test.hcursor.downField("description").as[String].getOrElse("?")
      val data    = test.hcursor.downField("data").as[Json].getOrElse(Json.Null)
      val isValid = test.hcursor.downField("valid").as[Boolean].getOrElse(false)

      // On ne teste que les instances marquées invalides
      if !isValid then
        total += 1
        val validationResult = validate(data, schema)

        val result = repairAll(data, schema, validationResult)

        result match
          case Right(fixed) =>
            // Vérification que l'instance réparée est bien valide
            val checkResult = validate(fixed, schema)
            if checkResult.valid then
              repaired += 1
              // Calcul de la distance Diffson entre original et réparé
              val patch    = diff(data, fixed)
              val distance = patch.ops.size
              distances = distance :: distances
              println(s"  ✓ [$name] $groupDesc > $desc (distance=$distance)")
            else
              println(s"  ✗ [$name] $groupDesc > $desc (réparation invalide, erreurs restantes: ${checkResult.errors.map(_.error).mkString("; ")})")
          case Left(err) =>
            println(s"  ✗ [$name] $groupDesc > $desc (échec: $err)")

  CategoryResult(name, total, repaired, distances.reverse)

@main def testRepair(): Unit =

  val testDir = new File("data/test_cases")
  val files   = testDir.listFiles().filter(_.getName.endsWith(".json")).sortBy(_.getName).toList

  if files.isEmpty then
    println("Aucun fichier de test trouvé dans data/test_cases/")
    System.exit(1)

  println("=" * 60)
  println("Harnais de test — réparation JSON")
  println("=" * 60)

  val results = files.map(runFile)

  // Résumé par catégorie
  println()
  println("=" * 60)
  println("Résumé par catégorie")
  println("=" * 60)

  var totalAll    = 0
  var repairedAll = 0
  var allDistances: List[Int] = Nil

  for r <- results do
    val rate = if r.total > 0 then (r.repaired * 100.0 / r.total).toInt else 0
    val avgDist = if r.distances.nonEmpty then
      f"${r.distances.sum.toDouble / r.distances.size}%.1f"
    else "N/A"
    val maxDist = if r.distances.nonEmpty then r.distances.max.toString else "N/A"
    println(f"  ${r.name}%-30s  ${r.repaired}%3d/${r.total}%3d  ($rate%3d%%)  dist moy=$avgDist  dist max=$maxDist")
    totalAll    += r.total
    repairedAll += r.repaired
    allDistances = allDistances ++ r.distances

  // Résumé global
  println()
  println("=" * 60)
  val globalRate = if totalAll > 0 then (repairedAll * 100.0 / totalAll).toInt else 0
  val globalAvg  = if allDistances.nonEmpty then f"${allDistances.sum.toDouble / allDistances.size}%.1f" else "N/A"
  val globalMax  = if allDistances.nonEmpty then allDistances.max.toString else "N/A"
  println(f"  TOTAL                           $repairedAll%3d/$totalAll%3d  ($globalRate%3d%%)  dist moy=$globalAvg  dist max=$globalMax")
  println("=" * 60)
