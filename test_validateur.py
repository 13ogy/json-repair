from jschon import create_catalog, JSON, JSONSchema

create_catalog('2020-12')

demo_schema = JSONSchema({
  "$id": "https://example.com/person.schema.json",
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "Person",
  "type": "object",
  "required": ["firstName", "lastName"],
  "properties": {
    "firstName": {
      "type": "string",
      "description": "The person's first name."
    },
    "lastName": {
      "type": "string",
      "description": "The person's last name."
    },
    "age": {
      "description": "Age in years which must be equal to or greater than zero.",
      "type": "integer",
      "minimum": 0
    }
  }
}

)

result = demo_schema.evaluate(
    JSON({
        "firstName": "1",
        "lastName": "True",
        "age": 21
        }

    )
)

print(result.output('basic'))