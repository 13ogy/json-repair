# json-repair

Algorithme de réparation automatique d'instances JSON invalides par rapport à un schéma JSON Schema.

---

## Table des matières

1. [Vue d'ensemble](#vue-densemble)
2. [Structure du projet](#structure-du-projet)
3. [Installation et lancement](#installation-et-lancement)
4. [Logique de réparation](#logique-de-réparation)
   - [Détection des erreurs](#1-détection-des-erreurs)
   - [Filtrage des erreurs feuilles](#2-filtrage-des-erreurs-feuilles)
   - [Réparation itérative](#3-réparation-itérative)
   - [Réparation d'un mot-clé](#4-réparation-dun-mot-clé-repairone)
   - [Gestion des combinateurs](#5-gestion-des-combinateurs-anyof--oneof--allof)
   - [Génération automatique](#6-génération-automatique-generatefromschema)
5. [Description des fichiers](#description-des-fichiers)
6. [Mots-clés supportés](#mots-clés-supportés)
7. [Analyse de complexité](#analyse-de-complexité)
8. [Jeu de tests](#jeu-de-tests)

---

## Vue d'ensemble

Ce projet implémente la fonction `Repare(J, S)` : étant donné une instance JSON `J` invalide et un schéma JSON Schema `S`, retourner une instance `J'` valide obtenue en modifiant `J` de manière **minimale**.

Le pipeline est le suivant :

```
Instance invalide J
        │
        ▼
   validate(J, S)          ← appel Python/jschon
        │
        ▼
  liste d'erreurs
        │
        ▼
  repairAll(J, S, erreurs)  ← boucle itérative Scala
        │
        ▼
  Instance réparée J'
```

La **validation** est déléguée à la bibliothèque Python `jschon` qui implémente JSON Schema draft 2020-12 et produit des erreurs structurées avec leur emplacement précis dans l'instance et dans le schéma.

La **génération** d'instances valides pour des schémas complexes est déléguée à `hypothesis-jsonschema` (Python), utilisée en dernier recours lorsqu'aucune transformation simple n'est possible.

---

## Structure du projet

```
json-repair/
├── src/
│   ├── project.scala          — directives scala-cli (dépendances, version Scala)
│   ├── Repare.scala           — algorithme principal de réparation
│   ├── TestRepair.scala       — harnais de test automatisé (tests unitaires)
│   ├── StressTest.scala       — stress test sur schémas réels (jsonschemabench)
│   ├── TestSuiteOfficial.scala — test suite officielle JSON Schema draft 2020-12
│   └── experiments/           — scripts d'exploration Diffson (non utilisés en production)
│       ├── TestDiff.scala
│       ├── TestPatch.scala
│       └── Test_suite_patch.scala
├── scripts/
│   ├── validate.py            — pont Scala → jschon (validation)
│   ├── generate.py            — pont Scala → hypothesis-jsonschema (génération)
│   ├── mutate.py              — mutateur d'instances valides → invalides
│   └── plots.py               — génère les figures du rapport (matplotlib)
├── data/
│   ├── test_cases/            — jeu de tests JSON par catégorie de mots-clés
│   │   ├── type_errors.json
│   │   ├── numeric_bounds.json
│   │   ├── string_constraints.json
│   │   ├── required_fields.json
│   │   ├── array_constraints.json
│   │   ├── object_constraints.json
│   │   ├── enum_const.json
│   │   ├── anyOf_oneOf.json
│   │   ├── allOf_not.json
│   │   ├── if_then_else.json
│   │   └── nested_complex.json
│   ├── jsonschemabench/       — submodule : 9558 schémas réels (guidance-ai)
│   ├── json-schema-test-suite/ — submodule : test suite officielle JSON Schema
│   ├── stress_test_results.csv — résultats du stress test (généré)
│   ├── official_test_suite_results.csv — résultats de la test suite officielle (généré)
│   ├── manual_tests_summary.csv — résumé du jeu de tests manuel (généré)
│   └── manual_tests_distances.csv — distances Diffson par instance (généré)
├── report/
│   ├── main.tex               — rapport LaTeX
│   └── img/                   — figures PNG du rapport
├── .venv/                     — environnement Python virtuel (ignoré par git)
├── .gitignore
├── .gitmodules                — configuration des sous-modules git
├── Dockerfile                 — image Docker reproductible
├── docker-compose.yml         — service `repair` pour lancer les harnais
└── requirements.txt           — dépendances Python
```

---

## Installation et lancement

### Option A — Reproduction avec Docker (recommandée)

Aucune installation locale de Python ou scala-cli n'est nécessaire.

```bash
# Initialiser les sous-modules de test (test suite + jsonschemabench)
git submodule update --init --recursive

# Construction de l'image (première fois uniquement)
docker compose build

# Jeu de tests manuel (84 instances)
docker compose run --rm repair scala-cli run \
    src/project.scala src/Repare.scala src/TestRepair.scala \
    --main-class testRepair

# Stress test sur jsonschemabench (N = nombre de schémas par collection)
docker compose run --rm repair scala-cli run \
    src/project.scala src/Repare.scala src/StressTest.scala \
    --main-class stressTest -- 10

# Test suite officielle JSON Schema (draft 2020-12)
docker compose run --rm repair scala-cli run \
    src/project.scala src/Repare.scala src/TestSuiteOfficial.scala \
    --main-class testSuiteOfficial

# Génération des graphes du rapport (optionnel — nécessite matplotlib)
docker compose run --rm repair .venv/bin/pip install matplotlib
docker compose run --rm repair .venv/bin/python3 scripts/plots.py
```

Les CSV de résultats sont écrits dans `data/` (monté en volume → accessibles depuis l'hôte). Les graphes PNG sont écrits dans `report/img/`.

### Option B — Installation locale

#### Prérequis

- [scala-cli](https://scala-cli.virtuslab.org/) ≥ 1.0
- Python 3.11+

#### Mise en place de l'environnement Python

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

#### Exécuter l'algorithme de réparation (exemple de test)

```bash
scala-cli run src/project.scala src/Repare.scala --main-class test
```

#### Exécuter le harnais de tests complet

```bash
scala-cli run src/project.scala src/Repare.scala src/TestRepair.scala --main-class testRepair
```

#### Exécuter le stress test sur schémas réels

```bash
# Initialiser le sous-module jsonschemabench (première fois uniquement)
git submodule update --init --recursive

# Lancer le stress test (N = nombre de schémas par collection, défaut : 20)
scala-cli run src/project.scala src/Repare.scala src/StressTest.scala --main-class stressTest -- 10
```

Les résultats sont écrits dans `data/stress_test_results.csv`.

#### Régénérer les figures du rapport

```bash
.venv/bin/pip install matplotlib
.venv/bin/python3 scripts/plots.py
```

Les figures PNG sont produites dans `report/img/`.

---

## Logique de réparation

### 1. Détection des erreurs

La validation est effectuée par `validate(data, schema)` qui appelle `scripts/validate.py` en sous-processus. Le script Python invoque `jschon` (JSON Schema draft 2020-12) et retourne sur stdout un objet JSON de la forme :

```json
{
  "valid": false,
  "errors": [
    {
      "instanceLocation": "/user/id",
      "keywordLocation": "/properties/user/properties/id/minimum",
      "error": "The value may not be less than 1"
    }
  ]
}
```

Chaque erreur contient trois informations clés :
- **`instanceLocation`** : chemin JSON Pointer vers la valeur invalide dans l'instance (ex: `/user/id`)
- **`keywordLocation`** : chemin JSON Pointer vers le mot-clé de schéma violé (ex: `.../minimum`)
- **`error`** : message lisible décrivant la violation

Le dernier segment de `keywordLocation` donne directement le **mot-clé à réparer** (ex: `minimum`, `type`, `required`).

---

### 2. Filtrage des erreurs feuilles

jschon retourne une arborescence d'erreurs qui inclut des erreurs intermédiaires (nœuds non-feuilles) comme `Properties ['x'] are invalid`. Ces erreurs ne contiennent pas d'information actionnable — elles signalent simplement qu'un sous-objet contient des erreurs, qui sont elles-mêmes détaillées en dessous.

La fonction `isLeafError` filtre ces erreurs non-actionnables :

```
Erreur filtrée si :
  - dernier segment du keywordLocation == "properties"  → erreur intermédiaire d'objet
  - keywordLocation contient "/anyOf/"                   → erreur enfant d'un anyOf
  - keywordLocation contient "/oneOf/"                   → erreur enfant d'un oneOf
  - keywordLocation contient "/allOf/"                   → erreur enfant d'un allOf
  - keywordLocation contient "/not/"                     → erreur enfant d'un not
```

Les erreurs enfants des combinateurs (`anyOf`, `oneOf`, etc.) sont volontairement filtrées car elles sont traitées globalement par leurs fonctions dédiées (`repairAnyOf`, `repairOneOf`, etc.), qui reçoivent l'erreur parente au niveau du combinateur lui-même.

**Note :** Les erreurs dans les branches `then`/`else` ne sont **pas** filtrées. Elles remontent avec leur mot-clé réel (ex: `required`) et sont interceptées dans `repairOne` par un cas spécifique qui détecte la présence de `"/then/"` ou `"/else/"` dans le `keywordLocation` et délègue à `repairIfThenElse`.

---

### 3. Réparation itérative

`repairAll(data, schema, result, maxIterations=10)` est la boucle principale :

```
entrée : instance J, schéma S, liste d'erreurs initiale
sortie : Right(J réparé) ou Left(message d'erreur)

boucle tant que (erreurs non vides ET iterations < maxIterations) :
    1. Prendre la première erreur e
    2. Appeler repairOne(J_courant, S, e) → J_nouveau
    3. Si J_nouveau ≠ J_courant :
         J_courant = J_nouveau
         re-valider J_courant → nouvelles erreurs
       Sinon :
         passer à l'erreur suivante (elle n'est pas réparable)
    4. iterations++
```

**Pourquoi la re-validation à chaque étape ?**
Réparer une erreur peut en introduire d'autres (ex: changer le type d'un champ peut violer une contrainte `minimum`), ou en résoudre plusieurs à la fois. La re-validation garantit que la liste d'erreurs est toujours à jour.

**Pourquoi `maxIterations` ?**
Sans garde-fou, une erreur non réparable pourrait créer une boucle infinie si `repairOne` retournait toujours une instance différente. La valeur par défaut de 10 est suffisante pour les schémas testés (distance max observée = 4). Les cas avec combinateurs imbriqués peuvent nécessiter plus d'itérations.

**Complexité de `repairAll` :**
Soit `E` le nombre d'erreurs initiales et `K` la constante `maxIterations`.  
Dans le pire cas : `O(K × (C_repair + C_validate))` où `C_validate` inclut l'appel Python.  
En pratique : `O(E)` itérations car chaque erreur est résolue en une passe.

---

### 4. Réparation d'un mot-clé (`repairOne`)

`repairOne(data, schema, error)` applique la transformation minimale correspondant au mot-clé violé. La navigation dans le schéma et l'instance se fait via deux fonctions auxiliaires :

- **`navigateSchema(schema, path)`** : descend dans le schéma en suivant les champs `properties` pour chaque segment du chemin. Retourne un curseur `ACursor` pointant sur le sous-schéma du champ.
- **`navigateData(cursor, path)`** : descend dans l'instance en suivant directement les clés. Retourne un curseur `ACursor` pointant sur la valeur à réparer.

La modification de la valeur utilise `.withFocus(_ => newValue).top` pour reconstruire l'instance complète avec la valeur modifiée, sans toucher aux autres champs.

**Tableau des stratégies par mot-clé :**

| Mot-clé | Stratégie de réparation |
|---|---|
| `type` | Conversion simple (chaîne→int/bool/array), sinon `generateFromSchema` |
| `minimum` | Remplacer par la valeur du minimum |
| `maximum` | Remplacer par la valeur du maximum |
| `exclusiveMinimum` | Remplacer par `exclusiveMin + 1` (int) ou `+ ε` (float) |
| `exclusiveMaximum` | Remplacer par `exclusiveMax - 1` (int) ou `- ε` (float) |
| `multipleOf` | Trouver le multiple le plus proche (floor/ceil), respecter min/max |
| `minLength` | Compléter la chaîne avec des `'x'` jusqu'à la longueur minimale |
| `maxLength` | Tronquer la chaîne à la longueur maximale |
| `pattern` | Générer via `hypothesis-jsonschema` |
| `format` | Générer via `hypothesis-jsonschema` |
| `enum` | Remplacer par la première valeur de la liste |
| `const` | Remplacer par la valeur exacte |
| `required` | Ajouter le champ manquant avec `defaultValue` ou `generateFromSchema` |
| `minItems` | Compléter le tableau avec des `null` |
| `maxItems` | Tronquer le tableau |
| `uniqueItems` | Dédupliquer en conservant la première occurrence |
| `items` | Réparer chaque élément individuellement selon le schéma `items` |
| `prefixItems` | Réparer chaque élément selon son schéma de position |
| `minProperties` | Générer et ajouter des champs via `hypothesis` |
| `maxProperties` | Supprimer les champs en excès (les derniers) |
| `additionalProperties` | Supprimer les clés absentes de `properties` |
| `anyOf` | Délégation à `repairAnyOf` |
| `oneOf` | Délégation à `repairOneOf` |
| `allOf` | Délégation à `repairAllOf` |
| `not` | Génération d'une valeur valide pour le schéma parent via `hypothesis` |
| `if`/`then`/`else` | Délégation à `repairIfThenElse` |

---

### 5. Gestion des combinateurs (`anyOf` / `oneOf` / `allOf`)

#### `anyOf` et `oneOf` — sélection de la meilleure branche

La fonction `repairAnyOf(data, schema, error)` (et son équivalent `repairOneOf`) choisit la branche la plus facile à satisfaire selon un **ordre de priorité partielle** :

**Étape 1 — Calcul du score pour chaque branche :**

Pour chaque branche du `anyOf`, on calcule deux métriques via `countPriority` :
1. **Nombre d'erreurs feuilles** produites en validant la sous-instance courante contre cette branche
2. **Priorité de complexité** calculée par `partialPriority`

**Étape 2 — `partialPriority(branchSchema)` :**

Cette fonction attribue un score de complexité à une branche :

```
Si le type est déclaré :
  string / integer / number / boolean  → priorité 0  (facile à réparer)
  array                                → priorité 1
  object                               → priorité 2  (difficile)

Si le type n'est pas déclaré, on infère depuis les mots-clés :
  "required" ou "properties" présents  → priorité 2  (objet implicite)
  "items" ou "minItems" présents        → priorité 1  (tableau implicite)
  sinon (minimum, multipleOf, etc.)    → priorité 0  (contrainte simple)
```

**Étape 3 — Tri des branches :**

```
Tri par (nombre d'erreurs croissant, puis priorité croissante)
→ on choisit la branche avec le moins d'erreurs,
  à égalité celle avec le type le plus simple
```

**Exemple :** Pour le schéma `anyOf: [{type: "object", required: ["name","age"]}, {minimum: 2}]` et l'instance `1.5` :
- Branche 1 (objet) : 3 erreurs (type, required×2), priorité 2
- Branche 2 (minimum: 2) : 1 erreur (minimum), priorité 0
→ On choisit la branche 2 → réparation : `1.5 → 2`

**Étape 4 — Réparation :**

On appelle `repairAll` sur la sous-instance avec le schéma de la branche choisie. En cas d'échec, on génère une instance via `hypothesis-jsonschema`.

#### `allOf` — toutes les branches

`repairAllOf` applique `repairAll` séquentiellement pour chaque branche du `allOf`. L'instance s'améliore progressivement à chaque branche traitée.

---

### 6. Génération automatique (`generateFromSchema`)

Lorsqu'aucune transformation simple n'est possible (type incompatible non convertible, `pattern`, `format`, schéma complexe), on appelle `scripts/generate.py` qui utilise `hypothesis-jsonschema` :

```python
result = find(from_schema(schema), lambda x: True, settings=...)
```

`hypothesis-jsonschema` génère automatiquement une instance valide pour le schéma donné, en gérant nativement `pattern`, `format`, `minimum`, etc.

**Limitation :** la génération est non-déterministe et peut être lente (sous-processus Python à chaque appel). Le premier exemple trouvé est retourné sans optimisation de la distance avec l'original.

---

## Description des fichiers

### `src/Repare.scala`

Fichier principal. Contient toute la logique de réparation en Scala 3.

| Fonction | Rôle |
|---|---|
| `validate` | Appelle `scripts/validate.py`, parse le résultat en `ValidationResult` |
| `defaultValue` | Retourne une valeur par défaut JSON pour un type donné |
| `partialPriority` | Calcule la complexité de réparation d'une branche de schéma |
| `stringToBool` | Convertit une chaîne ("oui", "true", etc.) en booléen |
| `stringToInt` | Convertit une chaîne en entier en respectant les bornes du schéma |
| `stringToDouble` | Convertit une chaîne en nombre flottant en respectant les bornes |
| `stringToArray` | Enveloppe une chaîne dans un tableau (avec padding si `minItems`) |
| `isLeafError` | Filtre les erreurs non-actionnables (intermédiaires / enfants de combinateurs) |
| `navigateSchema` | Navigue dans le schéma via les champs `properties` selon un chemin |
| `navigateData` | Navigue dans l'instance selon un chemin de clés |
| `generateType` | Génère une valeur conforme au type déclaré dans le schéma |
| `generateFromSchema` | Appelle `scripts/generate.py` via sous-processus |
| `countPriority` | Calcule priorité + erreurs pour chaque branche d'un combinateur |
| `repairAnyOf` | Répare une violation `anyOf` (meilleure branche) |
| `repairOneOf` | Répare une violation `oneOf` (même logique que `anyOf`) |
| `repairAllOf` | Répare une violation `allOf` (toutes les branches séquentiellement) |
| `repairNot` | Génère une valeur ne satisfaisant pas le schéma nié |
| `repairIfThenElse` | Évalue la condition `if`, répare selon la branche applicable |
| `repairUniqueItems` | Déduplique un tableau |
| `repairItems` | Répare chaque élément d'un tableau selon le schéma `items` |
| `repairPrefixItems` | Répare chaque élément d'un tuple selon son schéma de position |
| `repairAdditionalProperties` | Supprime les clés non autorisées d'un objet |
| `repairMinProperties` | Génère et ajoute des propriétés jusqu'au minimum requis |
| `repairMaxProperties` | Tronque les propriétés en excès |
| `repairExclusiveMin` | Ajuste la valeur à `exclusiveMin + 1` ou `+ ε` |
| `repairExclusiveMax` | Ajuste la valeur à `exclusiveMax - 1` ou `- ε` |
| `repairPatternOrFormat` | Génère une chaîne conforme via `hypothesis-jsonschema` |
| `repairEnum` | Remplace par la première valeur valide de la liste `enum` |
| `repairConst` | Remplace par la valeur exacte imposée par `const` |
| `repairOne` | Dispatch principal : route chaque mot-clé vers sa fonction de réparation |
| `repair` | Version non-itérative (passe unique) — conservée pour référence |
| `repairAll` | Version itérative avec re-validation et garde-fou `maxIterations` |

### `src/TestRepair.scala`

Harnais de test automatisé.

- Charge tous les fichiers `data/test_cases/*.json`
- Pour chaque instance invalide : appelle `repairAll`, re-valide avec `validate`, calcule la distance Diffson
- Affiche un résumé par catégorie : `réparés/total`, taux de réussite, distance moyenne et max
- Point d'entrée : `@main def testRepair()`

### `src/project.scala`

Centralise les directives scala-cli (`//> using`). Doit être passé explicitement lors de la compilation si on ne compile pas tout le répertoire `src/`.

### `scripts/validate.py`

Reçoit le schéma (argv[1]) et l'instance (argv[2]) en JSON compact.  
Invoque `jschon` (JSON Schema draft 2020-12) et retourne sur stdout :
```json
{"valid": bool, "errors": [{"instanceLocation": "...", "keywordLocation": "...", "error": "..."}]}
```

### `scripts/generate.py`

Reçoit un schéma (argv[1]) en JSON compact.  
Appelle `hypothesis_jsonschema.find()` et retourne la première instance valide trouvée sur stdout en JSON.

### `data/test_cases/*.json`

Chaque fichier suit la structure :
```json
[
  {
    "description": "Nom du groupe de tests",
    "schema": { ... },
    "tests": [
      { "description": "...", "data": { ... }, "valid": false }
    ]
  }
]
```

Les instances marquées `"valid": false` sont les cas de test. Les instances `"valid": true` (non présentes ici, mais supportées) seraient ignorées par le harnais.

### `src/experiments/`

Scripts d'exploration des approches Diffson (distance d'édition entre instances JSON). Non utilisés dans le pipeline de réparation. Conservés pour référence historique. Ne pas compiler avec les fichiers principaux (conflits de nom `@main def run`).

### `src/StressTest.scala`

Stress test sur des schémas réels issus de [jsonschemabench](https://github.com/guidance-ai/jsonschemabench) (9558 schémas, 10 collections).

Pour chaque schéma de l'échantillon :
1. Générer une instance valide via `hypothesis-jsonschema` (timeout 15s)
2. Muter l'instance via `scripts/mutate.py` pour la rendre invalide (3 tentatives)
3. Réparer l'instance mutée avec `repairAll`
4. Valider le résultat

Résultats écrits en CSV dans `data/stress_test_results.csv`. Timeout global de 60s par schéma.

### `scripts/mutate.py`

Mutateur d'instances JSON valides. Applique 1 à 3 mutations ciblées (changement de type, violation de bornes, suppression de champs requis, etc.) avec un fallback agressif si les mutations ciblées ne suffisent pas.

---

## Mots-clés supportés

### Contraintes de valeur
`type`, `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`, `multipleOf`, `minLength`, `maxLength`, `pattern`, `format`, `enum`, `const`

### Contraintes de structure
`required`, `properties`, `additionalProperties`, `minProperties`, `maxProperties`

### Contraintes de tableau
`minItems`, `maxItems`, `uniqueItems`, `items`, `prefixItems`

### Combinateurs logiques
`anyOf`, `oneOf`, `allOf`, `not`, `if`/`then`/`else`

---

## Analyse de complexité

### Notations

- `E` : nombre d'erreurs de validation dans l'instance initiale
- `K` : nombre maximal d'itérations (`maxIterations = 10`)
- `B` : nombre de branches d'un combinateur (`anyOf`, `oneOf`, `allOf`)
- `N` : nombre d'éléments dans un tableau ou d'une instance
- `C_val` : coût d'un appel à `validate` (appel Python + jschon)
- `C_gen` : coût d'un appel à `generateFromSchema` (appel Python + hypothesis)

### `validate` — O(C_val)

Chaque appel lance un sous-processus Python. Le coût est dominé par le démarrage de l'interpréteur Python et la traversal jschon du schéma : **O(|schema| × |instance|)** en pratique, mais considéré constant `C_val` ici.

### `repairOne` — O(|path| + C_keyword)

La navigation dans le schéma et l'instance est **O(|path|)** où `|path|` est la profondeur de l'instance. Le coût de réparation dépend du mot-clé :

| Cas | Complexité |
|---|---|
| `minimum`, `maximum`, `const`, `enum`, `minLength`, `maxLength`, `exclusiveMin/Max` | O(1) |
| `multipleOf` | O(1) |
| `uniqueItems` | O(N) — parcours du tableau pour déduplique |
| `items` | O(N × C_val) — validation + réparation de chaque élément |
| `prefixItems` | O(N × C_val) — idem par position |
| `additionalProperties` | O(|properties|) — filtrage des clés |
| `type`, `pattern`, `format`, `not`, `minProperties` | O(C_gen) — génération hypothesis |
| `anyOf`, `oneOf` | O(B × C_val + C_repair) — validation sur chaque branche + réparation |
| `allOf` | O(B × (C_val + C_repair)) — réparation séquentielle sur chaque branche |

### `repairAll` — O(K × (C_repair + C_val))

La boucle principale effectue au plus `K` itérations. Chaque itération comprend une réparation et une re-validation.

**Cas favorable :** chaque réparation résout exactement une erreur → `O(E × C_val)` si `E < K`.  
**Pire cas :** `O(K × (C_gen + C_val))` si chaque itération nécessite une génération.

### Récursion (combinateurs imbriqués)

`repairAll` et `repairAnyOf`/`repairAllOf` sont mutuellement récursifs. La profondeur maximale de récursion est bornée par la profondeur d'imbrication des combinateurs dans le schéma. En pratique, les schémas JSON Schema réels ont rarement plus de 3-4 niveaux d'imbrication, ce qui maintient la complexité pratique raisonnable.

**Complexité totale dans le pire cas :** O(K^D × B^D × C_val) où D est la profondeur d'imbrication des combinateurs — exponentiel théoriquement, mais `maxIterations` et la sélection de la meilleure branche limitent l'exploration en pratique.

---

## Jeu de tests

Résultats obtenus avec `src/TestRepair.scala` :

| Catégorie | Tests | Réussis | Taux | Dist. moy. | Dist. max |
|---|---|---|---|---|---|
| `type_errors` | 8 | 8 | 100% | 1.0 | 1 |
| `numeric_bounds` | 13 | 13 | 100% | 1.0 | 1 |
| `string_constraints` | 9 | 9 | 100% | 0.8 | 1 |
| `required_fields` | 8 | 8 | 100% | 1.6 | 3 |
| `array_constraints` | 12 | 12 | 100% | 0.9 | 2 |
| `object_constraints` | 4 | 4 | 100% | 0.8 | 2 |
| `enum_const` | 6 | 6 | 100% | 1.0 | 1 |
| `anyOf_oneOf` | 6 | 6 | 100% | 0.7 | 1 |
| `allOf_not` | 6 | 6 | 100% | 1.0 | 1 |
| `if_then_else` | 3 | 3 | 100% | 1.3 | 2 |
| `nested_complex` | 9 | 9 | 100% | 1.6 | 4 |
| **TOTAL** | **84** | **84** | **100%** | **1.1** | **4** |

La distance Diffson est le nombre d'opérations (ajouts, suppressions, remplacements) dans le patch JSON nécessaire pour transformer l'instance originale en instance réparée — c'est la mesure de la **minimalité des modifications**.

---

## Stress test — Schémas réels

Test de l'algorithme de réparation sur des schémas réels issus de [jsonschemabench](https://github.com/guidance-ai/jsonschemabench) (guidance-ai). Protocole : pour chaque schéma, on génère une instance valide via `hypothesis-jsonschema`, on la mute pour introduire des erreurs, puis on lance la réparation.

**Approche : mutation** — les instances valides sont corrompues par `scripts/mutate.py` qui applique des changements de type, violations de bornes, suppressions de champs requis, etc.

| Collection | Total | Générés | Mutés | Réparés | Taux |
|---|---|---|---|---|---|
| Github_easy | 10 | 10 | 6 | 6 | 100% |
| Github_hard | 10 | 5 | 2 | 2 | 100% |
| Github_medium | 10 | 10 | 10 | 10 | 100% |
| Github_trivial | 10 | 10 | 7 | 7 | 100% |
| Github_ultra | 10 | 3 | 2 | 2 | 100% |
| Glaiveai2K | 10 | 10 | 10 | 10 | 100% |
| JsonSchemaStore | 10 | 6 | 3 | 3 | 100% |
| Kubernetes | 10 | 10 | 1 | 1 | 100% |
| Snowplow | 10 | 10 | 10 | 10 | 100% |
| WashingtonPost | 10 | 9 | 5 | 5 | 100% |
| **TOTAL** | **100** | **83** | **56** | **56** | **100%** |

**Observations :**

Toutes les instances invalides obtenues après mutation sont réparées (56/56). En revanche, le pipeline laisse échapper la majorité des schémas en amont. Sur les 100 schémas tirés au sort, 17 ne donnent lieu à aucune génération — `hypothesis-jsonschema` n'arrive pas à produire une instance valide, le plus souvent à cause de `$ref` récursifs, de `patternProperties`, ou de schémas très volumineux (collections `Github_ultra` et `Github_hard`). Sur les 83 instances effectivement générées, 27 résistent à la mutation : certains schémas comme ceux de Kubernetes sont si permissifs (extensions `x-kubernetes-*`) que les mutations ciblées ne produisent pas d'instance invalide.

Au final, les collections `Glaiveai2K`, `Snowplow` et `Github_medium` traversent l'ensemble du pipeline, tandis que `Github_ultra` (70% d'échec en génération) et `Kubernetes` (90% d'échec en mutation) sont les plus difficiles.

---

## Test Suite Officielle JSON Schema (draft 2020-12)

### Méthodologie

Nous évaluons notre algorithme de réparation contre la [JSON Schema Test Suite](https://github.com/json-schema-org/JSON-Schema-Test-Suite) officielle (draft 2020-12). Pour chaque fichier de test :

1. On charge le schéma et les instances marquées comme **invalides**
2. On applique `repairAll(instance, schéma)`
3. On valide le résultat avec `validate()`
4. On mesure le taux de réparation par mot-clé

### Exécution

```bash
scala-cli run src/project.scala src/Repare.scala src/TestSuiteOfficial.scala --main-class testSuiteOfficial
```

Les résultats sont écrits dans `data/official_test_suite_results.csv`.

### Résultats — Mots-clés supportés (77% global — 220/284)

| Mot-clé | Total | Réparés | Taux |
|---|---|---|---|
| `type` | 59 | 59 | **100%** |
| `const` | 32 | 32 | **100%** |
| `additionalProperties` | 9 | 9 | **100%** |
| `minimum` | 3 | 3 | **100%** |
| `maximum` | 2 | 2 | **100%** |
| `exclusiveMinimum` | 2 | 2 | **100%** |
| `exclusiveMaximum` | 2 | 2 | **100%** |
| `maxItems` | 2 | 2 | **100%** |
| `maxLength` | 2 | 2 | **100%** |
| `maxProperties` | 3 | 3 | **100%** |
| `minItems` | 2 | 2 | **100%** |
| `minProperties` | 2 | 2 | **100%** |
| `pattern` | 2 | 1 | **100%** (1 ignoré) |
| `uniqueItems` | 19 | 17 | 89% |
| `anyOf` | 6 | 5 | 83% |
| `required` | 6 | 5 | 83% |
| `enum` | 29 | 23 | 79% |
| `multipleOf` | 4 | 3 | 75% |
| `if-then-else` | 10 | 7 | 70% |
| `minLength` | 3 | 2 | 66% |
| `properties` | 12 | 8 | 66% |
| `allOf` | 20 | 12 | 60% |
| `prefixItems` | 2 | 1 | 50% |
| `items` | 12 | 5 | 41% |
| `oneOf` | 15 | 6 | 40% |
| `not` | 24 | 5 | 20% |

### Résultats — Mots-clés non supportés (13% global — 30/248)

| Mot-clé | Total | Réparés | Taux |
|---|---|---|---|
| `anchor` | 4 | 3 | 75% |
| `vocabulary` | 2 | 1 | 50% |
| `$ref` | 42 | 16 | 38% |
| `$dynamicRef` | 22 | 3 | 23% |
| `dependentSchemas` | 10 | 2 | 20% |
| `contains` | 10 | 2 | 20% |
| `patternProperties` | 10 | 1 | 10% |
| `unevaluatedItems` | 29 | 1 | 3% |
| `dependentRequired` | 6 | 0 | 0% |
| `maxContains` | 7 | 0 | 0% |
| `minContains` | 14 | 0 | 0% |
| `propertyNames` | 5 | 0 | N/A (ignorés) |
| `unevaluatedProperties` | 60 | 0 | 0% |

### Analyse des limites par mot-clé

#### `not` — 20% (5/24)

19 cas sur 24 sont des schémas de la forme `{"not": {}}` ou `{"not": true}` qui interdisent toute valeur : aucune instance JSON ne peut les satisfaire, c'est une impossibilité théorique. Les 5 cas réparés correspondent à des négations ciblées (`not: {type: "integer"}`, `not: {enum: [...]}`) où l'algorithme parvient à changer le type ou la valeur incriminée.

#### `oneOf` — 40% (6/15)

Deux familles d'échecs distinctes. Six instances satisfont simultanément deux branches du `oneOf` : `repairOneOf` ne sait pas invalider une branche tout en gardant l'autre valide, ce qui demanderait une stratégie de modification inverse. Trois autres correspondent à des schémas où aucune branche n'est satisfaite — soit notre heuristique de sélection par complexité choisit une mauvaise branche, soit toutes les branches sont des schémas booléens (`oneOf: [true, true, true]`) sans aucun mot-clé sur lequel agir.

#### `allOf` — 60% (12/20)

Sept des huit échecs combinent `allOf` avec d'autres combinateurs (`anyOf`, `oneOf`) : la réparation séquentielle via `foldLeft` peut créer des régressions croisées, où la correction d'une branche annule celle d'une branche traitée précédemment. Le huitième cas est `allOf: [true, false]`, théoriquement insatisfiable.

#### `items` — 41% (5/12)

Quatre cas reposent sur `items: false`, qui interdit tout élément au-delà des `prefixItems` : nous ne tronquons pas les tableaux, ce qui empêche la réparation. Les trois autres utilisent `$defs`/`$ref` pour référencer le schéma des items, et notre algorithme ne résout pas les références.

#### `enum` — 79% (23/29)

Les six échecs sont tous des `enum: []` (liste vide), qui n'admettent aucune valeur : irréparables au même titre que `not: {}`. Tous les cas avec `enum` non vide sont réparés correctement, y compris ceux d'égalité stricte du type `false ≠ 0` ou `true ≠ 1`.

#### `properties` — 66% (8/12)

Trois échecs concernent une interaction entre `properties`, `patternProperties` (non implémenté) et `additionalProperties`. Le quatrième porte sur des noms de propriétés réservés (`__proto__`, `toString`, `constructor`) qui posent des problèmes de navigation.

#### `uniqueItems` — 89% (17/19)

Les deux échecs combinent `uniqueItems` avec `items: false` : la déduplication seule ne suffit pas, il faudrait aussi tronquer les éléments interdits par `items: false`.

#### `multipleOf` — 75% (3/4)

L'échec porte sur `multipleOf: 0.0001` : la division en virgule flottante (`0.00751 / 0.0001 ≈ 75.0999...`) empêche notre arrondi au multiple le plus proche de converger.

#### `minLength` — 66% (2/3)

L'échec concerne un caractère graphème composé (🐲) : notre comptage utilise `.length` de Scala (unités UTF-16), pas le nombre de graphèmes.

### Observations générales

Les contraintes scalaires (`type`, `const`, `minimum`, `maximum`, bornes strictes) et les contraintes structurelles simples (`additionalProperties`, `minProperties`, `maxProperties`) atteignent 100%. Ce sont aussi les cas les plus fréquents dans les schémas réels. À l'inverse, les combinateurs imbriqués (`oneOf`, `not`, `allOf` avec `anyOf` à l'intérieur) et les mots-clés non implémentés constituent l'essentiel des échecs.

Une partie de ces échecs sont théoriques : `not: {}`, `enum: []`, `allOf: [true, false]` n'admettent aucune instance valide et ne sont donc pas un défaut de l'algorithme. Pour le reste, trois améliorations auraient un impact direct : la résolution de `$ref`/`$defs` débloquerait de nombreux cas dans `items` et `allOf`, la prise en charge de `items: false` traiterait la troncation des tableaux, et une stratégie d'invalidation ciblée permettrait de gérer les cas où une instance satisfait plusieurs branches d'un `oneOf`. Enfin, certains mots-clés non implémentés (`$ref`, `$dynamicRef`, `contains`, etc.) sont parfois réparés indirectement, par effet de bord des itérations successives.
