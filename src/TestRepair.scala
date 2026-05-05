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

// Résultat fin pour un cas de test individuel (utilisé pour l'export CSV des distances).
case class TestCaseResult(
  category:   String,
  groupDesc:  String,
  testDesc:   String,
  status:     String,  // "repaired" ou "failed"
  distance:   Int      // -1 si non réparé
)

// Exécute tous les cas de test d'un fichier et retourne les résultats agrégés
// ainsi que la liste détaillée des résultats par cas de test (pour export CSV).
def runFile(file: File): (CategoryResult, List[TestCaseResult]) =
  val name    = file.getName.stripSuffix(".json")
  val content = Source.fromFile(file).mkString
  val groups  = parse(content).flatMap(_.as[Vector[Json]]).getOrElse(Vector.empty)

  var total    = 0
  var repaired = 0
  var distances: List[Int] = Nil
  var caseResults: List[TestCaseResult] = Nil

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
              caseResults = TestCaseResult(name, groupDesc, desc, "repaired", distance) :: caseResults
              println(s"  ✓ [$name] $groupDesc > $desc (distance=$distance)")
            else
              caseResults = TestCaseResult(name, groupDesc, desc, "failed", -1) :: caseResults
              println(s"  ✗ [$name] $groupDesc > $desc (réparation invalide, erreurs restantes: ${checkResult.errors.map(_.error).mkString("; ")})")
          case Left(err) =>
            caseResults = TestCaseResult(name, groupDesc, desc, "failed", -1) :: caseResults
            println(s"  ✗ [$name] $groupDesc > $desc (échec: $err)")

  (CategoryResult(name, total, repaired, distances.reverse), caseResults.reverse)

@main def testRepair(): Unit =

  val testDir = new File("data/test_cases")
  val files   = testDir.listFiles().filter(_.getName.endsWith(".json")).sortBy(_.getName).toList

  if files.isEmpty then
    println("Aucun fichier de test trouvé dans data/test_cases/")
    System.exit(1)

  println("=" * 60)
  println("Harnais de test — réparation JSON")
  println("=" * 60)

  val pairs = files.map(runFile)
  val results = pairs.map(_._1)
  val allCaseResults = pairs.flatMap(_._2)

  // Résumé par catégorie
  println()
  println("=" * 60)
  println("Résumé par catégorie")
  println("=" * 60)

  var totalAll    = 0
  var repairedAll = 0
  var allDistances: List[Int] = Nil

  // Écriture CSV des résultats agrégés par catégorie
  val summaryCsv = new java.io.PrintWriter("data/manual_tests_summary.csv")
  summaryCsv.println("category,total,repaired,rate_percent,dist_avg,dist_max")

  for r <- results do
    val rate = if r.total > 0 then (r.repaired * 100.0 / r.total).toInt else 0
    val avgDist = if r.distances.nonEmpty then
      f"${r.distances.sum.toDouble / r.distances.size}%.2f"
    else "0"
    val maxDist = if r.distances.nonEmpty then r.distances.max.toString else "0"
    println(f"  ${r.name}%-30s  ${r.repaired}%3d/${r.total}%3d  ($rate%3d%%)  dist moy=$avgDist  dist max=$maxDist")
    summaryCsv.println(s"${r.name},${r.total},${r.repaired},$rate,$avgDist,$maxDist")
    totalAll    += r.total
    repairedAll += r.repaired
    allDistances = allDistances ++ r.distances

  summaryCsv.close()

  // Écriture CSV des distances par instance (pour histogramme)
  val distCsv = new java.io.PrintWriter("data/manual_tests_distances.csv")
  distCsv.println("category,group_desc,test_desc,status,distance")
  for c <- allCaseResults do
    // Échapper les virgules dans les descriptions
    val gd = c.groupDesc.replace(",", ";").replace("\"", "'")
    val td = c.testDesc.replace(",", ";").replace("\"", "'")
    distCsv.println(s"${c.category},${gd},${td},${c.status},${c.distance}")
  distCsv.close()

  // Résumé global
  println()
  println("=" * 60)
  val globalRate = if totalAll > 0 then (repairedAll * 100.0 / totalAll).toInt else 0
  val globalAvg  = if allDistances.nonEmpty then f"${allDistances.sum.toDouble / allDistances.size}%.2f" else "N/A"
  val globalMax  = if allDistances.nonEmpty then allDistances.max.toString else "N/A"
  println(f"  TOTAL                           $repairedAll%3d/$totalAll%3d  ($globalRate%3d%%)  dist moy=$globalAvg  dist max=$globalMax")
  println("=" * 60)
  println(s"\n  Résultats CSV écrits dans data/manual_tests_summary.csv et data/manual_tests_distances.csv")
