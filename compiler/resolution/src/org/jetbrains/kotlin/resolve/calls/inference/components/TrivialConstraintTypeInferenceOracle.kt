/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.calls.inference.components

import org.jetbrains.kotlin.resolve.calls.inference.model.Constraint
import org.jetbrains.kotlin.resolve.calls.inference.model.ConstraintKind
import org.jetbrains.kotlin.resolve.calls.inference.model.NewTypeVariable
import org.jetbrains.kotlin.types.UnwrappedType
import org.jetbrains.kotlin.types.typeUtil.contains
import org.jetbrains.kotlin.types.typeUtil.isNothing
import org.jetbrains.kotlin.types.typeUtil.isNullableNothing

class TrivialConstraintTypeInferenceOracle {
    fun isTrivialConstraint(constraint: Constraint): Boolean {
        // TODO: probably we also can take into account `T <: Any(?)` constraints
        return constraint.kind == ConstraintKind.LOWER && constraint.type.isNothingOrNullableNothing()
    }

    fun isSuitableResultedType(resultType: UnwrappedType): Boolean {
        return !resultType.isNothingOrNullableNothing()
    }

    fun isAddingOnlyTrivial(
        baseType: UnwrappedType,
        otherConstraint: Constraint,
        typeVariable: NewTypeVariable,
        generatedConstraintType: UnwrappedType
    ): Boolean {
        if (otherConstraint.type.contains { it.isNothingOrNullableNothing() }) return false

        if (!baseType.contains { it.isNothingOrNullableNothing() } &&
            generatedConstraintType.contains { it.isNothingOrNullableNothing() }
        ) {
            return true
        }

        val generatedTrivialConstraint = baseType.substituteTypeVariable(typeVariable, baseType.constructor.builtIns.nothingType)
        return generatedTrivialConstraint == generatedConstraintType
    }
}

private fun UnwrappedType.isNothingOrNullableNothing(): Boolean =
    isNothing() || isNullableNothing()
