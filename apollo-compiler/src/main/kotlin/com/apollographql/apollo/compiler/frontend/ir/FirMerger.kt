package com.apollographql.apollo.compiler.frontend.ir

object FirMerger {
  fun mergeSelectionSet(selectionSet: SelectionSet, parentType: String): SelectionSet {
    return selectionSet
        .mergeTrivialInlineFragments(parentType)
        .mergeFieldsAndInlineFragments()
  }

  private fun SelectionSet.mergeTrivialInlineFragments(parentType: String): SelectionSet {
    return copy(
        selections = selections.flatMap { selection ->
          when (selection) {
            is InlineFragment -> {
              val selectionSet = selection.selectionSet.mergeTrivialInlineFragments(selection.typeCondition)
              if (selection.typeCondition == parentType) {
                selectionSet.selections
              } else {
                listOf(selection.copy(selectionSet = selectionSet))
              }
            }
            is Field -> {
              val selectionSet = selection.selectionSet.mergeTrivialInlineFragments(selection.type.leafName)
              listOf(selection.copy(selectionSet = selectionSet))
            }
            is FragmentSpread -> {
              listOf(selection)
            }
          }
        }
    )
  }

  private fun SelectionSet.mergeFieldsAndInlineFragments():SelectionSet {
    return SelectionSet(
        selections = selections.mergeFieldsAndInlineFragments()
    )
  }

  private fun List<Selection>.mergeFieldsAndInlineFragments(): List<Selection> {
    val mergedSelections = mutableListOf<Selection>()

    forEach { incomingSelection ->
      when (incomingSelection) {
        is InlineFragment -> {
          val selectionsToMerge = incomingSelection.selectionSet.selections.mergeFieldsAndInlineFragments()
          val index = mergedSelections.indexOfFirst { it is InlineFragment && it.typeCondition == incomingSelection.typeCondition }
          if (index == -1) {
            mergedSelections.add(incomingSelection.copy(
                selectionSet = SelectionSet(selectionsToMerge)
            ))
          } else {
            val existingInlineFragment = mergedSelections.removeAt(index) as InlineFragment
            mergedSelections.add(
                index,
                incomingSelection.copy(
                    selectionSet = SelectionSet(
                        (existingInlineFragment.selectionSet.selections + selectionsToMerge).mergeFieldsAndInlineFragments()
                    )
                )
            )
          }
        }
        is FragmentSpread -> {
          val index = mergedSelections.indexOfFirst { it is FragmentSpread && it.name == incomingSelection.name }
          if (index == -1) {
            mergedSelections.add(incomingSelection)
          } else {
            // It can happen that the same named fragment is requested multiple times
            // It's mostly useless but we still need to merge the conditions
            val existingFragmentSpread = mergedSelections.removeAt(index) as FragmentSpread
            mergedSelections.add(
                index,
                existingFragmentSpread.copy(
                    condition = existingFragmentSpread.condition.or(incomingSelection.condition).simplify()
                )
            )
          }
        }
        is Field -> {
          val index = mergedSelections.indexOfFirst { it is Field && it.responseName == incomingSelection.responseName }
          if (index == -1) {
            mergedSelections.add(
                incomingSelection.copy(
                    selectionSet = SelectionSet(
                        incomingSelection.selectionSet.selections.mergeFieldsAndInlineFragments()
                    )

                )
            )
          } else {
            val existingField = mergedSelections.removeAt(index) as Field
            mergedSelections.add(
                index,
                existingField.copy(
                    condition = existingField.condition.or(incomingSelection.condition).simplify(),
                    selectionSet = SelectionSet(
                        (existingField.selectionSet.selections + incomingSelection.selectionSet.selections).mergeFieldsAndInlineFragments()
                    )
                )
            )
          }
        }
      }
    }

    return mergedSelections
  }
}
