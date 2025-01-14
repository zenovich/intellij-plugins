// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.intellij.terraform.hcl.formatter

import com.intellij.application.options.CodeStyle
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import org.intellij.terraform.config.Constants.TF_FMT
import org.intellij.terraform.config.TerraformConstants.EXECUTION_NOTIFICATION_GROUP
import org.intellij.terraform.config.TerraformFileType
import org.intellij.terraform.config.actions.isTerraformExecutable
import org.intellij.terraform.config.util.TFExecutor
import org.intellij.terraform.hcl.HCLBundle

internal class TfAsyncFormattingService : AsyncDocumentFormattingService() {
  override fun getName(): String = TF_FMT

  override fun getNotificationGroupId(): String = EXECUTION_NOTIFICATION_GROUP.displayId

  override fun getFeatures(): Set<FormattingService.Feature> = emptySet()

  override fun canFormat(file: PsiFile): Boolean {
    val settings = CodeStyle.getCustomSettings(file, HclCodeStyleSettings::class.java)
    return (file.fileType is TerraformFileType || file.name.endsWith(TestsExtension)) && settings.RUN_TF_FMT_ON_REFORMAT
  }

  override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
    val context = request.context
    val project = context.project
    if (!isTerraformExecutable(project)) {
      return null
    }

    val virtualFile = context.virtualFile ?: return null
    val commandLine = createCommandLine(project)
    val processHandler = CapturingProcessHandler(commandLine)

    return object : FormattingTask {
      override fun run() {
        try {
          processHandler.processInput.write(request.documentText.toByteArray())
          processHandler.processInput.close()

          val output = processHandler.runProcess()
          if (output.exitCode == 0) {
            request.onTextReady(output.stdout)
          }
          else {
            request.onError(HCLBundle.message("terraform.formatter.error.title"), output.stderr)
          }
        }
        catch (e: Exception) {
          request.onError(HCLBundle.message("terraform.formatter.error.title"),
                          HCLBundle.message("terraform.formatter.error.message", virtualFile.name))
        }
      }

      override fun cancel(): Boolean {
        processHandler.destroyProcess()
        return true
      }

      override fun isRunUnderProgress(): Boolean = true
    }
  }

  private fun createCommandLine(project: Project): GeneralCommandLine =
    TFExecutor.`in`(project)
      .withPresentableName(TF_FMT)
      .withParameters("fmt", "-")
      .createCommandLine()
}

private const val TestsExtension = "tftest.hcl"