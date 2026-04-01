# generate.py
import sys
import json
from hypothesis_jsonschema import from_schema
from hypothesis import find, settings, HealthCheck

schema = json.loads(sys.stdin.read())

# find() searches for the first value that satisfies the predicate
result = find(
    from_schema(schema),
    lambda x: True,  # any valid value is fine
    settings=settings(
        max_examples=100,
        suppress_health_check=[HealthCheck.too_slow]
    )
)

print(json.dumps(result))