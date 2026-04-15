#!/usr/bin/env python3
"""
Mutateur d'instances JSON valides.
Prend une instance JSON valide (argv[1]) et un schéma (argv[2]),
applique 1 à 3 mutations aléatoires pour rendre l'instance invalide.
Sortie : l'instance mutée sur stdout.
"""
import json
import sys
import random
import copy

def mutate_type(value):
    """Change le type d'une valeur JSON."""
    if isinstance(value, str):
        return random.choice([42, 3.14, True, None])
    elif isinstance(value, bool):
        return random.choice(["oui", 42, 3.14])
    elif isinstance(value, int) and not isinstance(value, bool):
        return random.choice([str(value), True, [value]])
    elif isinstance(value, float):
        return random.choice([str(value), True, int(value)])
    elif isinstance(value, list):
        return random.choice(["not_an_array", 42, {}])
    elif isinstance(value, dict):
        return random.choice(["not_an_object", 42, []])
    elif value is None:
        return random.choice(["null_string", 0, False])
    return "mutated"

def mutate_numeric(value, schema):
    """Viole les bornes numériques d'un champ."""
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        return value
    minimum = schema.get("minimum")
    maximum = schema.get("maximum")
    exc_min = schema.get("exclusiveMinimum")
    exc_max = schema.get("exclusiveMaximum")
    multiple = schema.get("multipleOf")

    mutations = []
    if minimum is not None:
        mutations.append(minimum - random.randint(1, 100))
    if maximum is not None:
        mutations.append(maximum + random.randint(1, 100))
    if exc_min is not None:
        mutations.append(exc_min)
    if exc_max is not None:
        mutations.append(exc_max)
    if multiple is not None:
        mutations.append(value + multiple / 3.0)

    return random.choice(mutations) if mutations else value + 999

def mutate_string(value, schema):
    """Viole les contraintes de chaîne."""
    if not isinstance(value, str):
        return value
    min_len = schema.get("minLength")
    max_len = schema.get("maxLength")
    pattern = schema.get("pattern")

    mutations = []
    if min_len is not None and min_len > 0:
        mutations.append(value[:max(0, min_len - 1)])
    if max_len is not None:
        mutations.append(value + "x" * (max_len + 10))
    if pattern is not None:
        mutations.append("!!!invalid!!!")
    if schema.get("format"):
        mutations.append("not_a_valid_format")

    return random.choice(mutations) if mutations else value + "!!!"

def collect_paths(instance, schema, path=""):
    """Collecte les chemins mutables dans l'instance avec leur sous-schéma."""
    results = []
    if not isinstance(schema, dict):
        return results

    props = schema.get("properties", {})
    s_type = schema.get("type")

    # Le noeud courant est mutable
    if path != "" or s_type:
        results.append((path, instance, schema))

    if isinstance(instance, dict) and props:
        for key, sub_schema in props.items():
            if key in instance:
                results.extend(collect_paths(instance[key], sub_schema, f"{path}/{key}"))

    if isinstance(instance, list) and "items" in schema:
        items_schema = schema["items"]
        for i, item in enumerate(instance):
            results.extend(collect_paths(item, items_schema, f"{path}/{i}"))

    return results

def apply_mutation(instance, schema):
    """Applique une mutation aléatoire à l'instance."""
    mutated = copy.deepcopy(instance)
    paths = collect_paths(mutated, schema)

    if not paths:
        # Fallback : mutation de type à la racine
        return mutate_type(mutated)

    # Choisir un chemin aléatoire
    path, value, sub_schema = random.choice(paths)
    s_type = sub_schema.get("type")

    # Choisir le type de mutation
    mutation_types = ["type"]
    if s_type in ("integer", "number") or isinstance(value, (int, float)):
        mutation_types.append("numeric")
    if s_type == "string" or isinstance(value, str):
        mutation_types.append("string")
    if isinstance(mutated, dict) and schema.get("required"):
        mutation_types.append("remove_required")
    if isinstance(value, list):
        mutation_types.append("array_size")
    if sub_schema.get("enum"):
        mutation_types.append("enum")

    mutation = random.choice(mutation_types)

    if mutation == "remove_required" and path == "":
        required = schema.get("required", [])
        present = [k for k in required if k in mutated]
        if present:
            del mutated[random.choice(present)]
            return mutated

    # Naviguer jusqu'au chemin et appliquer la mutation
    segments = [s for s in path.split("/") if s]
    if not segments:
        if mutation == "type":
            return mutate_type(mutated)
        elif mutation == "numeric":
            return mutate_numeric(mutated, sub_schema)
        elif mutation == "string":
            return mutate_string(mutated, sub_schema)
        return mutate_type(mutated)

    # Naviguer dans l'instance
    parent = mutated
    for seg in segments[:-1]:
        if isinstance(parent, dict):
            parent = parent[seg]
        elif isinstance(parent, list):
            parent = parent[int(seg)]

    last = segments[-1]
    if isinstance(parent, list):
        last = int(last)

    if mutation == "type":
        parent[last] = mutate_type(value)
    elif mutation == "numeric":
        parent[last] = mutate_numeric(value, sub_schema)
    elif mutation == "string":
        parent[last] = mutate_string(value, sub_schema)
    elif mutation == "array_size":
        if isinstance(value, list):
            min_items = sub_schema.get("minItems", 0)
            max_items = sub_schema.get("maxItems")
            if min_items > 0:
                parent[last] = value[:max(0, min_items - 1)]
            elif max_items is not None:
                parent[last] = value + value + value
            else:
                parent[last] = []
    elif mutation == "enum":
        parent[last] = "INVALID_ENUM_VALUE_12345"

    return mutated

def aggressive_mutate(instance, schema):
    """Mutations agressives quand les mutations ciblées ne suffisent pas."""
    mutated = copy.deepcopy(instance)

    if isinstance(mutated, dict):
        # Stratégie 1 : supprimer des champs requis
        required = schema.get("required", [])
        present = [k for k in required if k in mutated]
        if present:
            del mutated[random.choice(present)]
            return mutated

        # Stratégie 2 : ajouter des propriétés additionnelles interdites
        if schema.get("additionalProperties") == False:
            mutated["__invalid_extra_key__"] = "invalid"
            return mutated

        # Stratégie 3 : remplacer une valeur par le mauvais type
        if mutated:
            key = random.choice(list(mutated.keys()))
            mutated[key] = mutate_type(mutated[key])
            return mutated

    # Stratégie 4 : changer le type de la racine
    return mutate_type(mutated)


def main():
    if len(sys.argv) < 3:
        print("Usage: mutate.py <instance_json> <schema_json>", file=sys.stderr)
        sys.exit(1)

    instance = json.loads(sys.argv[1])
    schema = json.loads(sys.argv[2])

    # Appliquer 1 à 3 mutations ciblées
    num_mutations = random.randint(1, 3)
    mutated = instance
    for _ in range(num_mutations):
        mutated = apply_mutation(mutated, schema)

    # Si la mutation ciblée n'a rien changé, forcer une mutation agressive
    if mutated == instance:
        mutated = aggressive_mutate(instance, schema)

    print(json.dumps(mutated))

if __name__ == "__main__":
    main()
