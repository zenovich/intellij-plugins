import {type CodeInformation, Language, type SourceScript, type TypeScriptServiceScript} from "@volar/language-core"
import type * as TS from "./languageService"
import type {GetElementTypeResponse, Range} from "tsc-ide-plugin/protocol"
import type {ReverseMapper} from "tsc-ide-plugin/ide-get-element-type"
import {getServiceScript} from "@volar/typescript/lib/node/utils"
import {toGeneratedRange, toSourceRanges} from "@volar/typescript/lib/node/transform"
import {AngularVirtualCode} from "./code"
import type * as ts from "typescript"

function toSourceRange(sourceScript: SourceScript<string> | undefined, language: Language<string>, serviceScript: TypeScriptServiceScript, range: ts.TextRange, filter: (data: CodeInformation) => boolean): [fileName: string, range: ts.TextRange] | undefined {
  for (const result of toSourceRanges(sourceScript, language, serviceScript, range, filter)) {
    return result
  }
  return undefined
}

export function decorateIdeLanguageServiceExtensions(language: Language<string>, languageService: TS.LanguageService) {

  let {webStormGetElementType, webStormGetTypeProperties, webStormGetSymbolType} = languageService

  if (webStormGetElementType === undefined || webStormGetElementType === undefined || webStormGetSymbolType === undefined)
    return

  const ngReverseMapper = function (ts: typeof TS, sourceFile: TS.SourceFile, generatedRange: Range): { pos: number, end: number, fileName: string } | undefined {
    const [serviceScript, targetScript, sourceScript] =
      getServiceScript(language, sourceFile.fileName);
    if (targetScript?.associatedOnly) {
      return undefined;
    }
    const program = languageService.getProgram()
    const generatedFile = targetScript ? program?.getSourceFile(targetScript.id) : undefined;
    if (serviceScript && sourceFile && generatedFile) {
      try {
        let generatedRangePosStart = ts.getPositionOfLineAndCharacter(generatedFile, generatedRange.start.line, generatedRange.start.character)
        let generatedRangePosEnd = ts.getPositionOfLineAndCharacter(generatedFile, generatedRange.end.line, generatedRange.end.character)

        const sourceRange = toSourceRange(sourceScript, language, serviceScript, {pos: generatedRangePosStart, end: generatedRangePosEnd}, () => true);

        if (sourceRange !== undefined) {
          return {
            fileName: sourceRange[0],
            pos: sourceRange[1].pos,
            end: sourceRange[1].end
          }
        }
      }
      catch (e) {
        // ignore
      }
    }
    return undefined;
  }

  languageService.webStormGetElementType = (
    ts: typeof TS,
    fileName: string,
    range: Range,
    forceReturnType: boolean,
    reverseMapper?: ReverseMapper,
  ): GetElementTypeResponse => {
    const [serviceScript, targetScript, sourceScript] =
      getServiceScript(language, fileName);
    if (targetScript?.associatedOnly) {
      return undefined;
    }
    const program = languageService.getProgram()
    const sourceFile = program?.getSourceFile(fileName);
    const generatedFile = targetScript ? program?.getSourceFile(targetScript.id) : undefined;

    if (serviceScript && sourceFile && generatedFile) {
      try {
        let originalRangePosStart = ts.getPositionOfLineAndCharacter(sourceFile, range.start.line, range.start.character)
        let originalRangePosEnd = ts.getPositionOfLineAndCharacter(sourceFile, range.end.line, range.end.character)

        const generatedRange = toGeneratedRange(language, serviceScript, sourceScript, {pos: originalRangePosStart, end: originalRangePosEnd}, () => true);

        if (generatedRange !== undefined) {
          const start = ts.getLineAndCharacterOfPosition(generatedFile, generatedRange.pos)
          const end = ts.getLineAndCharacterOfPosition(generatedFile, generatedRange.end)
          return webStormGetElementType(ts as any, targetScript.id, {start, end}, forceReturnType, ngReverseMapper.bind(null, ts))
        }
      }
      catch (e) {
        // ignore
      }
      return undefined;
    }
    else {
      return webStormGetElementType(ts as any, fileName, range, forceReturnType, reverseMapper)
    }
  }

  languageService.webStormGetSymbolType = (
    ts,
    symbolId: number,
    _reverseMapper?: ReverseMapper,
  ) => {
    return webStormGetSymbolType(ts, symbolId, ngReverseMapper.bind(null, ts))
  }

  languageService.webStormGetTypeProperties = (
    ts,
    typeId: number,
    _reverseMapper?: ReverseMapper,
  ) => {
    return webStormGetTypeProperties(ts, typeId, ngReverseMapper.bind(null, ts))
  }

}

export function decorateNgLanguageServiceExtensions(language: Language<string>, languageService: TS.LanguageService) {
  languageService.webStormNgUpdateTranspiledTemplate = (
    _ts, fileName,transpiledCode,sourceCode: { [key: string]: string }, mappings
  ) => {
    const sourceScript = language.scripts.get(fileName)
    const virtualCode = sourceScript?.generated?.root
    if (sourceScript && virtualCode instanceof AngularVirtualCode) {
      virtualCode.transpiledTemplateUpdated(transpiledCode, sourceCode, mappings);
    }
  }
}