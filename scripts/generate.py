# generate.py
# Génère une instance JSON valide pour un schéma donné via hypothesis-jsonschema.
# Le schéma est passé en argument (argv[1]) sous forme de chaîne JSON compacte.
# Retourne la première instance trouvée sérialisée en JSON sur stdout.
import sys
import json
from hypothesis_jsonschema import from_schema
from hypothesis import find, settings, HealthCheck

schema = json.loads(sys.argv[1])

# find() cherche le premier exemple satisfaisant le prédicat (ici, toujours vrai)
result = find(
    from_schema(schema),
    lambda x: True,
    settings=settings(
        max_examples=100,
        suppress_health_check=[HealthCheck.too_slow]
    )
)

print(json.dumps(result))