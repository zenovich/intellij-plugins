package org.intellij.prisma.ide.lsp

import com.intellij.application.options.CodeStyle
import com.intellij.lang.typescript.lsp.JSLspServerDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import org.intellij.prisma.PrismaBundle
import org.intellij.prisma.ide.formatter.settings.PrismaCodeStyleSettings
import org.intellij.prisma.lang.PrismaFileType

class PrismaLspServerDescriptor(project: Project)
  : JSLspServerDescriptor(project, PrismaLspServerActivationRule, PrismaBundle.message("prisma.framework.name")) {

  // references resolution is implemented without using the LSP server
  override val lspGoToDefinitionSupport = false

  // code completion is implemented without using the LSP server
  override val lspCompletionSupport = null

  override val lspFormattingSupport = object : LspFormattingSupport() {
    override fun shouldFormatThisFileExclusivelyByServer(file: VirtualFile, ideCanFormatThisFileItself: Boolean, serverExplicitlyWantsToFormatThisFile: Boolean): Boolean {
      return file.fileType == PrismaFileType && file.findPsiFile(project)?.let {
        CodeStyle.getCustomSettings(it, PrismaCodeStyleSettings::class.java).RUN_PRISMA_FMT_ON_REFORMAT
      } == true
    }
  }

  override val lspHoverSupport = false
}
