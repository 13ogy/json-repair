from jschon import create_catalog, JSON, JSONSchema

create_catalog('2020-12')

demo_schema = JSONSchema({
    "$id": "https://example.com/demo",
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "type": "array",
    "items": {
        "anyOf": [
            {
                "type": "string",
                "description": "Cool! We got a string here!"
            },
            {
                "type": "integer",
                "description": "Hey! We got an integer here!"
            }
        ]
    }
})

result = demo_schema.evaluate(
    JSON(["True"])
)
print(result.output('basic'))