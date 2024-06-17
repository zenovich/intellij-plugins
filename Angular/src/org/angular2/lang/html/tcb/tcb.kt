/**
 * @license
 * Copyright Google LLC All Rights Reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be
 * found in the LICENSE file at https://angular.io/license
 */
@file:Suppress("CascadeIf")

package org.angular2.lang.html.tcb

import com.intellij.lang.javascript.psi.*
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeList
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeList.ModifierType
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeListOwner
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.util.asSafely
import org.angular2.codeInsight.config.Angular2TypeCheckingConfig.ControlFlowPreventingContentProjectionKind
import org.angular2.codeInsight.controlflow.Angular2ControlFlowBuilder.Companion.NG_TEMPLATE_CONTEXT_GUARD
import org.angular2.codeInsight.controlflow.Angular2ControlFlowBuilder.Companion.NG_TEMPLATE_GUARD_PREFIX
import org.angular2.codeInsight.template.Angular2StandardSymbolsScopesProvider
import org.angular2.entities.Angular2ClassBasedDirectiveProperty
import org.angular2.entities.Angular2Component
import org.angular2.entities.Angular2EntityUtils.NG_ACCEPT_INPUT_TYPE_PREFIX
import org.angular2.entities.Angular2EntityUtils.TEMPLATE_REF
import org.angular2.entities.Angular2Pipe
import org.angular2.entities.Angular2TemplateGuard
import org.angular2.entities.source.Angular2SourceDirectiveProperty
import org.angular2.lang.Angular2LangUtil.`$IMPLICIT`
import org.angular2.lang.Angular2LangUtil.ANGULAR_CORE_PACKAGE
import org.angular2.lang.Angular2LangUtil.OUTPUT_CHANGE_SUFFIX
import org.angular2.lang.expr.psi.*
import org.angular2.lang.html.parser.Angular2AttributeNameParser


private typealias `TcbOp|Identifier` = Any
private typealias `Int|Identifier` = Any
internal typealias `TmplDirectiveMetadata|TmplAstElement|TmplAstTemplate` = Any
internal typealias `TmplDirectiveMetadata|TmplAstElement` = Any
internal typealias `TmplAstElement|TmplAstTemplate` = TmplAstDirectiveContainer
private typealias `TmplAstTemplate|TmplAstIfBlockBranch|TmplAstForLoopBlock` = TmplAstNode
private typealias `TmplAstElement|TmplAstTemplate|TmplAstVariable|TmplAstReference` = TmplAstNode
private typealias `TmplAstBoundAttribute|TmplAstTextAttribute` = TmplAstAttribute
internal typealias `TmplAstBoundAttribute|TmplAstBoundEvent|TmplAstTextAttribute` = TmplAstAttribute
private typealias `EventParamType|JSType` = Any

internal typealias AST = PsiElement
internal typealias TemplateId = String

internal object ts {
  fun isObjectLiteralExpression(expr: Expression): Boolean =
    expr.toString().let { it.startsWith("(") && it.endsWith(")") }

  fun isArrayLiteralExpression(expr: Expression): Boolean =
    expr.toString().let { it.startsWith("[") && it.endsWith("]") }

  enum class DiagnosticCategory {
    Error,
    Warning,
  }

}

/**
 * A code generation operation that's involved in the construction of a Type Check Block.
 *
 * The generation of a TCB is non-linear. Bindings within a template may result in the need to
 * construct certain types earlier than they otherwise would be constructed. That is, if the
 * generation of a TCB for a template is broken down into specific operations (constructing a
 * directive, extracting a variable from a let- operation, etc), then it's possible for operations
 * earlier in the sequence to depend on operations which occur later in the sequence.
 *
 * `TcbOp` abstracts the different types of operations which are required to convert a template into
 * a TCB. This allows for two phases of processing for the template, where 1) a linear sequence of
 * `TcbOp`s is generated, and then 2) these operations are executed, not necessarily in linear
 * order.
 *
 * Each `TcbOp` may insert statements into the body of the TCB, and also optionally return a
 * `Expression` which can be used to reference the operation's result.
 */
private abstract class TcbOp {
  /**
   * Set to true if this operation can be considered optional. Optional operations are only executed
   * when depended upon by other operations, otherwise they are disregarded. This allows for less
   * code to generate, parse and type-check, overall positively contributing to performance.
   */
  abstract val optional: Boolean

  abstract fun execute(): Identifier?

  /**
   * Replacement value or operation used while this `TcbOp` is executing (i.e. to resolve circular
   * references during its execution).
   *
   * This is usually a `null!` expression (which asks TS to infer an appropriate type), but another
   * `TcbOp` can be returned in cases where additional code generation is necessary to deal with
   * circular references.
   */
  open fun circularFallback(): `TcbOp|Identifier` {
    return INFER_TYPE_FOR_CIRCULAR_OP_EXPR
  }
}

/**
 * A `TcbOp` which creates an expression for a native DOM element (or web component) from a
 * `TmplAstElement`.
 *
 * Executing this operation returns a reference to the element variable.
 */
private class TcbElementOp(private val tcb: Context, private val scope: Scope, private val element: TmplAstElement) : TcbOp() {

  override val optional: Boolean
    get() {
      // The statement generated by this operation is only used for type-inference of the DOM
      // element's type and won't report diagnostics by itself, so the operation is marked as optional
      // to avoid generating statements for DOM elements that are never referenced.
      return true
    }

  override fun execute(): Identifier {
    val id = this.tcb.allocateId(element.startSourceSpan)
    // Add the declaration of the element using document.createElement.
    this.scope.addStatement(
      tsCreateVariable(id, Expression("document.createElement(\"${element.name}\")"))
    )
    return id
  }
}

/**
 * A `TcbOp` which creates an expression for particular let- `TmplAstVariable` on a
 * `TmplAstTemplate`'s context.
 *
 * Executing this operation returns a reference to the variable variable (lol).
 */
private class TcbTemplateVariableOp(
  private val tcb: Context,
  private val scope: Scope,
  private val template: TmplAstTemplate,
  private val variable: TmplAstVariable
) : TcbOp() {

  override val optional: Boolean get() = false

  override fun execute(): Identifier {
    // Look for a context variable for the template.
    val ctx = this.scope.resolve(this.template)

    // Allocate an identifier for the TmplAstVariable, and initialize it to a read of the variable
    // on the template context.
    val id = this.tcb.allocateId(variable.keySpan)
    this.scope.addStatement {
      append("var ")
      append(id, id.sourceSpan)
      append(" = ")
      append(ctx)
      val name = variable.value ?: `$IMPLICIT`
      if (StringUtil.isJavaIdentifier(name))
        append(".").append(name, variable.valueSpan)
      else
        append("[\"").append(name.replace("\"", "\\\""), variable.valueSpan).append("\"]")
      append(";")
    }
    return id
  }
}

/**
 * A `TcbOp` which generates a variable for a `TmplAstTemplate`'s context.
 *
 * Executing this operation returns a reference to the template's context variable.
 */
private class TcbTemplateContextOp(private val tcb: Context, private val scope: Scope) : TcbOp() {

  // The declaration of the context variable is only needed when the context is actually referenced.
  override val optional get() = true

  override fun execute(): Identifier {
    // Allocate a template ctx variable and declare it with an 'any' type. The type of this variable
    // may be narrowed as a result of template guard conditions.
    val ctx = this.tcb.allocateId()
    this.scope.addStatement {
      append("var ${ctx}: any = null!;")
    }
    return ctx
  }
}

/**
 * A `TcbOp` which descends into a `TmplAstTemplate`'s children and generates type-checking code for
 * them.
 *
 * This operation wraps the children's type-checking code in an `if` block, which may include one
 * or more type guard conditions that narrow types within the template body.
 */
private class TcbTemplateBodyOp(private val tcb: Context, private val scope: Scope, private val template: TmplAstTemplate) : TcbOp() {

  override val optional get() = false

  override fun execute(): Identifier? {
    // An `if` will be constructed, within which the template's children will be type checked. The
    // `if` is used for two reasons: it creates a new syntactic scope, isolating variables declared
    // in the template's TCB from the outer context, and it allows any directives on the templates
    // to perform type narrowing of either expressions or the template's context.
    //
    // The guard is the `if` block's condition. It's usually set to `true` but directives that exist
    // on the template can trigger extra guard expressions that serve to narrow types within the
    // `if`. `guard` is calculated by starting with `true` and adding other conditions as needed.
    // Collect these into `guards` by processing the directives.
    val directiveGuards = mutableListOf<Expression>()

    val directives = this.tcb.boundTarget.getDirectivesOfNode(this.template)

    for (dir in directives) {
      val dirInstId = this.scope.resolve(this.template, dir)
      val dirId = this.tcb.env.reference(dir.typeScriptClass ?: continue)

      // There are two kinds of guards. Template guards (ngTemplateGuards) allow type narrowing of
      // the expression passed to an @Input of the directive. Scan the directive to see if it has
      // any template guards, and generate them if needed.
      dir.templateGuards.forEach { guard ->
        // For each template guard function on the directive, look for a binding to that input.
        val boundInput = this.template.inputs[guard.inputName]
                         ?: this.template.templateAttrs.firstNotNullOfOrNull { attr ->
                           (attr as? TmplAstBoundAttribute)?.takeIf { it.name == guard.inputName }
                         }
        if (boundInput != null) {
          // If there is such a binding, generate an expression for it.
          val expr = tcbExpression(boundInput.value, this.tcb, this.scope).let {
            // The expression has already been checked in the type constructor invocation, so
            // it should be ignored when used within a template guard.
            Expression {
              withIgnoreDiagnostics {
                append(it)
              }
            }
          }

          if (guard.type == Angular2TemplateGuard.Kind.Binding) {
            // Use the binding expression itself as guard.
            directiveGuards.add(expr)
          }
          else {
            // Call the guard function on the directive with the directive instance and that
            // expression.
            val guardInvoke = Expression {
              append("$dirId.$NG_TEMPLATE_GUARD_PREFIX${guard.inputName}", boundInput.value?.textRange)
              append("($dirInstId, ")
              append(expr)
              append(")")
            }
            directiveGuards.add(guardInvoke)
          }
        }
      }

      // The second kind of guard is a template context guard. This guard narrows the template
      // rendering context variable `ctx`.
      if (dir.hasTemplateContextGuard) {
        if (this.tcb.env.config.applyTemplateContextGuards) {
          val ctx = this.scope.resolve(this.template)
          val guardInvoke = Expression {
            append("$dirId.$NG_TEMPLATE_CONTEXT_GUARD($dirInstId, $ctx)", template.startSourceSpan)
          }
          directiveGuards.add(guardInvoke)
        }
        else if (
          this.template.variables.isNotEmpty() &&
          this.tcb.env.config.suggestionsForSuboptimalTypeInference) {
          // The compiler could have inferred a better type for the variables in this template,
          // but was prevented from doing so by the type-checking configuration. Issue a warning
          // diagnostic.
          this.tcb.oobRecorder.suboptimalTypeInference(this.tcb.id, this.template.variables.values)
        }
      }
    }


    // By default the guard is simply `true`.
    var guard: Expression? = null

    // If there are any guards from directives, use them instead.
    if (directiveGuards.size > 0) {
      // Pop the first value and use it as the initializer to reduce(). This way, a single guard
      // will be used on its own, but two or more will be combined into binary AND expressions.
      guard = Expression {
        directiveGuards.reversed().forEachIndexed { index, expression ->
          if (index > 0)
            append(" && ")
          append(expression)
        }
      }
    }

    // Create a new Scope for the template. This constructs the list of operations for the template
    // children, as well as tracks bindings within the template.
    val tmplScope =
      Scope.forNodes(this.tcb, this.scope, this.template, this.template.children, guard)

    // Render the template's `Scope` into its statements.
    val statements = tmplScope.render()
    if (statements.isEmpty()) {
      // As an optimization, don't generate the scope's block if it has no statements. This is
      // beneficial for templates that contain for example `<span *ngIf="first"></span>`, in which
      // case there's no need to render the `NgIf` guard expression. This seems like a minor
      // improvement, however it reduces the number of flow-node antecedents that TypeScript needs
      // to keep into account for such cases, resulting in an overall reduction of
      // type-checking time.
      return null
    }
    this.scope.addStatement {
      if (guard != null) {
        // The scope has a guard that needs to be applied, so wrap the template block into an `if`
        // statement containing the guard expression.
        append("if (")
        append(guard)
        append(") ")
      }
      codeBlock {
        statements.forEach(this::appendStatement)
      }
    }
    return null
  }
}

/**
 * A `TcbOp` which renders an Angular expression (e.g. `{{foo() && bar.baz}}`).
 *
 * Executing this operation returns nothing.
 */
private class TcbExpressionOp(private val tcb: Context, private val scope: Scope, private val expression: JSExpression?) : TcbOp() {

  override val optional get() = false

  override fun execute(): Identifier? {
    val expr = tcbExpression(this.expression, this.tcb, this.scope)
    this.scope.addStatement {
      append("\"\" + ").append(expr).append(";")
    }
    return null
  }
}

/**
 * A `TcbOp` which constructs an instance of a directive. For generic directives, generic
 * parameters are set to `any` type.
 */
private abstract class TcbDirectiveTypeOpBase(
  private val tcb: Context,
  protected val scope: Scope,
  protected val node: `TmplAstElement|TmplAstTemplate`,
  protected val dir: TmplDirectiveMetadata
) : TcbOp() {

  // The statement generated by this operation is only used to declare the directive's type and
  // won't report diagnostics by itself, so the operation is marked as optional to avoid
  // generating declarations for directives that don't have any inputs/outputs.
  override val optional get() = true

  override fun execute(): Identifier {
    val type: Expression
    val cls = dir.typeScriptClass
    if (cls != null) {
      val clsId = tcb.env.reference(cls)
      type = Expression {
        append(clsId)
        val typeParameters = cls.typeParameters
        if (typeParameters.isNotEmpty()) {
          append("<")
          typeParameters.joinToString(", ") { "any" }
          append(">")
        }
      }
    }
    else {
      type = Expression("any")
    }
    val id = this.tcb.allocateId(this.node.startSourceSpan, ExpressionIdentifier.Directive)
    this.scope.addStatement(tsDeclareVariable(id, type))
    return id
  }
}

/**
 * A `TcbOp` which constructs an instance of a non-generic directive _without_ setting any of its
 * inputs. Inputs are later set in the `TcbDirectiveInputsOp`. Type checking was found to be
 * faster when done in this way as opposed to `TcbDirectiveCtorOp` which is only necessary when the
 * directive is generic.
 *
 * Executing this operation returns a reference to the directive instance variable with its inferred
 * type.
 */
private class TcbNonGenericDirectiveTypeOp(tcb: Context, scope: Scope, node: `TmplAstElement|TmplAstTemplate`, dir: TmplDirectiveMetadata)
  : TcbDirectiveTypeOpBase(tcb, scope, node, dir) {
  /**
   * Creates a variable declaration for this op's directive of the argument type. Returns the id of
   * the newly created variable.
   */
  override fun execute(): Identifier {
    assert(!dir.isGeneric)
    return super.execute()
  }
}

/**
 * A `TcbOp` which constructs an instance of a generic directive with its generic parameters set
 * to `any` type. This op is like `TcbDirectiveTypeOp`, except that generic parameters are set to
 * `any` type. This is used for situations where we want to avoid inlining.
 *
 * Executing this operation returns a reference to the directive instance variable with its generic
 * type parameters set to `any`.
 */
private class TcbGenericDirectiveTypeWithAnyParamsOp(tcb: Context, scope: Scope, node: `TmplAstElement|TmplAstTemplate`, dir: TmplDirectiveMetadata)
  : TcbDirectiveTypeOpBase(tcb, scope, node, dir) {

  override fun execute(): Identifier {
    assert(dir.isGeneric)
    return super.execute()
  }
}

/**
 * A `TcbOp` which creates a variable for a local ref in a template.
 * The initializer for the variable is the variable expression for the directive, template, or
 * element the ref refers to. When the reference is used in the template, those TCB statements will
 * access this variable as well. For example:
 * ```
 * var _t1 = document.createElement('div');
 * var _t2 = _t1;
 * _t2.value
 * ```
 * This operation supports more fluent lookups for the `TemplateTypeChecker` when getting a symbol
 * for a reference. In most cases, this isn't essential; that is, the information for the symbol
 * could be gathered without this operation using the `BoundTarget`. However, for the case of
 * ng-template references, we will need this reference variable to not only provide a location in
 * the shim file, but also to narrow the variable to the correct `TemplateRef<T>` type rather than
 * `TemplateRef<any>` (this work is still TODO).
 *
 * Executing this operation returns a reference to the directive instance variable with its inferred
 * type.
 */
private class TcbReferenceOp(
  private val tcb: Context,
  private val scope: Scope,
  private val node: TmplAstReference,
  private val host: `TmplAstElement|TmplAstTemplate`,
  private val target: `TmplDirectiveMetadata|TmplAstElement|TmplAstTemplate`
) : TcbOp() {

  // The statement generated by this operation is only used to for the Type Checker
  // so it can map a reference variable in the template directly to a node in the TCB.
  override val optional get() = true

  override fun execute(): Identifier {
    val id = this.tcb.allocateId(this.node.keySpan)
    val reference = Expression {
      append(if (target is TmplAstDirectiveContainer)
               scope.resolve(target)
             else
               scope.resolve(host, target as TmplDirectiveMetadata),
             node.valueSpan)
    }

    // The reference is either to an element, an <ng-template> node, or to a directive on an
    // element or template.
    val initializer = Expression {
      if ((target is TmplAstElement && !tcb.env.config.checkTypeOfDomReferences) ||
          !tcb.env.config.checkTypeOfNonDomReferences) {
        // References to DOM nodes are pinned to 'any' when `checkTypeOfDomReferences` is `false`.
        // References to `TemplateRef`s and directives are pinned to 'any' when
        // `checkTypeOfNonDomReferences` is `false`.
        append(reference).append(" as any")
      }
      else if (target is TmplAstTemplate) {
        // Direct references to an <ng-template> node simply require a value of type
        // `TemplateRef<any>`. To get this, an expression of the form
        // `(_t1 as any as TemplateRef<any>)` is constructed.
        append("(")
        append(reference).append(" as any as ")
        append(tcb.env.referenceExternalType(ANGULAR_CORE_PACKAGE, TEMPLATE_REF))
        append("<any>)")
      }
      else {
        append(reference)
      }
    }
    this.scope.addStatement(tsCreateVariable(id, initializer))
    return id
  }
}

/**
 * A `TcbOp` which is used when the target of a reference is missing. This operation generates a
 * variable of type any for usages of the invalid reference to resolve to. The invalid reference
 * itself is recorded out-of-band.
 */
private class TcbInvalidReferenceOp(private val tcb: Context, private val scope: Scope) : TcbOp() {

  // The declaration of a missing reference is only needed when the reference is resolved.
  override val optional get() = true

  override fun execute(): Identifier {
    val id = this.tcb.allocateId()
    this.scope.addStatement(tsCreateVariable(id, Expression("null as any")))
    return id
  }
}

/**
 * A `TcbOp` which constructs an instance of a directive with types inferred from its inputs. The
 * inputs themselves are not checked here; checking of inputs is achieved in `TcbDirectiveInputsOp`.
 * Any errors reported in this statement are ignored, as the type constructor call is only present
 * for type-inference.
 *
 * When a Directive is generic, it is required that the TCB generates the instance using this method
 * in order to infer the type information correctly.
 *
 * Executing this operation returns a reference to the directive instance variable with its inferred
 * type.
 */
private class TcbDirectiveCtorOp(
  private val tcb: Context,
  private val scope: Scope,
  private val node: `TmplAstElement|TmplAstTemplate`,
  private val dir: TmplDirectiveMetadata) : TcbOp() {

  // The statement generated by this operation is only used to infer the directive"s type and
  // won"t report diagnostics by itself, so the operation is marked as optional.
  override val optional = true

  override fun execute(): Identifier {
    val id = this.tcb.allocateId(this.node.startSourceSpan, ExpressionIdentifier.Directive)

    val genericInputs = mutableMapOf<String, TcbDirectiveInput>()
    val boundAttrs = getBoundAttributes(this.dir, this.node)

    for (attr in boundAttrs) {
      // Skip text attributes if configured to do so.
      if (!this.tcb.env.config.checkTypeOfAttributes &&
          attr.attribute is TmplAstTextAttribute) {
        continue
      }
      for (input in attr.inputs) {
        val fieldName = input.fieldName
        val isTwoWayBinding = false
        // Skip the field if an attribute has already been bound to it; we can't have a duplicate
        // key in the type constructor call.
        if (fieldName == null || genericInputs.contains(fieldName)) {
          continue
        }

        val expression = translateInput(attr.attribute, this.tcb, this.scope)

        genericInputs[fieldName] = TcbDirectiveBoundInput(
          field = fieldName,
          expression = expression,
          sourceSpan = attr.attribute.sourceSpan,
          isTwoWayBinding = isTwoWayBinding,
        )
      }
    }

    // Add unset directive inputs for each of the remaining unset fields.
    for (input in this.dir.inputs.values) {
      val fieldName = input.fieldName
      if (fieldName != null && !genericInputs.contains(fieldName)) {
        genericInputs[fieldName] = TcbDirectiveUnsetInput(field = fieldName)
      }
    }

    // Call the type constructor of the directive to infer a type, and assign the directive
    // instance.
    val typeCtor = Expression {
      withIgnoreDiagnostics {
        append(tcbCallTypeCtor(dir, tcb, genericInputs.values))
      }
    }
    this.scope.addStatement(tsCreateVariable(id, typeCtor))
    return id
  }

  override fun circularFallback(): `TcbOp|Identifier` {
    return TcbDirectiveCtorCircularFallbackOp(this.tcb, this.scope, this.node, this.dir)
  }
}

/**
 * A `TcbOp` which generates code to check input bindings on an element that correspond with the
 * members of a directive.
 *
 * Executing this operation returns nothing.
 */
private class TcbDirectiveInputsOp(
  private val tcb: Context,
  private val scope: Scope,
  private val node: `TmplAstElement|TmplAstTemplate`,
  private val dir: TmplDirectiveMetadata) : TcbOp() {

  override val optional get() = false

  override fun execute(): Identifier? {
    var dirId: Identifier? = null

    // TODO(joost): report duplicate properties

    val boundAttrs = getBoundAttributes(this.dir, this.node)

    for (attr in boundAttrs) {
      // For bound inputs, the property is assigned the binding expression.
      val expr = widenBinding(translateInput(attr.attribute, this.tcb, this.scope), this.tcb)

      var assignment: Expression = expr

      for (input in attr.inputs) {
        val fieldName = input.fieldName
        val isSignal = input.isSignal
        var target: Expression

        // Note: There is no special logic for transforms/coercion with signal inputs.
        // For signal inputs, a `transformType` will never be set as we do not capture
        // the transform in the compiler metadata. Signal inputs incorporate their
        // transform write type into their member type, and we extract it below when
        // setting the `WriteT` of such `InputSignalWithTransform<_, WriteT>`.

        if (fieldName != null && input.isCoerced && !input.isSignal) {
          var type: Expression

          val transformType = input.transformType
          if (transformType != null) {
            type = tcb.env.referenceType(transformType)
          }
          else {
            // The input has a coercion declaration which should be used instead of assigning the
            // expression into the input field directly. To achieve this, a variable is declared
            // with a type of `typeof Directive.ngAcceptInputType_fieldName` which is then used as
            // target of the assignment.
            val dirTypeRef: JSType = dir.entityJsType!!
            type = tsCreateTypeQueryForCoercedInput(tcb.env.referenceType(dirTypeRef), fieldName)
          }

          val id = this.tcb.allocateId()
          this.scope.addStatement(tsDeclareVariable(id, type))

          target = Expression { append(id, attr.attribute.keySpan) }
        }
        else if (fieldName == null) {
          // If no coercion declaration is present nor is the field declared (i.e. the input is
          // declared in a `@Directive` or `@Component` decorator's `inputs` property) there is no
          // assignment target available, so this field is skipped.
          continue
        }
        else if (!this.tcb.env.config.honorAccessModifiersForInputBindings && input.isRestricted) {
          // If strict checking of access modifiers is disabled and the field is restricted
          // (i.e. private/protected/readonly), generate an assignment into a temporary variable
          // that has the type of the field. This achieves type-checking but circumvents the access
          // modifiers.
          if (dirId == null) {
            dirId = this.scope.resolve(this.node, this.dir)
          }

          val id = this.tcb.allocateId()
          val type = Expression { append(dirId!!).append("[\"${fieldName}\"]") }
          val temp = tsDeclareVariable(id, type)
          this.scope.addStatement(temp)
          target = Expression { append(id, attr.attribute.keySpan) }
        }
        else {
          if (dirId == null) {
            dirId = this.scope.resolve(this.node, this.dir)
          }

          // To get errors assign directly to the fields on the instance, using property access
          // when possible. String literal fields may not be valid JS identifiers so we use
          // literal element access instead for those cases.
          target = if (StringUtil.isJavaIdentifier(fieldName))
            Expression { append(dirId).append(".").append(fieldName, attr.attribute.keySpan) }
          else
            Expression { append(dirId).append("[\"").append(fieldName, attr.attribute.keySpan).append("\"]") }
        }

        // For signal inputs, we unwrap the target `InputSignal`. Note that
        // we intentionally do the following things:
        //   1. keep the direct access to `dir.[field]` so that modifiers are honored.
        //   2. follow the existing pattern where multiple targets assign a single expression.
        //      This is a significant requirement for language service auto-completion.
        if (isSignal) {
          val inputSignalBrandWriteSymbol = this.tcb.env.referenceExternalSymbol(
            R3Identifiers.InputSignalBrandWriteType.moduleName,
            R3Identifiers.InputSignalBrandWriteType.name)

          target = Expression { append(target).append("[").append(inputSignalBrandWriteSymbol).append("]") }
        }

        // Two-way bindings accept `T | WritableSignal<T>` so we have to unwrap the value.
        if (input.isTwoWayBinding && this.tcb.env.config.allowSignalsInTwoWayBindings) {
          assignment = unwrapWritableSignal(assignment, this.tcb)
        }

        // Finally the assignment is extended by assigning it into the target expression.
        assignment = Expression { append(target).append(" = ").append(assignment) }
      }

      // Ignore diagnostics for text attributes if configured to do so.
      if (!tcb.env.config.checkTypeOfAttributes &&
          attr.attribute is TmplAstTextAttribute) {
        assignment = Expression {
          withIgnoreDiagnostics {
            append(assignment)
          }
        }
      }

      this.scope.addStatement(assignment)
    }

    // WebStorm handled by Html validator
    // this.checkRequiredInputs(seenRequiredInputs)

    return null
  }
}

/**
 * A `TcbOp` which is used to generate a fallback expression if the inference of a directive type
 * via `TcbDirectiveCtorOp` requires a reference to its own type. This can happen using a template
 * reference:
 *
 * ```html
 * <some-cmp #ref [prop]="ref.foo"></some-cmp>
 * ```
 *
 * In this case, `TcbDirectiveCtorCircularFallbackOp` will add a second inference of the directive
 * type to the type-check block, this time calling the directive's type constructor without any
 * input expressions. This infers the widest possible supertype for the directive, which is used to
 * resolve any recursive references required to infer the real type.
 */
private class TcbDirectiveCtorCircularFallbackOp(
  private val tcb: Context,
  private val scope: Scope,
  private val node: `TmplAstElement|TmplAstTemplate`,
  private val dir: TmplDirectiveMetadata
) : TcbOp() {

  override val optional get() = false

  override fun execute(): Identifier {
    val id = this.tcb.allocateId()
    val typeCtor = this.tcb.env.typeCtorFor(this.dir)
    val circularPlaceholder = Expression {
      append(typeCtor)
      append("(null!)")
    }
    this.scope.addStatement(tsCreateVariable(id, circularPlaceholder))
    return id
  }
}

/**
 * A `TcbOp` that finds and flags control flow nodes that interfere with content projection.
 *
 * Context:
 * Control flow blocks try to emulate the content projection behavior of `*ngIf` and `*ngFor`
 * in order to reduce breakages when moving from one syntax to the other (see #52414), however the
 * approach only works if there's only one element at the root of the control flow expression.
 * This means that a stray sibling node (e.g. text) can prevent an element from being projected
 * into the right slot. The purpose of the `TcbOp` is to find any places where a node at the root
 * of a control flow expression *would have been projected* into a specific slot, if the control
 * flow node didn't exist.
 *//*
private class TcbControlFlowContentProjectionOp(
  private val tcb: Context,
  private val element: TmplAstElement,
  private val ngContentSelectors: List<String>,
  private val componentName: String) : TcbOp() {

  private val category: ts.DiagnosticCategory

  init {
    // We only need to account for `error` and `warning` since
    // this check won't be enabled for `suppress`.
    this.category = if (tcb.env.config.controlFlowPreventingContentProjection == ControlFlowPreventingContentProjectionKind.Error)
      ts.DiagnosticCategory.Error
    else
      ts.DiagnosticCategory.Warning
  }

  override val optional get() = false

  override fun execute(): Identifier? {
    val controlFlowToCheck = this.findPotentialControlFlowNodes()

    if (controlFlowToCheck.size > 0) {
      val matcher = SelectorMatcher<String>()

      for (selector in this.ngContentSelectors) {
        // `*` is a special selector for the catch-all slot.
        if (selector != "*") {
          matcher.addSelectables(CssSelector.parse(selector), selector)
        }
      }

      for (root in controlFlowToCheck) {
        for (child in root.children) {
          if (child is TmplAstElement || child is TmplAstTemplate) {
            matcher.match(createCssSelectorFromNode(child), (_, originalSelector) => {
              this.tcb.oobRecorder.controlFlowPreventingContentProjection(
                this.tcb.id, this.category, child, this.componentName, originalSelector, root,
                this.tcb.hostPreserveWhitespaces)
            })
          }
        }
      }
    }

    return null
  }

  private fun findPotentialControlFlowNodes() {
    val result = mutableListOf<Angular2HtmlBlock>()

    for (child in this.element.children) {
      if (child is TmplAstForLoopBlock) {
        if (this.shouldCheck(child)) {
          result.add(child)
        }
        if (child.empty != null && this.shouldCheck(child.empty)) {
          result.add(child.empty)
        }
      }
      else if (child is TmplAstIfBlock) {
        for (branch in child.branches) {
          if (this.shouldCheck(branch)) {
            result.add(branch)
          }
        }
      }
      else if (child is TmplAstSwitchBlock) {
        for (current in child.cases) {
          if (this.shouldCheck(current)) {
            result.add(current)
          }
        }
      }
    }

    return result
  }

  private fun shouldCheck(node: TmplAstNode): Boolean {
    // Skip nodes with less than two children since it's impossible
    // for them to run into the issue that we're checking for.
    if (node.children.size < 2) {
      return false
    }

    var hasSeenRootNode = false

    // Check the number of root nodes while skipping empty text where relevant.
    for (child in node.children) {
      // Normally `preserveWhitspaces` would have been accounted for during parsing, however
      // in `ngtsc/annotations/component/src/resources.ts#parseExtractedTemplate` we enable
      // `preserveWhitespaces` to preserve the accuracy of source maps diagnostics. This means
      // that we have to account for it here since the presence of text nodes affects the
      // content projection behavior.
      if (!(child is TmplAstText) || this.tcb.hostPreserveWhitespaces ||
          child.value.trim().size > 0) {
        // Content projection will be affected if there's more than one root node.
        if (hasSeenRootNode) {
          return true
        }
        hasSeenRootNode = true
      }
    }

    return false
  }
}
*/

/**
 * A `TcbOp` which generates code to check "unclaimed inputs" - bindings on an element which were
 * not attributed to any directive or component, and are instead processed against the HTML element
 * itself.
 *
 * Currently, only the expressions of these bindings are checked. The targets of the bindings are
 * checked against the DOM schema via a `TcbDomSchemaCheckerOp`.
 *
 * Executing this operation returns nothing.
 */
private class TcbUnclaimedInputsOp(
  private val tcb: Context,
  private val scope: Scope,
  private val element: TmplAstElement,
  private val claimedInputs: Set<String>
) : TcbOp() {

  override val optional get() = false

  override fun execute(): Identifier? {
    // `this.inputs` contains only those bindings not matched by any directive. These bindings go to
    // the element itself.
    var elId: Identifier? = null

    // TODO(alxhub): this could be more efficient.
    for (binding in this.element.inputs.values) {
      val isPropertyBinding =
        binding.type == BindingType.Property || binding.type == BindingType.TwoWay

      if (isPropertyBinding && this.claimedInputs.contains(binding.name)) {
        // Skip this binding as it was claimed by a directive.
        continue
      }

      val expr = widenBinding(tcbExpression(binding.value, this.tcb, this.scope), this.tcb)

      if (this.tcb.env.config.checkTypeOfDomBindings && isPropertyBinding) {
        if (binding.name != "style" && binding.name != "class") {
          if (elId == null) {
            elId = this.scope.resolve(this.element)
          }
          // A direct binding to a property.
          val propertyName = Angular2AttributeNameParser.ATTR_TO_PROP_MAPPING[binding.name] ?: binding.name
          this.scope.addStatement {
            append(elId).append("[\"").append(propertyName, binding.keySpan).append("\"]")
            append(" = ")
            append(expr)
            append(";")
          }
        }
        else {
          this.scope.addStatement { append(expr).append(";") }
        }
      }
      else {
        // A binding to an animation, attribute, class or style. For now, only validate the right-
        // hand side of the expression.
        // TODO: properly check class and style bindings.
        this.scope.addStatement { append(expr).append(";") }
      }
    }

    return null
  }
}

/**
 * A `TcbOp` which generates code to check event bindings on an element that correspond with the
 * outputs of a directive.
 *
 * Executing this operation returns nothing.
 */
private class TcbDirectiveOutputsOp(
  private val tcb: Context, private val scope: Scope, private val node: `TmplAstElement|TmplAstTemplate`,
  private val dir: TmplDirectiveMetadata) : TcbOp() {

  override val optional get() = false

  override fun execute(): Identifier? {
    var dirId: Identifier? = null
    val outputs = dir.outputs

    for (output in this.node.outputs.values) {
      if (output.type == ParsedEventType.Animation ||
          !outputs.containsKey(output.name)) {
        continue
      }

      if (this.tcb.env.config.checkTypeOfOutputEvents && output.name.endsWith(OUTPUT_CHANGE_SUFFIX)) {
        val inputName = output.name.removeSuffix(OUTPUT_CHANGE_SUFFIX)
        isSplitTwoWayBinding(inputName, output, this.node.inputs, this.tcb)
      }
      // TODO(alxhub): consider supporting multiple fields with the same property name for outputs.
      val field = outputs[output.name]!!.fieldName

      if (dirId == null) {
        dirId = this.scope.resolve(this.node, this.dir)
      }
      val outputField = Expression {
        append(dirId).append("[\"$field\"]", output.keySpan)
      }
      if (this.tcb.env.config.checkTypeOfOutputEvents) {
        // For strict checking of directive events, generate a call to the `subscribe` method
        // on the directive's output field to let type information flow into the handler function's
        // `$event` parameter.
        val handler = tcbCreateEventHandler(output, this.tcb, this.scope, EventParamType.Infer)
        this.scope.addStatement {
          append(outputField)
          append(".subscribe(")
          append(handler)
          append(");")
        }
      }
      else {
        // If strict checking of directive events is disabled:
        //
        // * We still generate the access to the output field as a statement in the TCB so consumers
        //   of the `TemplateTypeChecker` can still find the node for the class member for the
        //   output.
        // * Emit a handler function where the `$event` parameter has an explicit `any` type.
        this.scope.addStatement(outputField)
        val handler = tcbCreateEventHandler(output, this.tcb, this.scope, EventParamType.Any)
        this.scope.addStatement(handler)
      }
    }

    return null
  }
}

/**
 * A `TcbOp` which generates code to check "unclaimed outputs" - event bindings on an element which
 * were not attributed to any directive or component, and are instead processed against the HTML
 * element itself.
 *
 * Executing this operation returns nothing.
 */
private class TcbUnclaimedOutputsOp(
  private val tcb: Context,
  private val scope: Scope,
  private val element: TmplAstElement,
  private val claimedOutputs: Set<String>) : TcbOp() {

  override val optional get() = false

  override fun execute(): Identifier? {
    var elId: Identifier? = null

    // TODO(alxhub): this could be more efficient.
    for (output in this.element.outputs.values) {
      if (this.claimedOutputs.contains(output.name)) {
        // Skip this event handler as it was claimed by a directive.
        continue
      }

      if (this.tcb.env.config.checkTypeOfOutputEvents && output.name.endsWith(OUTPUT_CHANGE_SUFFIX)) {
        val inputName = output.name.removeSuffix(OUTPUT_CHANGE_SUFFIX)
        if (isSplitTwoWayBinding(inputName, output, this.element.inputs, this.tcb)) {
          // Skip this event handler as the error was already handled.
          continue
        }
      }

      if (output.type == ParsedEventType.Animation) {
        // Animation output bindings always have an `$event` parameter of type `AnimationEvent`.
        val eventType = if (this.tcb.env.config.checkTypeOfAnimationEvents)
          this.tcb.env.referenceExternalType("@angular/animations", "AnimationEvent")
        else
          Expression("any")

        val handler = tcbCreateEventHandler(output, this.tcb, this.scope, eventType)
        this.scope.addStatement(handler)
      }
      else if (this.tcb.env.config.checkTypeOfDomEvents) {
        // If strict checking of DOM events is enabled, generate a call to `addEventListener` on
        // the element instance so that TypeScript's type inference for
        // `HTMLElement.addEventListener` using `HTMLElementEventMap` to infer an accurate type for
        // `$event` depending on the event name. For unknown event names, TypeScript resorts to the
        // base `Event` type.
        val handler = tcbCreateEventHandler(output, this.tcb, this.scope, EventParamType.Infer)

        if (elId == null) {
          elId = this.scope.resolve(this.element)
        }
        this.scope.addStatement {
          append(elId)
          append(".addEventListener(\"")
          append(output.name, output.keySpan)
          append("\", ")
          append(handler)
          append(");")
        }
      }
      else {
        // If strict checking of DOM inputs is disabled, emit a handler function where the `$event`
        // parameter has an explicit `any` type.
        val handler = tcbCreateEventHandler(output, this.tcb, this.scope, EventParamType.Any)
        this.scope.addStatement(handler)
      }
    }

    return null
  }
}

/**
 * A `TcbOp` which renders a variable defined inside of block syntax (e.g. `@if (expr; as var) {}`).
 *
 * Executing this operation returns the identifier which can be used to refer to the variable.
 *//*
private class TcbBlockVariableOp(
  private val tcb: Context,
  private val scope: Scope,
  private val initializer: Expression,
  private val variable: TmplAstVariable
) : TcbOp() {

  override val optional get() = false

  override fun execute(): Identifier {
    val id = this.tcb.allocateId()
    addParseSpanInfo(id, this.variable.keySpan)
    val variable = tsCreateVariable(id, wrapForTypeChecker(this.initializer))
    addParseSpanInfo(variable.declarationList.declarations[0], this.variable.sourceSpan)
    this.scope.addStatement(variable)
    return id
  }
}*/

/**
 * A `TcbOp` which renders a variable that is implicitly available within a block (e.g. `$count`
 * in a `@for` block).
 *
 * Executing this operation returns the identifier which can be used to refer to the variable.
 *//*

private class TcbBlockImplicitVariableOp(
  private val tcb: Context,
  private val scope: Scope,
  private val type: JSType,
  private val variable: TmplAstVariable
) : TcbOp() {

  override val optional get() = true

  override fun execute(): Identifier {
    val id = this.tcb.allocateId()
    addParseSpanInfo(id, this.variable.keySpan)
    val variable = tsDeclareVariable(id, this.type)
    addParseSpanInfo(variable.declarationList.declarations[0], this.variable.sourceSpan)
    this.scope.addStatement(variable)
    return id
  }
}
*/


/**
 * A `TcbOp` which renders an `if` template block as a TypeScript `if` statement.
 *
 * Executing this operation returns nothing.
 *//*
private class TcbIfOp(
  private val tcb: Context,
  private val scope: Scope,
  private val block: TmplAstIfBlock
) : TcbOp() {
  private val expressionScopes = mutableMapOf<TmplAstIfBlockBranch, Scope>()

  override val optional get() = false

  override fun execute(): Identifier? {
    val root = this.generateBranch(0)
    root && this.scope.addStatement(root)
    return null
  }

  private fun generateBranch(index: Int): Statement? {
    val branch = this.block.branches[index]

    if (!branch) {
      return null
    }

    // If the expression is null, it means that it"s an `else` statement.
    if (branch.expression == null) {
      val branchScope = this.getBranchScope(this.scope, branch, index)
      return ts.factory.createBlock(branchScope.render())
    }

    // We need to process the expression first so it gets its own scope that the body of the
    // conditional will inherit from. We do this, because we need to declare a separate variable
    // for the case where the expression has an alias _and_ because we need the processed
    // expression when generating the guard for the body.
    val expressionScope = Scope.forNodes(this.tcb, this.scope, branch, [], null)
    expressionScope.render().forEach(stmt => this.scope.addStatement(stmt))
    this.expressionScopes.set(branch, expressionScope)

    val expression = branch.expressionAlias == null ?
    tcbExpression(branch.expression, this.tcb, expressionScope) :
    expressionScope.resolve(branch.expressionAlias)
    val bodyScope = this.getBranchScope(expressionScope, branch, index)

    return ts.factory.createIfStatement(
      expression, ts.factory.createBlock(bodyScope.render()), this.generateBranch(index + 1))
  }

  private fun getBranchScope(parentScope: Scope, branch: TmplAstIfBlockBranch, index: Int): Scope {
    val checkBody = this.tcb.env.config.checkControlFlowBodies
    return Scope.forNodes(
      this.tcb, parentScope, null, checkBody ? branch . children :[],
    checkBody ? this.generateBranchGuard(index) : null)
  }

  private fun generateBranchGuard(index: Int): Expression? {
    var guard: Expression? = null

    // Since event listeners are inside callbacks, type narrowing doesn't apply to them anymore.
    // To recreate the behavior, we generate an expression that negates all the values of the
    // branches _before_ the current one, and then we add the current branch's expression on top.
    // For example `@if (expr === 1) {} @else if (expr === 2) {} @else if (expr === 3)`, the guard
    // for the last expression will be `!(expr === 1) && !(expr === 2) && expr === 3`.
    for (var i = 0; i <= index; i++) {
      val branch = this.block.branches[i]

      // Skip over branches without an expression.
      if (branch.expression == null) {
        continue
      }

      // This shouldn't happen since all the state is handled
      // internally, but we have the check just in case.
      if (!this.expressionScopes.contains(branch)) {
        throw Error(`Could not determine expression scope in branch at index ${i}`)
      }

      val expressionScope = this.expressionScopes.get(branch)!
      var expression: Expression

      if (branch.expressionAlias == null) {
        // We need to recreate the expression and mark it to be ignored for diagnostics,
        // because it was already checked as a part of the block's condition and we don't
        // want it to produce a duplicate diagnostic.
        expression = tcbExpression(branch.expression, this.tcb, expressionScope)
        markIgnoreDiagnostics(expression)
      }
      else {
        expression = expressionScope.resolve(branch.expressionAlias)
      }

      // The expressions of the preceding branches have to be negated
      // (e.g. `expr` becomes `!(expr)`) when comparing in the guard, except
      // for the branch's own expression which is preserved as is.
      val comparisonExpression = i == index ?
      expression :
      ts.factory.createPrefixUnaryExpression(
        ts.SyntaxKind.ExclamationToken, ts.factory.createParenthesizedExpression(expression))

      // Finally add the expression to the guard with an && operator.
      guard = guard == null ?
      comparisonExpression :
      ts.factory.createBinaryExpression(
        guard, ts.SyntaxKind.AmpersandAmpersandToken, comparisonExpression)
    }

    return guard
  }
}
*/

/**
 * A `TcbOp` which renders a `switch` block as a TypeScript `switch` statement.
 *
 * Executing this operation returns nothing.
 *//*
private class TcbSwitchOp(private val tcb: Context, private val scope: Scope, private val block: TmplAstSwitchBlock) : TcbOp() {

  override val optional get() = false

  override fun execute(): Identifier? {
    val switchExpression = tcbExpression(this.block.expression, this.tcb, this.scope)
    val clauses = this.block.cases.map(current => {
      val checkBody = this.tcb.env.config.checkControlFlowBodies
      val clauseScope = Scope.forNodes(
        this.tcb, this.scope, null, checkBody ? current . children :[],
      checkBody ? this.generateGuard(current, switchExpression) : null)
      val statements = [...clauseScope.render(), ts.factory.createBreakStatement()]

      return current.expression == null ?
      ts.factory.createDefaultClause(statements) :
      ts.factory.createCaseClause(
        tcbExpression(current.expression, this.tcb, clauseScope), statements)
    })

    this.scope.addStatement(
      ts.factory.createSwitchStatement(switchExpression, ts.factory.createCaseBlock(clauses)))

    return null
  }

  private fun generateGuard(node: TmplAstSwitchBlockCase, switchValue: Expression): Expression? {
    // For non-default cases, the guard needs to compare against the case value, e.g.
    // `switchExpression === caseExpression`.
    if (node.expression != null) {
      // The expression needs to be ignored for diagnostics since it has been checked already.
      val expression = tcbExpression(node.expression, this.tcb, this.scope)
      markIgnoreDiagnostics(expression)
      return ts.factory.createBinaryExpression(
        switchValue, ts.SyntaxKind.EqualsEqualsEqualsToken, expression)
    }

    // To fully narrow the type in the default case, we need to generate an expression that negates
    // the values of all of the other expressions. For example:
    // @switch (expr) {
    //   @case (1) {}
    //   @case (2) {}
    //   @default {}
    // }
    // Will produce the guard `expr !== 1 && expr !== 2`.
    var guard: Expression? = null

    for (current in this.block.cases) {
      if (current.expression == null) {
        continue
      }

      // The expression needs to be ignored for diagnostics since it has been checked already.
      val expression = tcbExpression(current.expression, this.tcb, this.scope)
      markIgnoreDiagnostics(expression)
      val comparison = ts.factory.createBinaryExpression(
        switchValue, ts.SyntaxKind.ExclamationEqualsEqualsToken, expression)

      if (guard == null) {
        guard = comparison
      }
      else {
        guard = ts.factory.createBinaryExpression(
          guard, ts.SyntaxKind.AmpersandAmpersandToken, comparison)
      }
    }

    return guard
  }
}
*/
/**
 * A `TcbOp` which renders a `for` block as a TypeScript `for...of` loop.
 *
 * Executing this operation returns nothing.
 *//*
private class TcbForOfOp(private val tcb: Context, private val scope: Scope, private val block: TmplAstForLoopBlock) : TcbOp() {

  override val optional = false

  override fun execute(): Identifier? {
    val loopScope = Scope.forNodes(
      this.tcb, this.scope, this.block,
      this.tcb.env.config.checkControlFlowBodies ? this.block.children : [], null)
    val initializerId = loopScope.resolve(this.block.item)
    if (!ts.isIdentifier(initializerId)) {
      throw Error(
        `Could not resolve for loop variable ${this.block.item.name} to an identifier`)
    }
    val initializer = ts.factory.createVariableDeclarationList(
      [ts.factory.createVariableDeclaration(initializerId)], ts.NodeFlags.Const)
    addParseSpanInfo(initializer, this.block.item.keySpan)
    // It's common to have a for loop over a nullable value (e.g. produced by the `async` pipe).
    // Add a non-null expression to allow such values to be assigned.
    val expression = ts.factory.createNonNullExpression(
      tcbExpression(this.block.expression, this.tcb, loopScope))
    val trackTranslator = TcbForLoopTrackTranslator(this.tcb, loopScope, this.block)
    val trackExpression = trackTranslator.translate(this.block.trackBy)
    val statements = [
    ...loopScope.render(),
    ts.factory.createExpressionStatement(trackExpression),
    ]

    this.scope.addStatement(ts.factory.createForOfStatement(
      null, initializer, expression, ts.factory.createBlock(statements)))

    return null
  }
}*/

/**
 * Value used to break a circular reference between `TcbOp`s.
 *
 * This value is returned whenever `TcbOp`s have a circular dependency. The expression is a non-null
 * assertion of the null value (in TypeScript, the expression `null!`). This construction will infer
 * the least narrow type for whatever it's assigned to.
 */
private val INFER_TYPE_FOR_CIRCULAR_OP_EXPR = Identifier("null!")

/**
 * Overall generation context for the type check block.
 *
 * `Context` handles operations during code generation which are global with respect to the whole
 * block. It's responsible for variable name allocation and management of any imports needed. It
 * also contains the template metadata itself.
 */
internal class Context(
  val env: Environment,
  val oobRecorder: OutOfBandDiagnosticRecorder,
  val id: TemplateId,
  val boundTarget: BoundTarget
) {
  private var nextId = 1

  /**
   * Allocate a new variable name for use within the `Context`.
   *
   * Currently this uses a monotonically increasing counter, but in the future the variable name
   * might change depending on the type of data being stored.
   */
  fun allocateId(sourceSpan: TextRange? = null, kind: ExpressionIdentifier? = null): Identifier {
    return Identifier("_t${this.nextId++}", sourceSpan, kind)
  }

  fun getPipeByName(name: String?): Angular2Pipe? {
    return this.boundTarget.pipes[name]
  }
}

/**
 * Local scope within the type check block for a particular template.
 *
 * The top-level template and each nested `<ng-template>` have their own `Scope`, which exist in a
 * hierarchy. The structure of this hierarchy mirrors the syntactic scopes in the generated type
 * check block, where each nested template is encased in an `if` structure.
 *
 * As a template's `TcbOp`s are executed in a given `Scope`, statements are added via
 * `addStatement()`. When this processing is complete, the `Scope` can be turned into a `ts.Block`
 * via `renderToBlock()`.
 *
 * If a `TcbOp` requires the output of another, it can call `resolve()`.
 */
internal class Scope(private val tcb: Context, private val parent: Scope? = null, private val guard: Expression? = null) {
  /**
   * A queue of operations which need to be performed to generate the TCB code for this scope.
   *
   * This array can contain either a `TcbOp` which has yet to be executed, or a `Expression|null`
   * representing the memoized result of executing the operation. As operations are executed, their
   * results are written into the `opQueue`, overwriting the original operation.
   *
   * If an operation is in the process of being executed, it is temporarily overwritten here with
   * `INFER_TYPE_FOR_CIRCULAR_OP_EXPR`. This way, if a cycle is encountered where an operation
   * depends transitively on its own result, the inner operation will infer the least narrow type
   * that fits instead. This has the same semantics as TypeScript itself when types are referenced
   * circularly.
   */
  private val opQueue = mutableListOf<`TcbOp|Identifier`?>()

  /**
   * A map of `TmplAstElement`s to the index of their `TcbElementOp` in the `opQueue`
   */
  private val elementOpMap = mutableMapOf<TmplAstElement, Int>()

  /**
   * A map of maps which tracks the index of `TcbDirectiveCtorOp`s in the `opQueue` for each
   * directive on a `TmplAstElement` or `TmplAstTemplate` node.
   */
  private val directiveOpMap = mutableMapOf<`TmplAstElement|TmplAstTemplate`, Map<TmplDirectiveMetadata, Int>>()

  /**
   * A map of `TmplAstReference`s to the index of their `TcbReferenceOp` in the `opQueue`
   */
  private val referenceOpMap = mutableMapOf<TmplAstReference, Int>()

  /**
   * Map of immediately nested <ng-template>s (within this `Scope`) represented by `TmplAstTemplate`
   * nodes to the index of their `TcbTemplateContextOp`s in the `opQueue`.
   */
  private val templateCtxOpMap = mutableMapOf<TmplAstTemplate, Int>()

  /**
   * Map of variables declared on the template that created this `Scope` (represented by
   * `TmplAstVariable` nodes) to the index of their `TcbVariableOp`s in the `opQueue`, or to
   * pre-resolved variable identifiers.
   */
  private val varMap = mutableMapOf<TmplAstVariable, `Int|Identifier`>()

  /**
   * Statements for this template.
   *
   * Executing the `TcbOp`s in the `opQueue` populates this array.
   */
  private val statements = mutableListOf<Statement>()

  companion object {
    /**
     * Names of the for loop context variables and their types.
     */
    private val forLoopContextVariableTypes = mapOf(
      "\$first" to "boolean",
      "\$last" to "boolean",
      "\$even" to "boolean",
      "\$odd" to "boolean",
      "\$index" to "number",
      "\$count" to "number",
    )

    /**
     * Constructs a `Scope` given either a `TmplAstTemplate` or a list of `TmplAstNode`s.
     *
     * @param tcb the overall context of TCB generation.
     * @param parentScope the `Scope` of the parent template (if any) or `null` if this is the root
     * `Scope`.
     * @param scopedNode Node that provides the scope around the child nodes (e.g. a
     * `TmplAstTemplate` node exposing variables to its children).
     * @param children Child nodes that should be appended to the TCB.
     * @param guard an expression that is applied to this scope for type narrowing purposes.
     */
    @JvmStatic
    internal fun forNodes(
      tcb: Context, parentScope: Scope?,
      scopedNode: `TmplAstTemplate|TmplAstIfBlockBranch|TmplAstForLoopBlock`?,
      children: List<TmplAstNode>, guard: Expression?): Scope {
      val scope = Scope(tcb, parentScope, guard)

      // If given an actual `TmplAstTemplate` instance, then process any additional information it
      // has.
      if (scopedNode is TmplAstTemplate) {
        // The template"s variable declarations need to be added as `TcbVariableOp`s.
        val varMap = mutableMapOf<String, TmplAstVariable>()

        for (v in scopedNode.variables.values) {
          // Validate that variables on the `TmplAstTemplate` are only declared once.
          if (!varMap.contains(v.name)) {
            varMap[v.name] = v
          }
          else {
            val firstDecl = varMap[v.name]!!
            tcb.oobRecorder.duplicateTemplateVar(tcb.id, v, firstDecl)
          }
          scope.registerVariable(v, TcbTemplateVariableOp(tcb, scope, scopedNode, v))
        }
      }
      else if (scopedNode != null) {
        TODO("support blocks")
      }
      //else if (scopedNode is TmplAstIfBlockBranch) {
      //  val { expression, expressionAlias } = scopedNode
      //  if (expression != null && expressionAlias != null) {
      //    scope.registerVariable(expressionAlias,
      //                           TcbBlockVariableOp(
      //                             tcb, scope, tcbExpression(expression, tcb, scope), expressionAlias))
      //  }
      //}
      //else if (scopedNode is TmplAstForLoopBlock) {
      //  // Register the variable for the loop so it can be resolved by
      //  // children. It'll be declared once the loop is created.
      //  val loopInitializer = tcb.allocateId()
      //  addParseSpanInfo(loopInitializer, scopedNode.item.sourceSpan)
      //  scope.varMap.set(scopedNode.item, loopInitializer)
      //
      //  for (variable in scopedNode.contextVariables) {
      //    if (!this.forLoopContextVariableTypes.contains(variable.value)) {
      //      throw Error(`Unrecognized for loop context variable ${variable.name}`)
      //    }
      //
      //    val type =
      //      ts.factory.createKeywordTypeNode(this.forLoopContextVariableTypes.get(variable.value)!)
      //    scope.registerVariable(variable, TcbBlockImplicitVariableOp(tcb, scope, type, variable))
      //  }
      //}
      for (node in children) {
        scope.appendNode(node)
      }
      return scope
    }
  }

  /** Registers a local variable with a scope. */
  private fun registerVariable(variable: TmplAstVariable, op: TcbOp) {
    this.opQueue.add(op)
    val opIndex = this.opQueue.size - 1
    this.varMap[variable] = opIndex
  }

  /**
   * Look up a `Expression` representing the value of some operation in the current `Scope`,
   * including any parent scope(s). This method always returns a mutable clone of the
   * `Expression` with the comments cleared.
   *
   * @param node a `TmplAstNode` of the operation in question. The lookup performed will depend on
   * the type of this node:
   *
   * Assuming `directive` is not present, then `resolve` will return:
   *
   * * `TmplAstElement` - retrieve the expression for the element DOM node
   * * `TmplAstTemplate` - retrieve the template context variable
   * * `TmplAstVariable` - retrieve a template let- variable
   * * `TmplAstReference` - retrieve variable created for the local ref
   *
   * @param directive if present, a directive type on a `TmplAstElement` or `TmplAstTemplate` to
   * look up instead of the default for an element or template node.
   */
  fun resolve(node: `TmplAstElement|TmplAstTemplate|TmplAstVariable|TmplAstReference`,
              directive: TmplDirectiveMetadata? = null): Identifier {
    // Attempt to resolve the operation locally.
    val res = this.resolveLocal(node, directive)
    if (res != null) {
      return res
    }
    else if (this.parent != null) {
      // Check with the parent.
      return this.parent.resolve(node, directive)
    }
    else {
      throw Error("Could not resolve ${node} / ${directive}")
    }
  }

  /**
   * Add a statement to this scope.
   */
  fun addStatement(stmt: Statement) {
    this.statements.add(stmt)
  }

  fun addStatement(expr: Expression) {
    this.statements.add(Statement { append(expr).append(";") })
  }

  fun addStatement(builder: Expression.ExpressionBuilder.() -> Unit) {
    addStatement(Statement(builder))
  }

  /**
   * Get the statements.
   */
  fun render(): List<Statement> {
    for (i in opQueue.indices) {
      // Optional statements cannot be skipped when we are generating the TCB for use
      // by the TemplateTypeChecker.
      val skipOptional = !this.tcb.env.config.enableTemplateTypeChecker
      this.executeOp(i, skipOptional)
    }
    return this.statements
  }

  /**
   * Returns an expression of all template guards that apply to this scope, including those of
   * parent scopes. If no guards have been applied, null is returned.
   */
  fun guards(): Expression? {
    var parentGuards: Expression? = null
    if (this.parent != null) {
      // Start with the guards from the parent scope, if present.
      parentGuards = this.parent.guards()
    }

    if (this.guard == null) {
      // This scope does not have a guard, so return the parent's guards as is.
      return parentGuards
    }
    else if (parentGuards == null) {
      // There's no guards from the parent scope, so this scope's guard represents all available
      // guards.
      return this.guard
    }
    else {
      // Both the parent scope and this scope provide a guard, so create a combination of the two.
      // It is important that the parent guard is used as left operand, given that it may provide
      // narrowing that is required for this scope's guard to be valid.
      return Expression {
        append(parentGuards)
        append(" && ")
        append(this@Scope.guard)
      }
    }
  }

  private fun resolveLocal(
    ref: `TmplAstElement|TmplAstTemplate|TmplAstVariable|TmplAstReference`,
    directive: TmplDirectiveMetadata? = null): Identifier? {
    if (ref is TmplAstReference && this.referenceOpMap.contains(ref)) {
      return this.resolveOp(this.referenceOpMap[ref]!!)
    }
    else if (ref is TmplAstVariable && this.varMap.contains(ref)) {
      // Resolving a context variable for this template.
      // Execute the `TcbVariableOp` associated with the `TmplAstVariable`.
      val opIndexOrNode = this.varMap[ref]!!
      return if (opIndexOrNode is Int) this.resolveOp(opIndexOrNode) else (opIndexOrNode as Identifier)
    }
    else if (
      ref is TmplAstTemplate && directive == null &&
      this.templateCtxOpMap.contains(ref)) {
      // Resolving the context of the given sub-template.
      // Execute the `TcbTemplateContextOp` for the template.
      return this.resolveOp(this.templateCtxOpMap[ref]!!)
    }
    else if (
      (ref is TmplAstElement || ref is TmplAstTemplate) &&
      directive != null && this.directiveOpMap.contains(ref)) {
      // Resolving a directive on an element or sub-template.
      val dirMap = this.directiveOpMap[ref]!!
      if (dirMap.contains(directive)) {
        return this.resolveOp(dirMap[directive]!!)
      }
      else {
        return null
      }
    }
    else if (ref is TmplAstElement && this.elementOpMap.contains(ref)) {
      // Resolving the DOM node of an element in this template.
      return this.resolveOp(this.elementOpMap[ref]!!)
    }
    else {
      return null
    }
  }

  /**
   * Like `executeOp`, but assert that the operation actually returned `Expression`.
   */
  private fun resolveOp(opIndex: Int): Identifier {
    val res = this.executeOp(opIndex, /* skipOptional */ false)
    if (res == null) {
      throw Error("Error resolving operation, got null")
    }
    return res
  }

  /**
   * Execute a particular `TcbOp` in the `opQueue`.
   *
   * This method replaces the operation in the `opQueue` with the result of execution (once done)
   * and also protects against a circular dependency from the operation to itself by temporarily
   * setting the operation's result to a special expression.
   */
  private fun executeOp(opIndex: Int, skipOptional: Boolean): Identifier? {
    val op = this.opQueue[opIndex]
    if (op == null) return op
    if (op is Identifier) return op
    if (op !is TcbOp) throw IllegalStateException(op.javaClass.toString())

    if (skipOptional && op.optional) {
      return null
    }

    // Set the result of the operation in the queue to its circular fallback. If executing this
    // operation results in a circular dependency, this will prevent an infinite loop and allow for
    // the resolution of such cycles.
    this.opQueue[opIndex] = op.circularFallback()
    val res = op.execute()
    // Once the operation has finished executing, it's safe to cache the real result.
    this.opQueue[opIndex] = res
    return res
  }

  private fun appendNode(node: TmplAstNode) {
    if (node is TmplAstElement) {
      this.opQueue.add(TcbElementOp(this.tcb, this, node))
      this.elementOpMap[node] = this.opQueue.lastIndex
      if (this.tcb.env.config.controlFlowPreventingContentProjection != ControlFlowPreventingContentProjectionKind.Suppress) {
        this.appendContentProjectionCheckOp(node)
      }
      this.appendDirectivesAndInputsOfNode(node)
      this.appendOutputsOfNode(node)
      for (child in node.children) {
        this.appendNode(child)
      }
      this.checkAndAppendReferencesOfNode(node)
    }
    else if (node is TmplAstTemplate) {
      // Template children are rendered in a child scope.
      this.appendDirectivesAndInputsOfNode(node)
      this.appendOutputsOfNode(node)
      this.opQueue.add(TcbTemplateContextOp(this.tcb, this))
      this.templateCtxOpMap[node] = this.opQueue.lastIndex
      if (this.tcb.env.config.checkTemplateBodies) {
        this.opQueue.add(TcbTemplateBodyOp(this.tcb, this, node))
      }
      // WebStorm - this is done through HTML validator
      //else if (this.tcb.env.config.alwaysCheckSchemaInTemplateBodies) {
      //this.appendDeepSchemaChecks(node.children)
      //}
      this.checkAndAppendReferencesOfNode(node)
    }
    //else if (node is TmplAstDeferredBlock) {
    //  this.appendDeferredBlock(node)
    //}
    //else if (node is TmplAstIfBlock) {
    //  this.opQueue.add(TcbIfOp(this.tcb, this, node))
    //}
    //else if (node is TmplAstSwitchBlock) {
    //  this.opQueue.add(TcbSwitchOp(this.tcb, this, node))
    //}
    //else if (node is TmplAstForLoopBlock) {
    //  this.opQueue.add(TcbForOfOp(this.tcb, this, node))
    //  node.empty && this.tcb.env.config.checkControlFlowBodies && this.appendChildren(node.empty)
    //}
    else if (node is TmplAstBoundText) {
      this.opQueue.add(TcbExpressionOp(this.tcb, this, node.value))
    }
    else if (node is TmplAstContent) {
      for (child in node.children) {
        this.appendNode(child)
      }
    }
    else {
      TODO("support blocks")
    }
  }

  private fun checkAndAppendReferencesOfNode(node: `TmplAstElement|TmplAstTemplate`) {
    for (ref in node.references.values) {
      val target = this.tcb.boundTarget.getReferenceTarget(ref)

      if (target == null) {
        // The reference is invalid if it doesn't have a target, so report it as an error.
        this.tcb.oobRecorder.missingReferenceTarget(this.tcb.id, ref)

        // Any usages of the invalid reference will be resolved to a variable of type any.
        this.opQueue.add(TcbInvalidReferenceOp(this.tcb, this))
      }
      else {
        this.opQueue.add(TcbReferenceOp(this.tcb, this, ref, node, target))
      }
      this.referenceOpMap[ref] = this.opQueue.lastIndex
    }
  }

  private fun appendDirectivesAndInputsOfNode(node: `TmplAstElement|TmplAstTemplate`) {
    // Collect all the inputs on the element.
    val claimedInputs = mutableSetOf<String>()
    val directives = this.tcb.boundTarget.getDirectivesOfNode(node)
    if (directives.isEmpty()) {
      // If there are no directives, then all inputs are unclaimed inputs, so queue an operation
      // to add them if needed.
      if (node is TmplAstElement) {
        this.opQueue.add(TcbUnclaimedInputsOp(this.tcb, this, node, claimedInputs))

        // WebStorm - this is done through HTML validator
        //this.opQueue.add(
        //  TcbDomSchemaCheckerOp(this.tcb, node, /* checkElement */ true, claimedInputs))
      }
      return
    }
    else {
      if (node is TmplAstElement) {
        val isDeferred = this.tcb.boundTarget.isDeferred(node)
        if (!isDeferred && directives.any { dir -> this.tcb.env.isExplicitlyDeferred(dir) }) {
          // This node has directives/components that were defer-loaded (included into
          // `@Component.deferredImports`), but the node itself was used outside of a
          // `@defer` block, which is the error.
          this.tcb.oobRecorder.deferredComponentUsedEagerly(this.tcb.id, node)
        }
      }
    }

    val dirMap = mutableMapOf<TmplDirectiveMetadata, Int>()
    for (dir in directives) {
      var directiveOp: TcbOp

      if (!dir.isGeneric) {
        // The most common case is that when a directive is not generic, we use the normal
        // `TcbNonDirectiveTypeOp`.
        directiveOp = TcbNonGenericDirectiveTypeOp(this.tcb, this, node, dir)
      }
      else if (
        !requiresInlineTypeCtor(dir.typeScriptClass, this.tcb.env) ||
        this.tcb.env.config.useInlineTypeConstructors) {
        // For generic directives, we use a type constructor to infer types. If a directive requires
        // an inline type constructor, then inlining must be available to use the
        // `TcbDirectiveCtorOp`. If not we, we fallback to using `any` – see below.
        directiveOp = TcbDirectiveCtorOp(this.tcb, this, node, dir)
      }
      else {
        // If inlining is not available, then we give up on inferring the generic params, and use
        // `any` type for the directive's generic parameters.
        directiveOp = TcbGenericDirectiveTypeWithAnyParamsOp(this.tcb, this, node, dir)
      }

      this.opQueue.add(directiveOp)
      dirMap[dir] = this.opQueue.lastIndex

      this.opQueue.add(TcbDirectiveInputsOp(this.tcb, this, node, dir))
    }
    this.directiveOpMap[node] = dirMap

    // After expanding the directives, we might need to queue an operation to check any unclaimed
    // inputs.
    if (node is TmplAstElement) {
      // Go through the directives and remove any inputs that it claims from `elementInputs`.
      for (dir in directives) {
        for (propertyName in dir.inputs.keys) {
          claimedInputs.add(propertyName)
        }
      }

      this.opQueue.add(TcbUnclaimedInputsOp(this.tcb, this, node, claimedInputs))
      // If there are no directives which match this element, then it's a "plain" DOM element (or a
      // web component), and should be checked against the DOM schema. If any directives match,
      // we must assume that the element could be custom (either a component, or a directive like
      // <router-outlet>) and shouldn't validate the element name itself.

      // WebStorm - this is done through HTML validator

      //val checkElement = directives.isEmpty()
      //this.opQueue.add(TcbDomSchemaCheckerOp(this.tcb, node, checkElement, claimedInputs))
    }
  }

  private fun appendOutputsOfNode(node: `TmplAstElement|TmplAstTemplate`) {
    // Collect all the outputs on the element.
    val claimedOutputs = mutableSetOf<String>()
    val directives = this.tcb.boundTarget.getDirectivesOfNode(node)
    if (directives.isEmpty()) {
      // If there are no directives, then all outputs are unclaimed outputs, so queue an operation
      // to add them if needed.
      if (node is TmplAstElement) {
        this.opQueue.add(TcbUnclaimedOutputsOp(this.tcb, this, node, claimedOutputs))
      }
      return
    }

    // Queue operations for all directives to check the relevant outputs for a directive.
    for (dir in directives) {
      this.opQueue.add(TcbDirectiveOutputsOp(this.tcb, this, node, dir))
    }

    // After expanding the directives, we might need to queue an operation to check any unclaimed
    // outputs.
    if (node is TmplAstElement) {
      // Go through the directives and register any outputs that it claims in `claimedOutputs`.
      for (dir in directives) {
        for (outputProperty in dir.outputs.keys) {
          claimedOutputs.add(outputProperty)
        }
      }

      this.opQueue.add(TcbUnclaimedOutputsOp(this.tcb, this, node, claimedOutputs))
    }
  }

  private fun appendContentProjectionCheckOp(root: TmplAstElement) {
    val meta =
      this.tcb.boundTarget.getDirectivesOfNode(root).firstNotNullOfOrNull { it.directive as? Angular2Component }

    if (meta?.ngContentSelectors != null && meta.ngContentSelectors.isNotEmpty()) {
      val selectors = meta.ngContentSelectors

      // We don't need to generate anything for components that don't have projection
      // slots, or they only have one catch-all slot (represented by `*`).
      if (selectors.size > 1 || (selectors.size == 1 && selectors[0].text.trim() != "*")) {
        TODO("Add support for blocks")
        //this.opQueue.add(
        //  TcbControlFlowContentProjectionOp(this.tcb, root, selectors, meta.name))
      }
    }
  }

  //private fun appendDeferredBlock(block: TmplAstDeferredBlock) {
  //  this.appendDeferredTriggers(block, block.triggers)
  //  this.appendDeferredTriggers(block, block.prefetchTriggers)
  //  this.appendChildren(block)
  //
  //  if (block.placeholder != null) {
  //    this.appendChildren(block.placeholder)
  //  }
  //
  //  if (block.loading != null) {
  //    this.appendChildren(block.loading)
  //  }
  //
  //  if (block.error != null) {
  //    this.appendChildren(block.error)
  //  }
  //}
  //
  //private fun appendDeferredTriggers(
  //  block: TmplAstDeferredBlock, triggers: TmplAstDeferredBlockTriggers) {
  //  if (triggers.when != null) {
  //    this.opQueue.add(TcbExpressionOp(this.tcb, this, triggers.when.value))
  //  }
  //
  //  if (triggers.hover != null) {
  //    this.appendReferenceBasedDeferredTrigger(block, triggers.hover)
  //  }
  //
  //  if (triggers.interaction != null) {
  //    this.appendReferenceBasedDeferredTrigger(block, triggers.interaction)
  //  }
  //
  //  if (triggers.viewport != null) {
  //    this.appendReferenceBasedDeferredTrigger(block, triggers.viewport)
  //  }
  //}
  //
  //private fun appendReferenceBasedDeferredTrigger(
  //  block: TmplAstDeferredBlock,
  //  trigger: TmplAstHoverDeferredTrigger|TmplAstInteractionDeferredTrigger|
  //TmplAstViewportDeferredTrigger)
  //{
  //  if (this.tcb.boundTarget.getDeferredTriggerTarget(block, trigger) == null) {
  //    this.tcb.oobRecorder.inaccessibleDeferredTriggerElement(this.tcb.id, trigger)
  //  }
  //}
}

private data class TcbBoundAttribute(
  val attribute: `TmplAstBoundAttribute|TmplAstTextAttribute`,
  val inputs: List<TcbBoundAttributeInput>,
)

private data class TcbBoundAttributeInput(
  val fieldName: String?,
  val required: Boolean,
  val transformType: JSType?,
  val isSignal: Boolean,
  val isTwoWayBinding: Boolean,
  val isCoerced: Boolean,
  val isRestricted: Boolean,
)

/**
 * Process an `AST` expression and convert it into a `Expression`, generating references to the
 * correct identifiers in the current scope.
 */
private fun tcbExpression(ast: JSExpression?, tcb: Context, scope: Scope): Expression = Expression {
  TcbExpressionTranslator(tcb, scope, this).translate(ast)
}

private open class TcbExpressionTranslator(private val tcb: Context, protected val scope: Scope, private val result: Expression.ExpressionBuilder) : Angular2RecursiveVisitor() {

  open fun translate(ast: JSElement?) {
    if (ast != null) {
      ast.accept(this)
    }
    else {
      result.append("undefined")
    }
  }

  override fun visitElement(element: PsiElement) {
    if (element is LeafPsiElement) {
      result.append(element.text, if (element !is PsiWhiteSpace) element.textRange else null)
    }
    else {
      result.withSourceSpan(element.textRange) {
        super.visitElement(element)
      }
    }
  }

  override fun visitJSCallExpression(node: JSCallExpression) {
    val methodExpression = node.methodExpression.let {
      var result = it
      while (result is JSParenthesizedExpression) {
        result = result.innerExpression
      }
      result
    }

    // Resolve the special `$any(expr)` syntax to insert a cast in the argument to type `any`.
    // `$any(expr)` -> `expr as any`
    if (methodExpression
          ?.asSafely<JSReferenceExpression>()
          ?.takeIf { it.qualifier == null && it.referenceName == "\$any" } != null
        && node.argumentSize == 1) {
      result.append("(")
      translate(node.arguments[0])
      result.append(" as any)")
    }
    else if (methodExpression
        ?.asSafely<JSReferenceExpression>()
        ?.isElvis == true
      ) {
      emitSafeAccess(node, methodExpression) {
        append("()")
      }
    } else {
      super.visitJSCallExpression(node)
    }
  }

  override fun visitJSReferenceExpression(node: JSReferenceExpression) {
    // TODO: this is actually a bug, because `ImplicitReceiver`: `ThisReceiver`. Consider a
    // case when the explicit `this` read is inside a template with a context that also provides the
    // variable name being read:
    // ```
    // <ng-template let-a>{{this.a}}</ng-template>
    // ```
    // Clearly, `this.a` should refer to the class property `a`. However, because of this code,
    // `this.a` will refer to `let-a` on the template context.
    //
    // Note that the generated code is actually consistent with this bug. To fix it, we have to:
    // - Check `!(ast.receiver instanceof ThisReceiver)` in this condition
    // - Update `ingest.ts` in the Template Pipeline (see the corresponding comment)
    // - Turn off legacy TemplateDefinitionBuilder
    // - Fix g3, and release in a major version
    if (node.qualifier == null || node.qualifier is JSThisExpression) {
      // Try to resolve a bound target for this expression. If no such target is available, then
      // the expression is referencing the top-level component context. In that case, `null` is
      // returned here to let it fall through resolution so it will be caught when the
      // `ImplicitReceiver` is resolved in the branch below.
      val templateTarget = resolveTarget(node)
      if (templateTarget == null) {
        // AST instances representing variables and references look very similar to property reads
        // or method calls from the component context: both have the shape
        // PropertyRead(ImplicitReceiver, 'propName') or Call(ImplicitReceiver, 'methodName').
        //
        // `translate` will first try to `resolve` the outer PropertyRead/Call. If this works,
        // it's because the `BoundTarget` found an expression target for the whole expression, and
        // therefore `translate` will never attempt to `resolve` the ImplicitReceiver of that
        // PropertyRead/Call.
        //
        // Therefore if `resolve` is called on an `ImplicitReceiver`, it's because no outer
        // PropertyRead/Call resolved to a variable or reference, and therefore this is a
        // property read or method call on the component context itself.
        if (node.qualifier == null) {
          result.append("this.")
        }
        result.append(node.text, node.textRange)
      }
      else {
        result.append(templateTarget)
      }
    }
    else {
      // The form of safe property reads depends on whether strictness is in use.
      if (node.isElvis) {
          emitSafeAccess(node, node.qualifier!!) {
            append(".")
            node.referenceNameElement
              ?.accept(this@TcbExpressionTranslator)
            ?: append("_error_")
          }
      }
      else {
        super.visitJSReferenceExpression(node)
      }
    }
  }

  private fun emitSafeAccess(node: PsiElement, qualifier: JSExpression, expression: Expression.ExpressionBuilder.() -> Unit) {
    result.withSourceSpan(node.textRange) {
      if (tcb.env.config.strictSafeNavigationTypes) {
        // Basically, the return here is either the type of the complete expression with a null-safe
        // property read, or `undefined`. So a ternary is used to create an "or" type:
        // "a?.b" becomes (null as any ? a!.b : undefined)
        // The type of this expression is (typeof a!.b) | undefined, which is exactly as desired.
        append("(null as any ? (")
        qualifier.accept(this@TcbExpressionTranslator)
        append(")!")
        expression()
        append(" : undefined)")
      }
      else if (VeSafeLhsInferenceBugDetector().veWillInferAnyFor(node)) {
        // Emulate a View Engine bug where 'any' is inferred for the left-hand side of the safe
        // navigation operation. With this bug, the type of the left-hand side is regarded as any.
        // Therefore, the left-hand side only needs repeating in the output (to validate it), and then
        // 'any' is used for the rest of the expression. This is done using a comma operator:
        // "a?.b" becomes (a as any).b, which will of course have type 'any'.
        append("((")
        qualifier.accept(this@TcbExpressionTranslator)
        append(") as any)")
        expression()
      }
      else {
        // The View Engine bug isn't active, so check the entire type of the expression, but the final
        // result is still inferred as `any`.
        // "a?.b" becomes (a!.b as any)
        append("((")
        qualifier.accept(this@TcbExpressionTranslator)
        append(")!")
        expression()
        append(" as any)")
      }
    }
  }

  override fun visitAngular2PipeExpression(pipe: Angular2PipeExpression) {
    val arguments = pipe.arguments
    val pipeName = pipe.methodExpression?.asSafely<Angular2PipeReferenceExpression>()?.referenceName
    val pipeInfo = tcb.getPipeByName(pipeName)
    val pipeCall: Expression
    if (pipeInfo == null) {
      // No pipe by that name exists in scope. Record this as an error.
      tcb.oobRecorder.missingPipe(tcb.id, pipe)

      // Use an 'any' value to at least allow the rest in the expression to be checked.
      pipeCall = Expression("(null as any)")
    }
    // TODO block support
    //else if (
    //  pipeInfo.isExplicitlyDeferred &&
    //  tcb.boundTarget.getEagerlyUsedPipes().includes(ast.name)) {
    //  // This pipe was defer-loaded (included into `@Component.deferredImports`),
    //  // but was used outside of a `@defer` block, which is the error.
    //  tcb.oobRecorder.deferredPipeUsedEagerly(this.tcb.id, ast)
    //
    //  // Use an 'any' value to at least allow the rest of the expression to be checked.
    //  pipeCall = Expression().append("(null as any)")
    //}
    else {
      // Use a variable declared as the pipe's type.
      pipeCall = tcb.env.pipeInst(pipeInfo)
    }
    result.withSourceSpan(pipe.textRange) {
      if (!tcb.env.config.checkTypeOfPipes) {
        result.append("(")
      }
      result.append(pipeCall)
      result.append(".")
      result.append("transform", pipe.methodExpression?.textRange)
      if (!tcb.env.config.checkTypeOfPipes) {
        result.append(" as any)")
      }
      result.append("(")
      arguments.forEachIndexed { index, it ->
        if (index > 0) {
          result.append(", ")
        }
        it.accept(this@TcbExpressionTranslator)
      }
      result.append(")")
    }
  }

  override fun visitJSArrayLiteralExpression(node: JSArrayLiteralExpression) {
    if (!tcb.env.config.strictLiteralTypes) {
      result.append("(")
    }
    super.visitJSArrayLiteralExpression(node)
    if (!tcb.env.config.strictLiteralTypes) {
      result.append(" as any)")
    }
  }

  override fun visitJSObjectLiteralExpression(node: JSObjectLiteralExpression) {
    if (!tcb.env.config.strictLiteralTypes) {
      result.append("(")
    }
    super.visitJSObjectLiteralExpression(node)
    if (!tcb.env.config.strictLiteralTypes) {
      result.append(" as any)")
    }
  }


  /**
   * Attempts to resolve a bound target for a given expression, and translates it into the
   * appropriate `Expression` that represents the bound target. If no target is available,
   * `null` is returned.
   */
  protected open fun resolveTarget(ast: JSReferenceExpression): Expression? {
    val binding = this.tcb.boundTarget.getExpressionTarget(ast)
    if (binding == null) {
      return null
    }

    val identifier = this.scope.resolve(binding)
    return Expression {
      append(identifier, ast.textRange)
    }
  }
}

/**
 * Call the type constructor of a directive instance on a given template node, inferring a type for
 * the directive instance from any bound inputs.
 */
private fun tcbCallTypeCtor(dir: TmplDirectiveMetadata, tcb: Context, inputs: Collection<TcbDirectiveInput>): Expression {
  val typeCtor = tcb.env.typeCtorFor(dir)

  // Construct an array in `ts.PropertyAssignment`s for each in the directive"s inputs.
  val members = inputs.map { input ->
    if (input is TcbDirectiveBoundInput) {
      // For bound inputs, the property is assigned the binding expression.
      var expr = widenBinding(input.expression, tcb)

      if (input.isTwoWayBinding && tcb.env.config.allowSignalsInTwoWayBindings) {
        expr = unwrapWritableSignal(expr, tcb)
      }

      Expression {
        append("\"${input.field}\": ")
        append(expr)
      }
    }
    else {
      // A type constructor is required to be called with all input properties, so any unset
      // inputs are simply assigned a value of type `any` to ignore them.
      Expression("\"${input.field}\": null as any")
    }
  }

  // Call the `ngTypeCtor` method on the directive class, with an object literal argument created
  // from the matched inputs.
  return Expression {
    append(typeCtor)
    append("({")
    members.forEachIndexed { index, expr ->
      if (index > 0) {
        append(", ")
      }
      append(expr)
    }
    append("})")
  }
}

private fun getBoundAttributes(directive: TmplDirectiveMetadata, node: `TmplAstElement|TmplAstTemplate`): List<TcbBoundAttribute> {
  val boundInputs = mutableListOf<TcbBoundAttribute>()

  val directiveInputs = directive.inputs

  fun processAttribute(attr: `TmplAstBoundAttribute|TmplAstTextAttribute`) {
    // Skip non-property bindings.
    if (attr is TmplAstBoundAttribute && attr.type != BindingType.Property &&
        attr.type != BindingType.TwoWay) {
      return
    }

    // Skip the attribute if the directive does not have an input for it.
    val inputs = directiveInputs[attr.name]?.let { listOf(it) }

    if (inputs != null) {
      boundInputs.add(TcbBoundAttribute(
        attribute = attr,
        inputs = inputs.map { input ->
          TcbBoundAttributeInput(
            fieldName = input.fieldName,
            required = input.required,
            transformType = (input as? Angular2SourceDirectiveProperty)?.transformParameterType,
            isSignal = (input as? Angular2SourceDirectiveProperty)?.typeFromSignal != null,
            isTwoWayBinding = attr is TmplAstBoundAttribute && attr.type == BindingType.TwoWay,
            isCoerced = (input as? Angular2ClassBasedDirectiveProperty)?.isCoerced == true,
            isRestricted = (input as? Angular2SourceDirectiveProperty)?.sources?.any {
              it is JSAttributeListOwner && it is JSRecordType.PropertySignature &&
              (it.attributeList?.hasModifier(ModifierType.READONLY) == true || it.accessType != JSAttributeList.AccessType.PUBLIC)
            } == true
          )
        }
      ))
    }
  }

  node.inputs.values.forEach(::processAttribute)
  node.attributes.values.forEach(::processAttribute)
  if (node is TmplAstTemplate) {
    node.templateAttrs.forEach(::processAttribute)
  }

  return boundInputs
}

/**
 * Translates the given attribute binding to a `Expression`.
 */
private fun translateInput(attr: `TmplAstBoundAttribute|TmplAstTextAttribute`, tcb: Context, scope: Scope): Expression {
  if (attr is TmplAstBoundAttribute) {
    // Produce an expression representing the value of the binding.
    return tcbExpression(attr.value, tcb, scope)
  }
  else {
    // For regular attributes with a static string value, use the represented string literal.
    return Expression("\"${(attr as TmplAstTextAttribute).value}\"")
  }
}

/**
 * Potentially widens the type in `expr` according to the type-checking configuration.
 */
private fun widenBinding(expr: Expression, tcb: Context): Expression {
  if (!tcb.env.config.checkTypeOfInputBindings) {
    // If checking the type of bindings is disabled, cast the resulting expression to 'any'
    // before the assignment.
    return tsCastToAny(expr)
  }
  else if (!tcb.env.config.strictNullInputBindings) {
    if (ts.isObjectLiteralExpression(expr) || ts.isArrayLiteralExpression(expr)) {
      // Object literals and array literals should not be wrapped in non-null assertions as that
      // would cause literals to be prematurely widened, resulting in type errors when assigning
      // into a literal type.
      return expr
    }
    else {
      // If strict null checks are disabled, erase `null` and `undefined` from the type by
      // wrapping the expression in a non-null assertion.
      return Expression { append(expr).append("!") }
    }
  }
  else {
    // No widening is requested, use the expression as is.
    return expr
  }
}

/**
 * Wraps an expression in an `unwrapSignal` call which extracts the signal"s value.
 */
private fun unwrapWritableSignal(expression: Expression, tcb: Context): Expression {
  val unwrapRef = tcb.env.referenceExternalSymbol(
    R3Identifiers.unwrapWritableSignal.moduleName, R3Identifiers.unwrapWritableSignal.name)
  return Expression { append(unwrapRef).append("(").append(expression).append(")") }
}

private sealed interface TcbDirectiveInput {
  val field: String
}

/**
 * An input binding that corresponds with a field of a directive.
 */
private data class TcbDirectiveBoundInput(
  /**
   * The name of a field on the directive that is set.
   */
  override val field: String,

  /**
   * The `Expression` corresponding with the input binding expression.
   */
  val expression: Expression,

  /**
   * The source span of the full attribute binding.
   */
  val sourceSpan: TextRange,

  /**
   * Whether the binding is part of a two-way binding.
   */
  val isTwoWayBinding: Boolean,
) : TcbDirectiveInput

/**
 * Indicates that a certain field of a directive does not have a corresponding input binding.
 */
private data class TcbDirectiveUnsetInput(
  /**
   * The name of a field on the directive for which no input binding is present.
   */
  override val field: String,
) : TcbDirectiveInput

private const val EVENT_PARAMETER = Angular2StandardSymbolsScopesProvider.`$EVENT`

private enum class EventParamType {
  /* Generates code to infer the type of `$event` based on how the listener is registered. */
  Infer,

  /* Declares the type of the `$event` parameter as `any`. */
  Any,
}

/**
 * Creates an arrow function to be used as handler function for event bindings. The handler
 * function has a single parameter `$event` and the bound event's handler `AST` represented as a
 * TypeScript expression as its body.
 *
 * When `eventType` is set to `Infer`, the `$event` parameter will not have an explicit type. This
 * allows for the created handler function to have its `$event` parameter's type inferred based on
 * how it's used, to enable strict type checking of event bindings. When set to `Any`, the `$event`
 * parameter will have an explicit `any` type, effectively disabling strict type checking of event
 * bindings. Alternatively, an explicit type can be passed for the `$event` parameter.
 */
private fun tcbCreateEventHandler(event: TmplAstBoundEvent, tcb: Context, scope: Scope,
                                  eventType: `EventParamType|JSType`): Expression {
  val handlers = event.handler.map { handler ->
    tcbEventHandlerExpression(handler, tcb, scope).let {
      if (event.type == ParsedEventType.TwoWay) {
        Expression {
          append(it).append(" = $EVENT_PARAMETER")
        }
      }
      else {
        it
      }
    }
  }

  // Obtain all guards that have been applied to the scope and its parents, as they have to be
  // repeated within the handler function for their narrowing to be in effect within the handler.
  val guards = scope.guards()

  return Expression {
    append("(").append(EVENT_PARAMETER)
    if (eventType == EventParamType.Infer) {
      // do nothing
    }
    else if (eventType == EventParamType.Any) {
      append(": any")
    }
    else {
      append(": ")
      if (eventType is Expression)
        append(eventType)
      else
        append(tcb.env.referenceType(eventType as JSType))
    }
    append("): any => ")

    codeBlock {
      if (guards != null) {
        appendStatement {
          // Wrap the body in an `if` statement containing all guards that have to be applied.
          append("if (")
          append(guards)
          append(")")
          codeBlock {
            handlers.forEach {
              appendStatement {
                append(it).append(";")
              }
            }
          }
        }
      }
      else {
        handlers.forEach {
          appendStatement {
            append(it).append(";")
          }
        }
      }
    }
  }
}

/**
 * Similar to `tcbExpression`, this function converts the provided `AST` expression into a
 * `Expression`, with special handling of the `$event` variable that can be used within event
 * bindings.
 */
private fun tcbEventHandlerExpression(ast: JSElement?, tcb: Context, scope: Scope): Expression = Expression {
  TcbEventHandlerTranslator(tcb, scope, this).translate(ast)
}

private fun isSplitTwoWayBinding(inputName: String, output: TmplAstBoundEvent, inputs: Map<String, TmplAstBoundAttribute>, tcb: Context): Boolean {
  val input = inputs[inputName]
  if (input == null || input.sourceSpan != output.sourceSpan) {
    return false
  }
  // Input consumer should be a directive because it"s claimed
  val inputConsumer = tcb.boundTarget.getConsumerOfBinding(input) as? TmplDirectiveMetadata
                      ?: return false
  val outputConsumer = tcb.boundTarget.getConsumerOfBinding(output)
  if (outputConsumer == null || outputConsumer is TmplAstTemplate) {
    return false
  }
  if (outputConsumer is TmplAstElement) {
    tcb.oobRecorder.splitTwoWayBinding(
      tcb.id, input, output, inputConsumer, outputConsumer)
    return true
  }
  else if (outputConsumer != inputConsumer) {
    tcb.oobRecorder.splitTwoWayBinding(
      tcb.id, input, output, inputConsumer, outputConsumer)
    return true
  }
  return false
}

private class TcbEventHandlerTranslator(tcb: Context, scope: Scope, result: Expression.ExpressionBuilder)
  : TcbExpressionTranslator(tcb, scope, result) {
  override fun resolveTarget(ast: JSReferenceExpression): Expression? {
    // Recognize a property read on the implicit receiver corresponding with the event parameter
    // that is available in event bindings. Since this variable is a parameter of the handler
    // function that the converted expression becomes a child of, just create a reference to the
    // parameter by its name.
    if (ast.qualifier == null && ast.referenceName == EVENT_PARAMETER) {
      return Expression(EVENT_PARAMETER, ast.textRange)
    }
    return super.resolveTarget(ast)
  }
}

//class TcbForLoopTrackTranslator(tcb: Context, scope: Scope, private val block: TmplAstForLoopBlock)
//  : TcbExpressionTranslator(tcb, scope) {
//  private val allowedVariables: Set<TmplAstVariable>
//
//  init {
//    // Tracking expressions are only allowed to read the `$index`,
//    // the item and properties off the component instance.
//    this.allowedVariables = Set([block.item])
//    for (variable in block.contextVariables) {
//      if (variable.value == "$index") {
//        this.allowedVariables.add(variable)
//      }
//    }
//  }
//
//  override fun resolve(ast: AST): Expression? {
//    if (ast is PropertyRead && ast.receiver is ImplicitReceiver) {
//      val target = this.tcb.boundTarget.getExpressionTarget(ast)
//
//      if (target != null && !this.allowedVariables.contains(target)) {
//        this.tcb.oobRecorder.illegalForLoopTrackAccess(this.tcb.id, this.block, ast)
//      }
//    }
//
//    return super.resolve(ast)
//  }
//}

private fun tsCastToAny(expr: Expression): Expression {
  // Wrap `expr` in parentheses if needed (see `SAFE_TO_CAST_WITHOUT_PARENS` above).
  //if (!SAFE_TO_CAST_WITHOUT_PARENS.has(expr.kind)) {
  //  expr = ts.factory.createParenthesizedExpression(expr)
  //}

  // The outer expression is always wrapped in parentheses.
  return Expression {
    append("((")
    append(expr)
    append(") as any)")
  }
}

/**
 * Create a `ts.VariableStatement` which declares a variable without explicit initialization.
 *
 * The initializer `null!` is used to bypass strict variable initialization checks.
 *
 * Unlike with `tsCreateVariable`, the type of the variable is explicitly specified.
 */

internal fun tsDeclareVariable(id: Identifier, type: Expression): Statement {
  // When we create a variable like `var _t1: boolean = null!`, TypeScript actually infers `_t1`
  // to be `never`, instead of a `boolean`. To work around it, we cast the value
  // in the initializer, e.g. `var _t1 = null! as boolean;`.
  return Statement {
    append("var ")
    append(id, id.sourceSpan)
    append(" = null! as ")
    append(type)
    append(";")
  }
}

/**
 * Creates a `ts.TypeQueryNode` for a coerced input.
 *
 * For example: `typeof MatInput.ngAcceptInputType_value`, where MatInput is `typeName` and `value`
 * is the `coercedInputName`.
 *
 * @param typeName The `EntityName` of the Directive where the static coerced input is defined.
 * @param coercedInputName The field name of the coerced input.
 */
private fun tsCreateTypeQueryForCoercedInput(typeName: Expression, coercedInputName: String): Expression {
  return Expression {
    append("typeof ").append(typeName).append(".$NG_ACCEPT_INPUT_TYPE_PREFIX${coercedInputName}")
  }
}

/**
 * Create a `ts.VariableStatement` that initializes a variable with a given expression.
 *
 * Unlike with `tsDeclareVariable`, the type of the variable is inferred from the initializer
 * expression.
 */
private fun tsCreateVariable(id: Identifier, initializer: Expression): Statement {
  return Statement {
    append("var ")
    append(id, id.sourceSpan)
    append(" = ")
    append(initializer)
    append(";")
  }
}

enum class ExpressionIdentifier {
  Directive
}