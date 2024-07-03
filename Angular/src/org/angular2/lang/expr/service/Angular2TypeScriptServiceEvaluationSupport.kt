package org.angular2.lang.expr.service

import com.intellij.lang.javascript.psi.JSType
import com.intellij.lang.typescript.compiler.TypeScriptServiceEvaluationSupport
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import org.angular2.lang.html.tcb.Angular2TranspiledComponentFileBuilder.TranspiledComponentFile

interface Angular2TypeScriptServiceEvaluationSupport : TypeScriptServiceEvaluationSupport {

  fun getGeneratedElementType(transpiledFile: TranspiledComponentFile, templateFile: PsiFile, generatedRange: TextRange): JSType?

}