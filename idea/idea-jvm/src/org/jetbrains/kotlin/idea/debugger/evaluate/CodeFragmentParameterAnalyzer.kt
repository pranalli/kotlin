/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.evaluate

import com.intellij.debugger.engine.evaluation.EvaluateExceptionUtil
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.codegen.AsmUtil
import org.jetbrains.kotlin.codegen.CodeFragmentCodegenInfo
import org.jetbrains.kotlin.codegen.getCallLabelForLambdaArgument
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.debugger.evaluate.CodeFragmentParameter.*
import org.jetbrains.kotlin.idea.debugger.evaluate.KotlinCodeFragmentFactory.Companion.FAKE_JAVA_CONTEXT_FUNCTION_NAME
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.load.java.sam.SingleAbstractMethodUtils
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.calls.checkers.COROUTINE_CONTEXT_1_3_FQ_NAME
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.scopes.receivers.ExtensionReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitClassReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitReceiver
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlin.types.KotlinType

interface CodeFragmentParameter {
    val kind: Kind
    val name: String
    val debugString: String

    enum class Kind {
        ORDINARY, EXTENSION_RECEIVER, DISPATCH_RECEIVER, COROUTINE_CONTEXT, LOCAL_FUNCTION, FAKE_JAVA_OUTER_CLASS
    }

    class Smart(
        val dumb: Dumb,
        override val targetType: KotlinType,
        override val targetDescriptor: DeclarationDescriptor
    ) : CodeFragmentParameter by dumb, CodeFragmentCodegenInfo.IParameter

    data class Dumb(
        override val kind: Kind,
        override val name: String,
        override val debugString: String = name
    ) : CodeFragmentParameter
}

class CodeFragmentParameterInfo(
    val parameters: List<CodeFragmentParameter.Smart>,
    val crossingBounds: Set<CodeFragmentParameter.Dumb>
)

class CodeFragmentParameterAnalyzer(private val codeFragment: KtCodeFragment, private val bindingContext: BindingContext) {
    private val parameters = LinkedHashMap<DeclarationDescriptor, Smart>()
    private val crossingBounds = mutableSetOf<Dumb>()

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

                var processed = false

                val extensionReceiver = resolvedCall.extensionReceiver
                if (extensionReceiver is ImplicitReceiver) {
                    val descriptor = extensionReceiver.declarationDescriptor
                    val parameter = processReceiver(extensionReceiver)
                    checkBounds(descriptor, expression, parameter)
                    processed = true
                }

                val dispatchReceiver = resolvedCall.dispatchReceiver
                if (dispatchReceiver is ImplicitReceiver) {
                    val descriptor = dispatchReceiver.declarationDescriptor
                    val parameter = processReceiver(dispatchReceiver)
                    checkBounds(descriptor, expression, parameter)
                    processed = true
                }

                if (!processed) {
                    val descriptor = resolvedCall.resultingDescriptor
                    val parameter = processSimpleNameExpression(descriptor)
                    checkBounds(descriptor, expression, parameter)
                }

                return null
            }

            override fun visitThisExpression(expression: KtThisExpression, data: Unit?): Void? {
                val instanceReference = runReadAction { expression.instanceReference }
                val target = bindingContext[BindingContext.REFERENCE_TARGET, instanceReference]
                if (isCodeFragmentDeclaration(target)) {
                    return null
                }

                val parameter = when (target) {
                    is ClassDescriptor -> processDispatchReceiver(target)
                    is CallableDescriptor -> {
                        val type = bindingContext[BindingContext.EXPRESSION_TYPE_INFO, expression]?.type
                        if (type != null) {
                            processExtensionReceiver(target, type, expression.getLabelName())
                        } else {
                            null
                        }
                    }
                    else -> null
                }

                if (parameter != null) {
                    checkBounds(target, expression, parameter)
                }

                return null
            }

            override fun visitSuperExpression(expression: KtSuperExpression, data: Unit?): Void {
                throw EvaluateExceptionUtil.createEvaluateException("Evaluation of 'super' call expression is not supported")
            }
        }, Unit)

        return CodeFragmentParameterInfo(parameters.values.toList(), crossingBounds)
    }

    private fun isFakeFunctionForJavaContext(descriptor: CallableDescriptor): Boolean {
        return descriptor is FunctionDescriptor
                && descriptor.name.asString() == FAKE_JAVA_CONTEXT_FUNCTION_NAME
                && codeFragment.getCopyableUserData(KtCodeFragment.FAKE_CONTEXT_FOR_JAVA_FILE) != null
    }

    private fun processReceiver(receiver: ImplicitReceiver): Smart? {
        return when (receiver) {
            is ExtensionReceiver -> processExtensionReceiver(receiver.declarationDescriptor, receiver.type, null)
            is ImplicitClassReceiver -> processDispatchReceiver(receiver.classDescriptor)
            else -> null
        }
    }

    private fun processExtensionReceiver(descriptor: CallableDescriptor, receiverType: KotlinType, label: String?): Smart? {
        if (isFakeFunctionForJavaContext(descriptor)) {
            return processFakeJavaCodeReceiver(descriptor)
        }

        val actualLabel = label ?: getLabel(descriptor) ?: return null
        val receiverParameter = descriptor.extensionReceiverParameter ?: return null

        return parameters.getOrPut(descriptor) {
            Smart(Dumb(Kind.EXTENSION_RECEIVER, actualLabel, AsmUtil.THIS + "@" + actualLabel), receiverType, receiverParameter)
        }
    }

    private fun processDispatchReceiver(descriptor: ClassDescriptor): Smart? {
        if (descriptor.kind == ClassKind.OBJECT) {
            return null
        }

        val type = descriptor.defaultType
        return parameters.getOrPut(descriptor) {
            Smart(Dumb(Kind.DISPATCH_RECEIVER, "", AsmUtil.THIS + "@" + descriptor.name.asString()), type, descriptor)
        }
    }

    private fun processFakeJavaCodeReceiver(descriptor: CallableDescriptor): Smart? {
        val receiverParameter = descriptor
            .takeIf { descriptor is FunctionDescriptor }
            ?.extensionReceiverParameter
            ?: return null

        val label = FAKE_JAVA_CONTEXT_FUNCTION_NAME
        val type = receiverParameter.type
        return parameters.getOrPut(descriptor) {
            Smart(Dumb(Kind.FAKE_JAVA_OUTER_CLASS, label, AsmUtil.THIS), type, receiverParameter)
        }
    }

    private fun getLabel(callableDescriptor: CallableDescriptor): String? {
        val source = callableDescriptor.source.getPsi()

        if (source is KtFunctionLiteral) {
            getCallLabelForLambdaArgument(source, bindingContext)?.let { return it }
        }

        return callableDescriptor.name.takeIf { !it.isSpecial }?.asString()
    }

    private fun processSimpleNameExpression(target: DeclarationDescriptor): Smart? {
        processCoroutineContextCall(target)?.let { return it }

        if ((target as? DeclarationDescriptorWithVisibility)?.visibility != Visibilities.LOCAL) {
            return null
        }

        return when (target) {
            is FunctionDescriptor -> {
                val type = SingleAbstractMethodUtils.getFunctionTypeForAbstractMethod(target, false)
                parameters.getOrPut(target) {
                    Smart(Dumb(Kind.LOCAL_FUNCTION, target.name.asString()), type, target)
                }
            }
            is ValueDescriptor -> {
                parameters.getOrPut(target) {
                    val type = target.type
                    Smart(Dumb(Kind.ORDINARY, target.name.asString()), type, target)
                }
            }
            else -> null
        }
    }

    private fun processCoroutineContextCall(target: DeclarationDescriptor): Smart? {
        if (target is PropertyDescriptor && target.fqNameSafe == COROUTINE_CONTEXT_1_3_FQ_NAME) {
            val type = target.type
            return parameters.getOrPut(target) { Smart(Dumb(Kind.COROUTINE_CONTEXT, ""), type, target) }
        }

        return null
    }

    fun checkBounds(descriptor: DeclarationDescriptor?, expression: KtExpression, parameter: Smart?) {
        if (parameter == null || descriptor !is DeclarationDescriptorWithSource) {
            return
        }

        val targetPsi = descriptor.source.getPsi()
        if (targetPsi != null && doesCrossInlineBounds(expression, targetPsi)) {
            crossingBounds += parameter.dumb
        }
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
}