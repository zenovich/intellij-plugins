// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.intellij.terraform.config.actions

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import org.intellij.terraform.config.util.TFExecutor
import org.intellij.terraform.config.util.executeSuspendable
import org.jetbrains.annotations.Nls

class TFInitAction : TFExternalToolsAction() {

  override suspend fun invoke(project: Project, module: Module?, title: @Nls String, virtualFile: VirtualFile) {
    withBackgroundProgress(project, title) {
      val directory = if (virtualFile.isDirectory) virtualFile else virtualFile.parent
      TFExecutor.`in`(project, module)
        .withPresentableName(title)
        .withParameters("init")
        .showOutputOnError()
        .withWorkDirectory(directory.canonicalPath)
        .executeSuspendable()
      VfsUtil.markDirtyAndRefresh(true, true, true, directory)
    }
  }

}