# KSP

apollo-ksp is a code first execution engine. It generates GraphQL resolvers from Kotlin code. Kotlin and GraphQL are similar enough that most of the time it maps well. 

Restrictions:
* Default arguments are not exposed in KSP.
* KDoc is not possible on function arguments.
* All interfaces must be sealed.
* Input classes are defined based on their primary constructor.
* Output classes are defined based on their declared public fields and functions.