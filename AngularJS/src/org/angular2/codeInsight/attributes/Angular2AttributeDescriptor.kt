// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.angular2.codeInsight.attributes

import com.intellij.javascript.web.codeInsight.html.attributes.WebSymbolAttributeDescriptor
import com.intellij.javascript.web.codeInsight.html.attributes.WebSymbolHtmlAttributeInfo
import com.intellij.lang.javascript.psi.JSType
import com.intellij.model.Pointer
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlTag
import com.intellij.webSymbols.SymbolKind
import com.intellij.webSymbols.WebSymbol
import com.intellij.webSymbols.WebSymbolCodeCompletionItem
import com.intellij.webSymbols.WebSymbolsContainer
import org.angular2.Angular2Framework
import org.angular2.entities.Angular2Directive
import org.angular2.lang.html.parser.Angular2AttributeNameParser
import org.angular2.web.Angular2DescriptorSymbolsProvider
import javax.swing.Icon

class Angular2AttributeDescriptor(info: WebSymbolHtmlAttributeInfo, tag: XmlTag?)
  : WebSymbolAttributeDescriptor(info, tag) {

  val sourceDirectives: List<Angular2Directive> get() = bindingInfoProvider.directives

  @get:JvmName("hasErrorSymbols")
  val hasErrorSymbols: Boolean
    get() = bindingInfoProvider.errorSymbols.isNotEmpty()

  @get:JvmName("hasNonDirectiveSymbols")
  val hasNonDirectiveSymbols: Boolean
    get() = bindingInfoProvider.nonDirectiveSymbols.isNotEmpty()

  val info: Angular2AttributeNameParser.AttributeInfo = Angular2AttributeNameParser.parse(name, tag)

  private val bindingInfoProvider by lazy(LazyThreadSafetyMode.NONE) { Angular2DescriptorSymbolsProvider(this.symbol) }

  companion object {
    @JvmStatic
    @Deprecated(message = "Deprecated, returns fake descriptor. Use web-types or Web Symbols instead")
    fun create(tag: XmlTag,
               attributeName: String,
               @Suppress("UNUSED_PARAMETER")
               element: PsiElement): Angular2AttributeDescriptor {
      return Angular2AttributeDescriptor(object: WebSymbolHtmlAttributeInfo{
        override val name: String
          get() = attributeName
        override val symbol: WebSymbol
          get() = object: WebSymbol {
            override val origin: WebSymbolsContainer.Origin
              get() = WebSymbolsContainer.OriginData(Angular2Framework.ID)
            override val namespace: WebSymbolsContainer.Namespace
              get() = WebSymbolsContainer.Namespace.HTML
            override val kind: SymbolKind
              get() = WebSymbol.KIND_HTML_ATTRIBUTES
            override fun createPointer(): Pointer<out WebSymbol> =
              Pointer.hardPointer(this)
          }
        override val acceptsNoValue: Boolean
          get() = false
        override val acceptsValue: Boolean
          get() = true
        override val enumValues: List<WebSymbolCodeCompletionItem>?
          get() = null
        override val strictEnumValues: Boolean
          get() = false
        override val jsType: JSType?
          get() = null
        override val icon: Icon?
          get() = null
        override val required: Boolean
          get() = false
        override val defaultValue: String?
          get() = null
        override val priority: WebSymbol.Priority
          get() = WebSymbol.Priority.NORMAL
      },tag)
    }
  }

}