## Glossary

A small Glossary of the terms used during codegen. The [GraphQL Spec](https://spec.graphql.org/draft/) does a nice job of defining the common terms like `Field`, `SelectionSet`, etc... so I'm not adding these terms here. But it misses some concepts that we bumped accross during codegen and that I'm trying to clarify here.

### Field tree

Every GraphQL operation queries a tree of fields starting from the root. Fields can be of scalar or compound type. 

### Fragment tree

Each field in the field tree contains (possibly nested) fragments and inline fragments that define a tree of fragments. This makes a GraphQL query a somewhat 2-dimensional tree.

### Field set

A selection set containing only fields and no fragments. When combined they form a field tree.

### Response shape

Synonym for field tree. This is the shape of the actual json as returned by the server. A given query can have multiple shapes depending the different type conditions at each field.

### Scalar type

A leaf type

### Compound type

A type that contains sub fields. It is an interface, object or union type

### Concrete type

Synonym for object type

### Possible types

Given a type condition, all the concrete types that can satisfy this type condition.

### TypeCase
A set of type conditions from nested fragments inline fragments. It can be a concrete type but it doesn't have to. Even if it has no possibleType, the [BaseType](#BaseType) is also listed in the TypeCase for backward compatibility with new types added to the schema. For a given field, the type cases represent the different possible Field Sets.

Example:
```graphql
{
    animal {
        species
        ... on Pet {
            name
        }
        ... on WarmBlooded {
            temperature
        }
    }
}
```
Typecases are:
  * `PetAnimal`
  * `PetWarmBloodedAnimal`
  * `Animal`


### Base Type 

the type of the field as defined in the schema, independantly of nested named and inline fragments. The `BaseType` is also a `TypeCase`.  

Example: `Animal`

### Enclosing Type

the type of the enclosing fragment in the fragment tree, if any. This is always null for the [BaseType](#BaseType).

Example:
```graphql
{
    animal {
        ... on Pet {
            ...warmBlooded {
                temperature
            }
        }
    }
}
```
`Pet` is the EnclosingType of the `warmBlooded` fragment

### Parent Type

the type of parent field of a given field.

Example:
```graphql
{
    animal {
        species
    }
}
```
`Animal` is the Parent type of the `species` field. Note that `animal` (lowercase) is the parent field of the `species` field.
