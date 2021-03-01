## Glossary

A small Glossary of the terms used troughout the repo

### TypeCase
A certain response shape matching a set of type conditions from nested fragments inline fragments. It can be a concrete type but it doesn't have to. Even if it has no possibleType, the [BaseType](#BaseType) is also listed in the TypeCase for backward compatibility with new types added to the schema

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


### BaseType 

the type of the field as defined in the schema, independantly of nested named and inline fragments. The `BaseType` is also a `TypeCase`.  

Example: `Animal`

### EnclosingType

the type of the enclosing fragment in the fragment hierarchy, if any. This is always null for the [BaseType](#BaseType).

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
`Pet` is the EnclosingType of the warmBlooded fragment
