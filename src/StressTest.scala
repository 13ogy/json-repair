import io.circe._
import io.circe.parser._
import io.circe.syntax._
import scala.io.Source
import scala.sys.process._
import scala.util.{Try, Random}
import java.io.File

// Résultat pour un schéma individuel
case class SchemaResult(
  file:       String,   // nom du fichier du schéma
  generated:  Boolean,  // true si la génération a réussi
  mutated:    Boolean,  // true si la mutation a produit une instance invalide
  repaired:   Boolean,  // true si la réparation a réussi (instance valide après)
  error:      String    // message d'erreur si échec
)

// Résultats agrégés pour une collection
case class CollectionResult(
  name:       String,
  total:      Int,
  generated:  Int,
  mutated:    Int,
  repaired:   Int,
  failed:     List[String]  // noms des schémas qui ont échoué
)

// Génère une instance valide pour un schéma donné via hypothesis-jsonschema
// Timeout de 15 secondes pour éviter les blocages sur schémas complexes
def generateInstance(schema: Json): Option[Json] =
  val cleanSchema = schema.hcursor.downField("$schema").delete.top.getOrElse(schema)
  try
    val process = Runtime.getRuntime.exec(
      Array(".venv/bin/python3", "scripts/generate.py", cleanSchema.noSpaces)
    )
    val finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
    if !finished then
      process.destroyForcibly()
      return None
    if process.exitValue() != 0 then return None
    val output = new String(process.getInputStream.readAllBytes()).trim
    parse(output).toOption
  catch
    case _: Exception => None

// Mute une instance valide pour la rendre invalide
// Timeout de 10 secondes
def mutateInstance(instance: Json, schema: Json): Option[Json] =
  val cleanSchema = schema.hcursor.downField("$schema").delete.top.getOrElse(schema)
  try
    val process = Runtime.getRuntime.exec(
      Array(".venv/bin/python3", "scripts/mutate.py", instance.noSpaces, cleanSchema.noSpaces)
    )
    val finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
    if !finished then
      process.destroyForcibly()
      return None
    if process.exitValue() != 0 then return None
    val output = new String(process.getInputStream.readAllBytes()).trim
    parse(output).toOption
  catch
    case _: Exception => None

// Teste un seul schéma : générer → muter → réparer → valider
// Timeout global de 30 secondes pour tout le pipeline
def testSchema(schemaFile: File): SchemaResult =
  val name = schemaFile.getName
  import scala.concurrent.{Future, Await}
  import scala.concurrent.duration._
  import scala.concurrent.ExecutionContext.Implicits.global
  try
    Await.result(Future(testSchemaInner(schemaFile)), 60.seconds)
  catch
    case _: java.util.concurrent.TimeoutException =>
      SchemaResult(name, false, false, false, "Timeout (60s)")
    case e: Exception =>
      SchemaResult(name, false, false, false, s"Exception: ${e.getMessage.take(50)}")

def testSchemaInner(schemaFile: File): SchemaResult =
  val name = schemaFile.getName
  val content = Source.fromFile(schemaFile).mkString
  val schema = parse(content) match
    case Right(s) => s
    case Left(_)  => return SchemaResult(name, false, false, false, "Parse error du schéma")

  // Étape 1 : générer une instance valide
  val instance = generateInstance(schema) match
    case Some(i) => i
    case None    => return SchemaResult(name, false, false, false, "Génération échouée")

  // Vérifier que l'instance générée est bien valide
  val genResult = validate(instance, schema)
  if !genResult.valid then
    return SchemaResult(name, false, false, false, "Instance générée non valide")

  // Étape 2 : muter l'instance pour la rendre invalide (3 tentatives)
  var mutated = Json.Null
  var mutResult = ValidationResult(valid = true, errors = List.empty)
  var attempts = 0
  while mutResult.valid && attempts < 3 do
    mutateInstance(instance, schema) match
      case Some(m) =>
        mutated = m
        mutResult = validate(mutated, schema)
      case None => ()
    attempts += 1

  if mutResult.valid then
    return SchemaResult(name, true, false, false, "Instance mutée toujours valide (3 tentatives)")

  // Étape 3 : réparer l'instance mutée
  val repairResult = repairAll(mutated, schema, mutResult)
  repairResult match
    case Right(repairedInstance) =>
      // Étape 4 : vérifier que l'instance réparée est valide
      val finalResult = validate(repairedInstance, schema)
      if finalResult.valid then
        SchemaResult(name, true, true, true, "")
      else
        SchemaResult(name, true, true, false,
          s"Réparation incomplète: ${finalResult.errors.size} erreurs restantes")
    case Left(err) =>
      SchemaResult(name, true, true, false, err)

// Lance le stress test sur une collection avec un échantillon de taille N
def runCollection(collectionDir: File, sampleSize: Int): CollectionResult =
  val name = collectionDir.getName
  val allFiles = collectionDir.listFiles().filter(_.getName.endsWith(".json")).toList
  val sample = if sampleSize >= allFiles.size then allFiles
               else Random.shuffle(allFiles).take(sampleSize)

  println(s"\n  Collection : $name (${sample.size}/${allFiles.size} schémas)")
  println(s"  ${"─" * 50}")

  var generated = 0
  var mutated = 0
  var repaired = 0
  var failed: List[String] = Nil

  for (file, idx) <- sample.zipWithIndex do
    print(s"    [${idx+1}/${sample.size}] ${file.getName.take(40).padTo(40, ' ')} ")

    val result = testSchema(file)

    if result.generated then generated += 1
    if result.mutated then mutated += 1
    if result.repaired then
      repaired += 1
      println("✓")
    else
      failed = result.error :: failed
      if !result.generated then println(s"⊘ gen: ${result.error.take(50)}")
      else if !result.mutated then println(s"⊘ mut: ${result.error.take(50)}")
      else println(s"✗ ${result.error.take(50)}")

  CollectionResult(name, sample.size, generated, mutated, repaired, failed.reverse)

@main def stressTest(args: String*): Unit =
  val sampleSize = args.headOption.flatMap(_.toIntOption).getOrElse(20)

  println("╔══════════════════════════════════════════════════════════════╗")
  println("║     Stress Test — Réparation sur schémas réels             ║")
  println("║     Source : jsonschemabench (guidance-ai)                  ║")
  println(s"║     Échantillon : $sampleSize schémas par collection              ║")
  println("╚══════════════════════════════════════════════════════════════╝")

  val benchDir = new File("data/jsonschemabench/data")
  if !benchDir.exists() then
    println("ERREUR: data/jsonschemabench/data/ introuvable. Initialiser le submodule git.")
    return

  val collections = benchDir.listFiles()
    .filter(_.isDirectory)
    .sortBy(_.getName)

  var results: List[CollectionResult] = Nil

  for collection <- collections do
    val result = runCollection(collection, sampleSize)
    results = result :: results

  results = results.reverse

  // Affichage du résumé
  println("\n" + "═" * 80)
  println("  RÉSUMÉ PAR COLLECTION")
  println("═" * 80)
  println(f"  ${"Collection"}%-20s ${"Total"}%6s ${"Générés"}%8s ${"Mutés"}%8s ${"Réparés"}%8s ${"Taux"}%8s")
  println("  " + "─" * 70)

  var totalAll = 0; var genAll = 0; var mutAll = 0; var repAll = 0

  // Écriture CSV des résultats
  val csvFile = new java.io.PrintWriter("data/stress_test_results.csv")
  csvFile.println("collection,total,generated,mutated,repaired,rate_percent")

  for r <- results do
    val rateNum = if r.mutated > 0 then (r.repaired * 100.0 / r.mutated).toInt else -1
    val rate = if rateNum >= 0 then s"${rateNum}%" else "N/A"
    val line = s"  ${r.name.padTo(20, ' ')} ${r.total.toString.reverse.padTo(6, ' ').reverse} ${r.generated.toString.reverse.padTo(8, ' ').reverse} ${r.mutated.toString.reverse.padTo(8, ' ').reverse} ${r.repaired.toString.reverse.padTo(8, ' ').reverse} ${rate.reverse.padTo(8, ' ').reverse}"
    println(line)
    csvFile.println(s"${r.name},${r.total},${r.generated},${r.mutated},${r.repaired},${if rateNum >= 0 then rateNum else ""}")
    totalAll += r.total; genAll += r.generated; mutAll += r.mutated; repAll += r.repaired

  println("  " + "─" * 70)
  val totalRateNum = if mutAll > 0 then (repAll * 100.0 / mutAll).toInt else -1
  val totalRate = if totalRateNum >= 0 then s"${totalRateNum}%" else "N/A"
  val totalLine = s"  ${"TOTAL".padTo(20, ' ')} ${totalAll.toString.reverse.padTo(6, ' ').reverse} ${genAll.toString.reverse.padTo(8, ' ').reverse} ${mutAll.toString.reverse.padTo(8, ' ').reverse} ${repAll.toString.reverse.padTo(8, ' ').reverse} ${totalRate.reverse.padTo(8, ' ').reverse}"
  println(totalLine)
  csvFile.println(s"TOTAL,${totalAll},${genAll},${mutAll},${repAll},${if totalRateNum >= 0 then totalRateNum else ""}")
  csvFile.close()
  println(s"\n  Résultats CSV écrits dans data/stress_test_results.csv")

  // Affichage des échecs détaillés
  val allFailed = results.filter(_.failed.nonEmpty)
  if allFailed.nonEmpty then
    println("\n" + "═" * 80)
    println("  CATÉGORIES AVEC DIFFICULTÉS")
    println("═" * 80)
    for r <- allFailed do
      println(s"\n  ${r.name} (${r.failed.size} échecs sur ${r.mutated} mutés) :")
      for err <- r.failed.take(10) do
        println(s"    → ${err.take(70)}")
      if r.failed.size > 10 then
        println(s"    ... et ${r.failed.size - 10} autres")
