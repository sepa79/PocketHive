import type { BundleFilePayload } from './scenariosApi'

export function monacoLanguageForBundleFile(editorKind: BundleFilePayload['editorKind']) {
  if (editorKind === 'yaml') return 'yaml'
  if (editorKind === 'json') return 'json'
  if (editorKind === 'markdown') return 'markdown'
  return 'plaintext'
}
