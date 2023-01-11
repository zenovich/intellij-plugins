// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.astro.lang.typescript

import com.intellij.psi.tree.IElementType
import java.util.concurrent.ConcurrentHashMap

/* Special wrapper token used in highlighting lexer to pass information that expression token comes from frontmatter script */
class AstroFrontmatterHighlighterToken private constructor(val original: IElementType)
  : IElementType("FRNT:" + original.debugName, original.language) {

  companion object {
    private val map = ConcurrentHashMap<IElementType, AstroFrontmatterHighlighterToken>()
    operator fun get(original: IElementType): AstroFrontmatterHighlighterToken =
      map.computeIfAbsent(original) { AstroFrontmatterHighlighterToken(it) }
  }

}