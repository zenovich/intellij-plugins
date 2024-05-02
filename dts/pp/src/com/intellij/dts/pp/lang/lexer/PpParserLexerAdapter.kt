package com.intellij.dts.pp.lang.lexer

import com.intellij.dts.pp.lang.PpTokenTypes
import com.intellij.dts.pp.lang.psi.PpElifStatement
import com.intellij.dts.pp.lang.psi.PpIfStatement
import com.intellij.dts.pp.lang.psi.PpStatementType.*
import com.intellij.dts.pp.lang.psi.PpToken
import com.intellij.dts.pp.lang.psi.identifier
import com.intellij.lexer.Lexer

private class IfState {
  private var inside: Boolean = false
  private var wasActive: Boolean = false

  /**
   * Returns whether this if branch is active.
   */
  fun beginIf(result: Boolean): Boolean {
    inside = true
    wasActive = result

    return result
  }

  /**
   * Returns whether this elif branch is active.
   */
  fun elseIf(result: Boolean): Boolean {
    // if there is no leading if statement, treat elsif as beginning
    if (!inside) return beginIf(result)

    // ensure only one branch can be active at once
    if (wasActive || !result) return false

    wasActive = true
    return true
  }

  fun endIf() {
    inside = false
    wasActive = false
  }
}

/**
 * Handles if statements depending on the defines. If the body of an if-statement is not
 * active all tokens inside the body are joined to one [PpTokenTypes.inactive] token.
 *
 * This lexer should only be used for parsing since keeping track of the current defines
 * requires the entire file to be processed at once. For highlighting use [PpHighlightingLexerAdapter].
 */
open class PpParserLexerAdapter(tokenTypes: PpTokenTypes, baseLexer: Lexer) : PpLexerAdapterBase(tokenTypes, baseLexer) {
  private val defines = mutableListOf("I_AM_DEFINED")
  private val ifState = IfState()

  override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
    assert(startOffset == 0)

    super.start(buffer, startOffset, endOffset, initialState)
  }

  override fun processMarker(baseLexer: Lexer) {
    val tokens = tokenizeStatement(baseLexer)

    // forward tokens of the statement
    addStatementsTokens(baseLexer.tokenStart, tokens)
    baseLexer.advance()

    // map sections to inactive depending on the statement
    val statement = parseStatement(tokens)
    when {
      statement is PpIfStatement && !ifState.beginIf(statement.evaluate(defines)) -> {
        processInactiveSection(baseLexer)
      }
      statement is PpElifStatement && !ifState.elseIf(statement.evaluate(defines)) -> {
        processInactiveSection(baseLexer)
      }
      statement.type == Endif -> {
        ifState.endIf()
      }
      statement.type == Define -> {
        statement.identifier?.let { defines.add(it.text.toString()) }
      }
      statement.type == Undef -> {
        statement.identifier?.let { defines.remove(it.text.toString()) }
      }
    }
  }

  /**
   * Processes an inactive code section. Therefore, the base lexer is advanced until
   * the next preprocessor statement was reached. The overall if-state is tracked by
   * [ifState].
   *
   * This function handles nested if statements and also restores the lexer state in
   * the end.
   */
  private fun processInactiveSection(baseLexer: Lexer) {
    val baseLexerState = baseLexer.state

    var tokens: List<PpToken>
    var nestedIfs = 0

    while (true) {
      // advance until next preprocessor statement
      while (baseLexer.tokenType != null && baseLexer.tokenType != tokenTypes.statementMarker) {
        baseLexer.advance()
      }

      // reached the end of the file before the inactive section was closed
      if (baseLexer.tokenType == null) {
        addToken(baseLexer.bufferEnd, tokenTypes.inactive)
        return
      }

      tokens = tokenizeStatement(baseLexer)
      val statement = parseStatement(tokens)

      // find the end of the inactive code section, but mind nested ifs and elifs
      when {
        statement is PpIfStatement -> nestedIfs++
        nestedIfs == 0 && statement.type == Endif -> break
        nestedIfs >= 1 && statement.type == Endif -> nestedIfs--
        nestedIfs == 0 && statement is PpElifStatement -> {
          addToken(baseLexer.tokenStart, tokenTypes.inactive)
          baseLexer.restoreState(baseLexerState)
          return
        }
      }

      baseLexer.advance()
    }

    // emit one token for the inactive code section
    addToken(baseLexer.tokenStart, tokenTypes.inactive)

    // forward tokens of last statement, it is no longer part of the inactive code section
    addStatementsTokens(baseLexer.tokenStart, tokens)
    baseLexer.restoreState(baseLexerState)

    baseLexer.advance()
  }
}
