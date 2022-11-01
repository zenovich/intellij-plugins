// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.vuejs.web

import com.intellij.html.webSymbols.WebSymbolsHtmlQueryConfigurator
import com.intellij.lang.javascript.DialectDetector
import com.intellij.lang.javascript.library.JSLibraryUtil
import com.intellij.lang.javascript.settings.JSApplicationSettings
import com.intellij.model.Pointer
import com.intellij.psi.PsiElement
import com.intellij.refactoring.suggested.createSmartPointer
import com.intellij.webSymbols.PsiSourcedWebSymbol
import com.intellij.webSymbols.SymbolKind
import com.intellij.webSymbols.SymbolNamespace
import com.intellij.webSymbols.WebSymbol
import com.intellij.webSymbols.completion.WebSymbolCodeCompletionItem
import com.intellij.webSymbols.context.WebSymbolsContext
import com.intellij.webSymbols.query.WebSymbolsQueryResultsCustomizer
import com.intellij.webSymbols.query.WebSymbolsQueryResultsCustomizerFactory
import org.jetbrains.vuejs.codeInsight.detectVueScriptLanguage
import org.jetbrains.vuejs.codeInsight.tags.VueInsertHandler
import org.jetbrains.vuejs.model.VueModelVisitor
import org.jetbrains.vuejs.web.VueWebSymbolsQueryConfigurator.Companion.PROP_VUE_COMPOSITION_COMPONENT
import org.jetbrains.vuejs.web.VueWebSymbolsQueryConfigurator.Companion.PROP_VUE_PROXIMITY

class VueWebSymbolsQueryResultsCustomizer(private val context: PsiElement) : WebSymbolsQueryResultsCustomizer {

  private val scriptLanguage by lazy(LazyThreadSafetyMode.NONE) {
    detectVueScriptLanguage(context.containingFile)
  }

  override fun createPointer(): Pointer<out WebSymbolsQueryResultsCustomizer> {
    val contextPtr = context.createSmartPointer()
    return Pointer {
      contextPtr.dereference()?.let { VueWebSymbolsQueryResultsCustomizer(it) }
    }
  }

  override fun apply(matches: List<WebSymbol>,
                     strict: Boolean,
                     namespace: SymbolNamespace?,
                     kind: SymbolKind,
                     name: String?): List<WebSymbol> {
    if (namespace != WebSymbol.NAMESPACE_HTML) return matches

    if (kind == VueWebSymbolsQueryConfigurator.KIND_VUE_COMPONENTS) {
      if (!strict) return matches
    }
    else if (kind == WebSymbol.KIND_HTML_ELEMENTS) {
      val hasStandardHtmlSymbols = matches.any { it is WebSymbolsHtmlQueryConfigurator.StandardHtmlSymbol }
      if (!hasStandardHtmlSymbols) return matches
    }

    return matches.filter { symbol ->
      symbol.properties[PROP_VUE_PROXIMITY] != VueModelVisitor.Proximity.OUT_OF_SCOPE ||
      symbol.properties[PROP_VUE_COMPOSITION_COMPONENT] == true
    }
  }

  override fun apply(item: WebSymbolCodeCompletionItem,
                     namespace: SymbolNamespace?,
                     kind: SymbolKind): WebSymbolCodeCompletionItem {
    if (namespace == WebSymbol.NAMESPACE_HTML
        && kind == VueWebSymbolsQueryConfigurator.KIND_VUE_COMPONENTS) {
      val proximity = item.symbol?.properties?.get(PROP_VUE_PROXIMITY)
      val element = (item.symbol as? PsiSourcedWebSymbol)?.source
      if (proximity == VueModelVisitor.Proximity.OUT_OF_SCOPE && element != null) {
        val settings = JSApplicationSettings.getInstance()
        if ((scriptLanguage != null && "ts" == scriptLanguage)
            || (DialectDetector.isTypeScript(element)
                && !JSLibraryUtil.isProbableLibraryFile(element.containingFile.viewProvider.virtualFile))) {
          if (settings.hasTSImportCompletionEffective(element.project)) {
            return item.withInsertHandlerAdded(VueInsertHandler.INSTANCE, WebSymbol.Priority.LOWEST)
          }
        }
        else {
          if (settings.isUseJavaScriptAutoImport) {
            return item.withInsertHandlerAdded(VueInsertHandler.INSTANCE, WebSymbol.Priority.LOWEST)
          }
        }
      }
    }
    return item
  }

  override fun getModificationCount(): Long = 0

  override fun equals(other: Any?): Boolean =
    other is VueWebSymbolsQueryResultsCustomizer
    && other.context == context

  override fun hashCode(): Int =
    context.hashCode()

  class Provider : WebSymbolsQueryResultsCustomizerFactory {
    override fun create(location: PsiElement, context: WebSymbolsContext): WebSymbolsQueryResultsCustomizer? =
      if (context.framework == VueFramework.ID)
        VueWebSymbolsQueryResultsCustomizer(location)
      else null

  }

}