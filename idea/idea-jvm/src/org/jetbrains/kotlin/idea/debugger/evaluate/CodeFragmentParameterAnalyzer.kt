/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.evaluate

import com.intellij.debugger.engine.evaluation.EvaluateExceptionUtil
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.codegen.AsmUtil.THIS
import org.jetbrains.kotlin.codegen.CodeFragmentCodegenInfo
import org.jetbrains.kotlin.codegen.getCallLabelForLambdaArgument
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.debugger.evaluate.CodeFragmentParameterInfo.Parameter
import org.jetbrains.kotlin.idea.debugger.evaluate.KotlinCodeFragmentFactory.Companion.FAKE_JAVA_CONTEXT_FUNCTION_NAME
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.load.java.sam.SingleAbstractMethodUtils
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.scopes.receivers.ExtensionReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitClassReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitReceiver
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.builtIns

class CodeFragmentParameterInfo(
    val parameters: List<Parameter<*>>,
    val mappings: Map<KtElement, Parameter<*>>,
    val dispatchReceivers: Set<Parameter.DispatchReceiver>,
    val extensionReceivers: Set<Parameter.ExtensionReceiver>,
    val crossingBounds: Set<Parameter<*>>
) {
    sealed class Parameter<T : DeclarationDescriptor>(
        val index: Int,
        val type: KotlinType,
        override val descriptor: T,
        var rawString: String
    ) : CodeFragmentCodegenInfo.IParameter {
        class Ordinary(index: Int, type: KotlinType, descriptor: ValueDescriptor, val name: String) :
            Parameter<ValueDescriptor>(index, type, descriptor, name)

        @Suppress("ConvertToStringTemplate")
        class ExtensionReceiver(
            index: Int, type: KotlinType, descriptor: CallableDescriptor,
            val label: String, val isFakeJavaReceiver: Boolean = false
        ) : Parameter<CallableDescriptor>(index, type, descriptor, THIS + "@" + label)

        class DispatchReceiver(index: Int, type: KotlinType, descriptor: ClassDescriptor) :
            Parameter<ClassDescriptor>(index, type, descriptor, THIS)

        class LocalFunction(
            index: Int, type: KotlinType, descriptor: FunctionDescriptor,
            val name: String, var functionIndex: Int = 0
        ) : Parameter<FunctionDescriptor>(index, type, descriptor, name)
    }
}

class CodeFragmentParameterAnalyzer(private val codeFragment: KtCodeFragment, private val bindingContext: BindingContext) {
    private val mappings = hashMapOf<KtElement, Parameter<*>>()
    private val parameters = LinkedHashMap<DeclarationDescriptor, Parameter<*>>()
    private val dispatchReceivers = mutableSetOf<Parameter.DispatchReceiver>()
    private val extensionReceivers = mutableSetOf<Parameter.ExtensionReceiver>()
    private val crossingBounds = mutableSetOf<Parameter<*>>()

    fun analyze(): CodeFragmentParameterInfo {
        checkUsedOnce()

        codeFragment.accept(object : KtTreeVisitor<Unit>() {
            override fun visitSimpleNameExpression(expression: KtSimpleNameExpression, data: Unit?): Void? {
                if (runReadAction { expression.isDotSelector() }) {
                    return null
                }

                val resolvedCall = expression.getResolvedCall(bindingContext) ?: return null
                if (isCodeFragmentDeclaration(resolvedCall.resultingDescriptor)) {
                    return null
                }

                val extensionReceiver = resolvedCall.extensionReceiver
                val dispatchReceiver = resolvedCall.dispatchReceiver

                val parameter: Parameter<*>?
                val descriptor: DeclarationDescriptor

                when {
                    extensionReceiver is ImplicitReceiver -> {
                        descriptor = extensionReceiver.declarationDescriptor
                        parameter = processReceiver(extensionReceiver)
                    }
                    dispatchReceiver is ImplicitReceiver -> {
                        descriptor = dispatchReceiver.declarationDescriptor
                        parameter = processReceiver(dispatchReceiver)
                    }
                    else -> {
                        descriptor = resolvedCall.resultingDescriptor
                        parameter = processSimpleNameExpression(expression, descriptor)
                    }
                }

                if (parameter != null && descriptor is DeclarationDescriptorWithSource) {
                    val targetPsi = descriptor.source.getPsi()
                    if (targetPsi != null && doesCrossInlineBounds(expression, targetPsi)) {
                        crossingBounds += parameter
                    }
                }

                return null
            }

            override fun visitThisExpression(expression: KtThisExpression, data: Unit?): Void? {
                val instanceReference = runReadAction { expression.instanceReference }
                val target = bindingContext[BindingContext.REFERENCE_TARGET, instanceReference]
                if (isCodeFragmentDeclaration(target)) {
                    return null
                }

                when (target) {
                    is ClassDescriptor -> processDispatchReceiver(target)
                    is CallableDescriptor -> {
                        val type = bindingContext[BindingContext.EXPRESSION_TYPE_INFO, expression]?.type
                        if (type != null) {
                            processExtensionReceiver(target, type, expression.getLabelName())
                        }
                    }
                }

                return null
            }

            override fun visitSuperExpression(expression: KtSuperExpression, data: Unit?): Void {
                throw EvaluateExceptionUtil.createEvaluateException("Evaluation of 'super' call expression is not supported")
            }
        }, Unit)

        return CodeFragmentParameterInfo(parameters.values.toList(), mappings, dispatchReceivers, extensionReceivers, crossingBounds)
    }

    private fun isFakeFunctionForJavaContext(descriptor: CallableDescriptor): Boolean {
        return descriptor is FunctionDescriptor
                && descriptor.name.asString() == FAKE_JAVA_CONTEXT_FUNCTION_NAME
                && codeFragment.getCopyableUserData(KtCodeFragment.FAKE_CONTEXT_FOR_JAVA_FILE) != null
    }

    private fun processReceiver(receiver: ImplicitReceiver): Parameter<*>? {
        return when (receiver) {
            is ExtensionReceiver -> processExtensionReceiver(receiver.declarationDescriptor, receiver.type, null)
            is ImplicitClassReceiver -> processDispatchReceiver(receiver.classDescriptor)
            else -> null
        }
    }

    private fun processExtensionReceiver(descriptor: CallableDescriptor, receiverType: KotlinType, label: String?): Parameter<*>? {
        if (isFakeFunctionForJavaContext(descriptor)) {
            return processFakeJavaCodeReceiver(descriptor)
        }

        val actualLabel = label ?: getLabel(descriptor) ?: return null

        return parameters.getOrPut(descriptor) {
            Parameter.ExtensionReceiver(nextIndex(receiverType), receiverType, descriptor, actualLabel)
                .also { extensionReceivers += it }
        }
    }

    private fun processDispatchReceiver(descriptor: ClassDescriptor): Parameter<*>? {
        if (descriptor.kind == ClassKind.OBJECT) {
            return null
        }

        return parameters.getOrPut(descriptor) {
            val type = descriptor.defaultType
            Parameter.DispatchReceiver(nextIndex(type), type, descriptor)
                .also { dispatchReceivers += it }
        }
    }

    private fun processFakeJavaCodeReceiver(descriptor: CallableDescriptor): Parameter<*>? {
        val thisType = descriptor
            .takeIf { descriptor is FunctionDescriptor }
            ?.extensionReceiverParameter?.type
            ?: return null

        return parameters.getOrPut(descriptor) {
            val label = FAKE_JAVA_CONTEXT_FUNCTION_NAME
            Parameter.ExtensionReceiver(nextIndex(thisType), thisType, descriptor, label, isFakeJavaReceiver = true)
                .also { extensionReceivers += it }
        }
    }

    private fun getLabel(callableDescriptor: CallableDescriptor): String? {
        val source = callableDescriptor.source.getPsi()

        if (source is KtFunctionLiteral) {
            getCallLabelForLambdaArgument(source, bindingContext)?.let { return it }
        }

        return callableDescriptor.name.takeIf { !it.isSpecial }?.asString()
    }

    private fun processSimpleNameExpression(expression: KtSimpleNameExpression, target: DeclarationDescriptor): Parameter<*>? {
        if ((target as? DeclarationDescriptorWithVisibility)?.visibility != Visibilities.LOCAL) {
            return null
        }

        return when (target) {
            is FunctionDescriptor -> {
                val type = SingleAbstractMethodUtils.getFunctionTypeForAbstractMethod(target, false)
                parameters.getOrPut(target) {
                    Parameter.LocalFunction(nextIndex(type), type, target, target.name.asString())
                }
            }
            is ValueDescriptor -> {
                parameters.getOrPut(target) {
                    val type = target.type
                    Parameter.Ordinary(nextIndex(type), type, target, target.name.asString())
                }
            }
            else -> null
        }?.also { mappings[expression] = it }
    }

    private fun doesCrossInlineBounds(expression: PsiElement, declaration: PsiElement): Boolean {
        val declarationParent = declaration.parent ?: return false
        var currentParent: PsiElement? = expression.parent?.takeIf { it.isInside(declarationParent) } ?: return false

        while (currentParent != null && currentParent != declarationParent) {
            if (currentParent is KtFunction) {
                val functionDescriptor = bindingContext[BindingContext.FUNCTION, currentParent]
                if (functionDescriptor != null && !functionDescriptor.isInline) {
                    return true
                }
            }

            currentParent = when (currentParent) {
                is KtCodeFragment -> currentParent.context
                else -> currentParent.parent
            }
        }

        return false
    }

    private fun isCodeFragmentDeclaration(descriptor: DeclarationDescriptor?): Boolean {
        if (descriptor is ValueParameterDescriptor && isCodeFragmentDeclaration(descriptor.containingDeclaration)) {
            return true
        }

        if (descriptor !is DeclarationDescriptorWithSource) {
            return false
        }

        return descriptor.source.getPsi()?.containingFile is KtCodeFragment
    }

    private tailrec fun PsiElement.isInside(parent: PsiElement): Boolean {
        if (parent.isAncestor(this)) {
            return true
        }

        val context = (this.containingFile as? KtCodeFragment)?.context ?: return false
        return context.isInside(parent)
    }

    private var used = false

    private fun checkUsedOnce() {
        if (used) {
            error(CodeFragmentParameterAnalyzer::class.java.simpleName + " may be only used once")
        }

        used = true
    }

    private var nextIndex = 0

    private fun nextIndex(type: KotlinType): Int {
        val result = nextIndex
        val builtIns = type.builtIns
        this.nextIndex += if (builtIns.floatType == type || builtIns.doubleType == type) 2 else 1
        return result
    }
}