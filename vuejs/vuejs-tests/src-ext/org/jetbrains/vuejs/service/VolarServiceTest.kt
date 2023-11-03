// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.vuejs.service

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.lang.javascript.JSDaemonAnalyzerLightTestCase.checkHighlightingByText
import com.intellij.lang.javascript.typescript.service.TypeScriptServiceTestBase.assertHasServiceItems
import com.intellij.lang.typescript.compiler.TypeScriptCompilerSettings
import com.intellij.openapi.Disposable
import com.intellij.platform.lsp.tests.checkLspHighlighting
import com.intellij.util.text.SemVer
import junit.framework.TestCase
import org.jetbrains.vuejs.lang.VueInspectionsProvider
import org.jetbrains.vuejs.lang.VueTestModule
import org.jetbrains.vuejs.lang.configureVueDependencies
import org.jetbrains.vuejs.options.getVueSettings
import org.junit.Test

class VolarServiceTest : VolarServiceTestBase() {

  @Test
  fun testSimpleVue() {
    myFixture.enableInspections(VueInspectionsProvider())
    myFixture.configureVueDependencies(VueTestModule.VUE_3_0_0)

    myFixture.configureByText("tsconfig.json", tsconfig)
    myFixture.configureByText("Simple.vue", """
      <script setup lang="ts">
      let <error descr="Vue: Type 'number' is not assignable to type 'string'.">a</error>: string = 1;
      
      function acceptNumber(num: number): number { return num; }
      
      acceptNumber(<error descr="Vue: Argument of type 'boolean' is not assignable to parameter of type 'number'.">true</error>);
      </script>
      
      <template>
        <div v-text="acceptNumber(<error descr="Vue: Argument of type 'boolean' is not assignable to parameter of type 'number'.">true</error>)" />
        <!-- todo remove duplicate internal warning -->
        <div>{{acceptNumber(<error descr="Argument type true is not assignable to parameter type number"><error descr="Vue: Argument of type 'boolean' is not assignable to parameter of type 'number'.">true</error></error>)}}</div>
      </template>
    """)
    myFixture.checkLspHighlighting()
    assertCorrectService()
  }

  @Test
  fun testEnableSuggestions() {
    myFixture.configureVueDependencies(VueTestModule.VUE_3_0_0)
    myFixture.configureByText("tsconfig.json", tsconfig)
    myFixture.configureByText("Simple.vue", """
      <script setup lang="ts">
      export function hello(<weak_warning descr="Vue: 'p' is declared but its value is never read." textAttributesKey="NOT_USED_ELEMENT_ATTRIBUTES">p</weak_warning>: number, p2: number) {
          console.log(p2);
      }
      let <error descr="Vue: Type 'number' is not assignable to type 'string'.">a</error>: string = 1;
      </script>
      
      <template>
      </template>
    """)
    myFixture.checkLspHighlighting()
    assertCorrectService()
  }

  @Test
  fun testDisableSuggestions() {
    val settings = TypeScriptCompilerSettings.getSettings(project)
    settings.isShowSuggestions = false
    disposeOnTearDown(Disposable { settings.isShowSuggestions = true })
    myFixture.configureVueDependencies(VueTestModule.VUE_3_0_0)
    myFixture.addFileToProject("tsconfig.json", tsconfig)
    myFixture.configureByText("Simple.vue", """
      <script setup lang="ts">
      export function hello(p: number, p2: number) {
          console.log(p2);
      }
      let <error descr="Vue: Type 'number' is not assignable to type 'string'.">a</error>: string = 1;
      </script>
      
      <template>
      </template>
    """)
    myFixture.checkLspHighlighting()
    assertCorrectService()
  }

  @Test
  fun testSimpleRename() {
    myFixture.configureVueDependencies(VueTestModule.VUE_3_0_0)
    myFixture.addFileToProject("tsconfig.json", tsconfig)
    val fileToRename = myFixture.addFileToProject("Usage.vue", """
      <script setup lang="ts">
        console.log("test");
      </script>
      <template>text</template>
    """.trimIndent())

    myFixture.configureByText("Simple.vue", """
      <script setup lang="ts">
      import Usage from './Us<caret>age.vue';
      console.log(Usage);
      </script>
      <template>
      </template>
    """)

    myFixture.checkLspHighlighting()
    myFixture.renameElement(fileToRename, "Usage2.vue")

    //no errors
    myFixture.checkLspHighlighting()

    assertCorrectService()
  }

  @Test
  fun testOptionalPropertyInsideObjectLiteralInTSFileCompletion() { // WEB-61886
    myFixture.configureVueDependencies(VueTestModule.VUE_3_0_0)
    myFixture.addFileToProject("tsconfig.json", tsconfig)

    myFixture.addFileToProject("api.ts", """
      export interface Config {
        base?: string;
      }
      
      export function applyDefaults(config: Config): Config {
        return config;
      }      
    """.trimIndent())

    myFixture.configureByText("config.ts", """
      import {applyDefaults} from "./api";
      
      applyDefaults({
        <caret>
      })
    """.trimIndent())

    myFixture.checkLspHighlighting()
    assertCorrectService()

    val elements = myFixture.completeBasic()
    myFixture.type('\n')

    checkHighlightingByText(myFixture, """
      import {applyDefaults} from "./api";
      
      applyDefaults({
        base
      })
    """.trimIndent(), true)

    val presentationTexts = getPresentationTexts(elements)
    // duplicated question mark is definitely unwanted, but for now, this is what we get from Volar, so let's encode it in test
    TestCase.assertTrue("Lookup element presentation must match expected", presentationTexts.contains("base??"))
    assertHasServiceItems(elements, true)
  }

  @Test
  fun testOptionalPropertyInsideQualifiedReferenceInTSFileCompletion() { // WEB-63103
    myFixture.configureVueDependencies(VueTestModule.VUE_3_0_0)
    myFixture.addFileToProject("tsconfig.json", tsconfig)

    // Volar reports obscuring errors when there's no reference after dot, but we have to test caret placement directly after it
    myFixture.configureByText("main.ts", """
      const foo: Partial<{ bar: string; baz: number; }> = {};
      foo.<caret><error>b</error>;
    """.trimIndent())

    myFixture.checkLspHighlighting()
    assertCorrectService()

    val elements = myFixture.completeBasic()
    myFixture.type('\t')

    checkHighlightingByText(myFixture, """
      const foo: Partial<{ bar: string; baz: number; }> = {};
      foo.bar;
    """.trimIndent(), true)

    val presentationTexts = getPresentationTexts(elements)
    // duplicated question mark is definitely unwanted, but for now, this is what we get from Volar, so let's encode it in test
    TestCase.assertTrue("Lookup element presentation must match expected", presentationTexts.contains("bar??"))
    assertHasServiceItems(elements, true)
  }

  @Test
  fun testOptionalParameterPropertyInTSFileCompletion() { // WEB-63103
    myFixture.configureVueDependencies(VueTestModule.VUE_3_0_0)
    myFixture.addFileToProject("tsconfig.json", tsconfig)

    // Volar reports obscuring errors when there's no reference after dot, but we have to test caret placement directly after it
    myFixture.configureByText("main.ts", """
      function test(foo?: { bar?: string; }) {
        <error>foo</error>.<caret><error>b</error>;
      }
    """.trimIndent())

    myFixture.checkLspHighlighting()
    assertCorrectService()

    val elements = myFixture.completeBasic()
    myFixture.type('\t')

    checkHighlightingByText(myFixture, """
      function test(foo?: { bar?: string; }) {
        foo?.bar;
      }
    """.trimIndent(), true)

    val presentationTexts = getPresentationTexts(elements)
    // duplicated question mark is definitely unwanted, but for now, this is what we get from Volar, so let's encode it in test
    TestCase.assertTrue("Lookup element presentation must match expected", presentationTexts.contains("bar??"))
    assertHasServiceItems(elements, true)
  }

  @Test
  fun testSimpleCustomVersionVue() {
    myFixture.enableInspections(VueInspectionsProvider())
    val version = SemVer.parseFromText("1.8.10")
    myFixture.configureVueDependencies(VueTestModule.VUE_3_0_0, additionalDependencies = mapOf("@vue/language-server" to version.toString()))
    myFixture.configureByText("tsconfig.json", tsconfig)
    performNpmInstallForPackageJson("package.json")
    val state = getVueSettings(project).state
    val old = state.packageName
    disposeOnTearDown(Disposable { state.packageName = old })
    val path = myFixture.findFileInTempDir("node_modules/@vue/language-server").path
    TestCase.assertNotNull(path)
    state.packageName = path

    myFixture.configureByText("Simple.vue", """
      <script setup lang="ts">
      let <error descr="Vue: Type 'number' is not assignable to type 'string'.">a</error>: string = 1;
      
      function acceptNumber(num: number): number { return num; }
      
      acceptNumber(<error descr="Vue: Argument of type 'boolean' is not assignable to parameter of type 'number'.">true</error>);
      </script>
      
      <template>
        <div v-text="acceptNumber(<error descr="Vue: Argument of type 'boolean' is not assignable to parameter of type 'number'.">true</error>)" />
        <!-- todo remove duplicate internal warning -->
        <div>{{acceptNumber(<error descr="Argument type true is not assignable to parameter type number"><error descr="Vue: Argument of type 'boolean' is not assignable to parameter of type 'number'.">true</error></error>)}}</div>
      </template>
    """)
    myFixture.checkLspHighlighting()

    assertCorrectService(version)
  }

  private fun getPresentationTexts(elements: Array<LookupElement>): List<String?> {
    return elements.map { element ->
      val presentation = LookupElementPresentation()
      element.renderElement(presentation)
      presentation.itemText
    }
  }
}