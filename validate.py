import sys
import json
from jschon import create_catalog, JSON, JSONSchema, URI

create_catalog('2020-12')

# read schema and data from command line arguments
schema_json = json.loads(sys.argv[1])
data_json   = json.loads(sys.argv[2])

schema_json["$schema"] = "https://json-schema.org/draft/2020-12/schema"

schema = JSONSchema(schema_json, metaschema_uri=URI("https://json-schema.org/draft/2020-12/schema"))

result = schema.evaluate(JSON(data_json))
output = result.output('basic')

# flatten into your ValidationError format, filter out non-leaf errors
errors = [
    {
        "instanceLocation": e.get("instanceLocation", ""),
        "keywordLocation":  e.get("keywordLocation", ""),
        "error":            e.get("error", "")
    }
    for e in output.get("errors", [])
]

print(json.dumps({
    "valid":  output["valid"],
    "errors": errors
}))