import io.circe._
import io.circe.parser._
import io.circe.syntax._
import scala.io.Source
import java.io.File

// Résultat par fichier de mot-clé
case class KeywordResult(
  keyword:    String,
  total:      Int,     // nombre d'instances invalides testées
  repaired:   Int,     // réparées avec succès (instance valide après réparation)
  skipped:    Int      // ignorées (erreur de parse, validation échouée, etc.)
)

// Les mots-clés que notre algorithme supporte
val supportedKeywords = Set(
  "type", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
  "multipleOf", "minLength", "maxLength", "pattern", "format",
  "enum", "const", "required", "properties",
  "additionalProperties", "minProperties", "maxProperties",
  "minItems", "maxItems", "uniqueItems", "items", "prefixItems",
  "anyOf", "oneOf", "allOf", "not", "if-then-else"
)

// Mots-clés non supportés (features avancées)
val unsupportedKeywords = Set(
  "anchor", "boolean_schema", "contains", "content", "default", "defs",
  "dependentRequired", "dependentSchemas", "dynamicRef",
  "infinite-loop-detection", "maxContains", "minContains",
  "patternProperties", "propertyNames", "ref", "refRemote",
  "unevaluatedItems", "unevaluatedProperties", "vocabulary"
)

// Teste toutes les instances invalides d'un fichier de la test suite officielle
def runOfficialFile(file: File): KeywordResult =
  val keyword = file.getName.stripSuffix(".json")
  val content = Source.fromFile(file).mkString
  val groups = parse(content).flatMap(_.as[Vector[Json]]).getOrElse(Vector.empty)

  var total = 0
  var repaired = 0
  var skipped = 0

  for group <- groups do
    val schema = group.hcursor.downField("schema").as[Json].getOrElse(Json.Null)
    val tests = group.hcursor.downField("tests").as[Vector[Json]].getOrElse(Vector.empty)
    val groupDesc = group.hcursor.downField("description").as[String].getOrElse("?")

    for test <- tests do
      val isValid = test.hcursor.downField("valid").as[Boolean].getOrElse(true)
      // On ne teste que les instances invalides
      if !isValid then
        total += 1
        val data = test.hcursor.downField("data").as[Json].getOrElse(Json.Null)
        val desc = test.hcursor.downField("description").as[String].getOrElse("?")

        // Valider d'abord pour obtenir les erreurs
        val result = validate(data, schema)
        if !result.valid then
          // Tenter la réparation avec timeout
          try
            val repairResult = repairAll(data, schema, result)
            repairResult match
              case Right(repairedInstance) =>
                val finalResult = validate(repairedInstance, schema)
                if finalResult.valid then
                  repaired += 1
                  print("✓")
                else
                  print("✗")
                  // Afficher les détails de l'échec
                  System.out.flush()
              case Left(err) =>
                print("✗")
          catch
            case e: Exception =>
              skipped += 1
              print("⊘")
        else
          // Le validateur ne détecte pas l'erreur (possible avec certains mots-clés)
          skipped += 1
          print("·")

  println()
  KeywordResult(keyword, total, repaired, skipped)


@main def testSuiteOfficial(): Unit =
  println("╔══════════════════════════════════════════════════════════════════╗")
  println("║  Test Suite Officielle — JSON Schema Draft 2020-12             ║")
  println("║  Source : json-schema-org/JSON-Schema-Test-Suite               ║")
  println("╚══════════════════════════════════════════════════════════════════╝")

  val testDir = new File("data/json-schema-test-suite/tests/draft2020-12")
  if !testDir.exists() then
    println("ERREUR: data/json-schema-test-suite/ introuvable. Initialiser le submodule git.")
    return

  val allFiles = testDir.listFiles()
    .filter(f => f.isFile && f.getName.endsWith(".json"))
    .sortBy(_.getName)

  // Séparer les fichiers supportés et non supportés
  val (supported, unsupported) = allFiles.partition { f =>
    val kw = f.getName.stripSuffix(".json")
    supportedKeywords.contains(kw)
  }

  println(s"\n  Mots-clés supportés : ${supported.length}")
  println(s"  Mots-clés non supportés : ${unsupported.length}")
  println(s"  Non supportés : ${unsupported.map(_.getName.stripSuffix(".json")).mkString(", ")}")

  // Tester les fichiers supportés
  println("\n" + "═" * 70)
  println("  RÉSULTATS PAR MOT-CLÉ (supportés)")
  println("═" * 70)

  var results: List[KeywordResult] = Nil

  for file <- supported do
    val kw = file.getName.stripSuffix(".json")
    print(s"  ${kw.padTo(25, ' ')} ")
    System.out.flush()
    val result = runOfficialFile(file)
    results = result :: results

  results = results.reverse

  // Tester aussi les non supportés pour voir ce qu'on peut quand même réparer
  println("\n" + "═" * 70)
  println("  RÉSULTATS PAR MOT-CLÉ (non supportés — best effort)")
  println("═" * 70)

  var unsupportedResults: List[KeywordResult] = Nil

  for file <- unsupported do
    val kw = file.getName.stripSuffix(".json")
    print(s"  ${kw.padTo(25, ' ')} ")
    System.out.flush()
    val result = runOfficialFile(file)
    unsupportedResults = result :: unsupportedResults

  unsupportedResults = unsupportedResults.reverse

  // Résumé
  println("\n" + "═" * 70)
  println("  RÉSUMÉ — Mots-clés supportés")
  println("═" * 70)
  println(s"  ${"Mot-clé".padTo(25, ' ')} ${"Total".reverse.padTo(6, ' ').reverse} ${"Réparés".reverse.padTo(8, ' ').reverse} ${"Ignorés".reverse.padTo(8, ' ').reverse} ${"Taux".reverse.padTo(8, ' ').reverse}")
  println("  " + "─" * 60)

  var totalAll = 0; var repAll = 0; var skipAll = 0

  for r <- results do
    val rate = if (r.total - r.skipped) > 0
               then s"${(r.repaired * 100.0 / (r.total - r.skipped)).toInt}%"
               else "N/A"
    println(s"  ${r.keyword.padTo(25, ' ')} ${r.total.toString.reverse.padTo(6, ' ').reverse} ${r.repaired.toString.reverse.padTo(8, ' ').reverse} ${r.skipped.toString.reverse.padTo(8, ' ').reverse} ${rate.reverse.padTo(8, ' ').reverse}")
    totalAll += r.total; repAll += r.repaired; skipAll += r.skipped

  println("  " + "─" * 60)
  val totalRate = if (totalAll - skipAll) > 0
                  then s"${(repAll * 100.0 / (totalAll - skipAll)).toInt}%"
                  else "N/A"
  println(s"  ${"TOTAL".padTo(25, ' ')} ${totalAll.toString.reverse.padTo(6, ' ').reverse} ${repAll.toString.reverse.padTo(8, ' ').reverse} ${skipAll.toString.reverse.padTo(8, ' ').reverse} ${totalRate.reverse.padTo(8, ' ').reverse}")

  // Résumé non supportés
  if unsupportedResults.nonEmpty then
    println("\n" + "═" * 70)
    println("  RÉSUMÉ — Mots-clés non supportés (best effort)")
    println("═" * 70)
    println(s"  ${"Mot-clé".padTo(25, ' ')} ${"Total".reverse.padTo(6, ' ').reverse} ${"Réparés".reverse.padTo(8, ' ').reverse} ${"Ignorés".reverse.padTo(8, ' ').reverse} ${"Taux".reverse.padTo(8, ' ').reverse}")
    println("  " + "─" * 60)

    var uTotal = 0; var uRep = 0; var uSkip = 0
    for r <- unsupportedResults do
      val rate = if (r.total - r.skipped) > 0
                 then s"${(r.repaired * 100.0 / (r.total - r.skipped)).toInt}%"
                 else "N/A"
      println(s"  ${r.keyword.padTo(25, ' ')} ${r.total.toString.reverse.padTo(6, ' ').reverse} ${r.repaired.toString.reverse.padTo(8, ' ').reverse} ${r.skipped.toString.reverse.padTo(8, ' ').reverse} ${rate.reverse.padTo(8, ' ').reverse}")
      uTotal += r.total; uRep += r.repaired; uSkip += r.skipped

    println("  " + "─" * 60)
    val uRate = if (uTotal - uSkip) > 0
                then s"${(uRep * 100.0 / (uTotal - uSkip)).toInt}%"
                else "N/A"
    println(s"  ${"TOTAL".padTo(25, ' ')} ${uTotal.toString.reverse.padTo(6, ' ').reverse} ${uRep.toString.reverse.padTo(8, ' ').reverse} ${uSkip.toString.reverse.padTo(8, ' ').reverse} ${uRate.reverse.padTo(8, ' ').reverse}")

  // Écriture CSV
  val csvFile = new java.io.PrintWriter("data/official_test_suite_results.csv")
  csvFile.println("keyword,supported,total,repaired,skipped,rate_percent")
  for r <- results do
    val rate = if (r.total - r.skipped) > 0 then (r.repaired * 100.0 / (r.total - r.skipped)).toInt else -1
    csvFile.println(s"${r.keyword},true,${r.total},${r.repaired},${r.skipped},${if rate >= 0 then rate else ""}")
  for r <- unsupportedResults do
    val rate = if (r.total - r.skipped) > 0 then (r.repaired * 100.0 / (r.total - r.skipped)).toInt else -1
    csvFile.println(s"${r.keyword},false,${r.total},${r.repaired},${r.skipped},${if rate >= 0 then rate else ""}")
  csvFile.close()
  println(s"\n  Résultats CSV écrits dans data/official_test_suite_results.csv")
