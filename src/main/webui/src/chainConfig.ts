// How a chain is configured, read from the cluster's public app-chain files: the sealed `<app>.chain` (JSON, the
// genesis of every chain in the application), the `<app>.draft.yaml` the operators sealed, and per-chain plans
// (`<chain>.bindings.yaml`, `<chain>.role-plan.yaml`). The same code reads the files served from demo.config-dir
// and files a visitor drops into the page (those never leave the browser).

export type ConfigKind = 'SEALED' | 'DRAFT' | 'BINDINGS' | 'ROLE_PLAN'
export interface ConfigFile { path: string; kind: ConfigKind; content: string; uploaded?: boolean }
export interface ConfigListing { configured: boolean; note: string | null; files: ConfigFile[] }

export interface SealedChain {
  file: ConfigFile
  app: string
  network: string | null
  membership: string | null
  yanoXVersion: string | null
  lead: string | null
  members: string[]
  recipe: string | null
  bundles: string[]
  settings: Record<string, string>
  nodeSettings: Record<string, string>
}

export interface DraftChain {
  file: ConfigFile
  app: string | null
  /** Application-level lines (name, network, operators, threshold, …) without the leading comment banner. */
  header: string
  /** This chain's item of the `chains:` list, as written (comments kept). */
  section: string
}

export interface ChainConfig {
  chainId: string
  sealed: SealedChain | null
  draft: DraftChain | null
  extras: ConfigFile[]
  /** Chain ids found in any file (for "is this file about this cluster?" hints). */
  known: boolean
}

const SECRET_KEY = /(seed|secret|password|passphrase|token|api[-_]?key|private[-_]?key|mnemonic)/i
const PLACEHOLDER = /^\s*["']?\$\{[A-Z0-9_]+\}["']?\s*$/

/** Never show a value whose key looks like a secret, unless it is a ${PLACEHOLDER}. */
export function redactValue(key: string, value: string): string {
  return SECRET_KEY.test(key) && !PLACEHOLDER.test(value) ? '•••••• (hidden)' : value
}

/** Line-wise redaction for YAML text: `key: value` lines whose key looks like a secret. */
export function redactYaml(text: string): string {
  return text.split('\n').map((line) => {
    const m = /^(\s*-?\s*)([A-Za-z0-9_.-]+)(\s*:\s*)(\S.*)$/.exec(line)
    if (!m || !SECRET_KEY.test(m[2]) || PLACEHOLDER.test(m[4].replace(/\s+#.*$/, ''))) return line
    return `${m[1]}${m[2]}${m[3]}"•••••• (hidden)"`
  }).join('\n')
}

/** Guess the kind of a dropped file from its name, then its content. */
export function kindOf(name: string, content: string): ConfigKind | null {
  if (name.endsWith('.chain')) return 'SEALED'
  if (name.endsWith('.draft.yaml') || name.endsWith('.draft.yml')) return 'DRAFT'
  if (name.endsWith('.bindings.yaml')) return 'BINDINGS'
  if (name.endsWith('.role-plan.yaml')) return 'ROLE_PLAN'
  const trimmed = content.trimStart()
  if (trimmed.startsWith('{')) return parseSealed(content) ? 'SEALED' : null
  if (/^chains:\s*$/m.test(content)) return 'DRAFT'
  return null
}

interface SealedJson {
  format?: string
  genesis?: {
    name?: string; network?: string; membership?: string; yanoXVersion?: string; lead?: string
    members?: unknown[]
    chains?: { id?: string; recipe?: string; bundles?: string[]; settings?: Record<string, unknown>; nodeSettings?: Record<string, unknown> }[]
  }
}

function parseSealed(content: string): SealedJson | null {
  try {
    const parsed = JSON.parse(content) as SealedJson
    return typeof parsed?.format === 'string' && parsed.format.startsWith('yano-appchain') && Array.isArray(parsed.genesis?.chains)
      ? parsed : null
  } catch {
    return null
  }
}

const strings = (record: Record<string, unknown> | undefined) =>
  Object.fromEntries(Object.entries(record ?? {}).map(([k, v]) => [k, typeof v === 'string' ? v : JSON.stringify(v)]))

/** Member keys in a sealed genesis are objects or strings depending on the version; keep the hex keys. */
function memberKeys(members: unknown[] | undefined): string[] {
  return (members ?? []).map((m) => {
    if (typeof m === 'string') return m
    const o = m as Record<string, unknown>
    return String(o.key ?? o.memberKey ?? o.publicKey ?? o.signingKey ?? '')
  }).filter((k) => /^[0-9a-f]{64}$/.test(k))
}

function sealedChain(file: ConfigFile, chainId: string): SealedChain | null {
  const parsed = parseSealed(file.content)
  const genesis = parsed?.genesis
  const chain = genesis?.chains?.find((c) => c.id === chainId)
  if (!genesis || !chain) return null
  const settings = strings(chain.settings)
  const fromSettings = settings.members ? settings.members.split(',').map((k) => k.trim()).filter(Boolean) : []
  return {
    file,
    app: genesis.name ?? file.path.split('/').pop()!.replace(/\.chain$/, ''),
    network: genesis.network ?? null,
    membership: genesis.membership ?? null,
    yanoXVersion: genesis.yanoXVersion ?? null,
    lead: genesis.lead ?? null,
    members: fromSettings.length ? fromSettings : memberKeys(genesis.members),
    recipe: chain.recipe ?? null,
    bundles: chain.bundles ?? [],
    settings,
    nodeSettings: strings(chain.nodeSettings),
  }
}

const indentOf = (line: string) => line.length - line.trimStart().length
const isContent = (line: string) => line.trim() !== '' && !line.trimStart().startsWith('#')

/** Cuts one chain's item out of a draft's `chains:` list, keeping its comments and formatting. */
function draftChain(file: ConfigFile, chainId: string): DraftChain | null {
  const lines = file.content.split('\n')
  const listAt = lines.findIndex((l) => /^chains:\s*(#.*)?$/.test(l))
  if (listAt < 0) return null
  const itemRe = /^(\s*)-\s+id:\s*["']?([A-Za-z0-9-]+)["']?\s*(#.*)?$/
  let start = -1
  let indent = 0
  for (let i = listAt + 1; i < lines.length; i++) {
    const m = itemRe.exec(lines[i])
    if (m && m[2] === chainId) { start = i; indent = m[1].length; break }
  }
  if (start < 0) return null
  let end = lines.length
  for (let i = start + 1; i < lines.length; i++) {
    const line = lines[i]
    if (!isContent(line)) continue
    const lineIndent = indentOf(line)
    if (lineIndent < indent || (lineIndent === indent && line.trimStart().startsWith('-'))) { end = i; break }
  }
  while (end > start + 1 && !isContent(lines[end - 1])) end--
  const header = lines.slice(0, listAt)
  let first = 0
  while (first < header.length && !isContent(header[first])) first++
  const name = /^name:\s*["']?([A-Za-z0-9-]+)/m.exec(file.content)
  return {
    file,
    app: name ? name[1] : null,
    header: header.slice(first).join('\n').trimEnd(),
    section: ['chains:', ...lines.slice(start, end)].join('\n'),
  }
}

/** Every chain id the files describe (sealed genesis or draft list). */
export function chainIdsIn(files: ConfigFile[]): string[] {
  const ids = new Set<string>()
  for (const f of files) {
    if (f.kind === 'SEALED') parseSealed(f.content)?.genesis?.chains?.forEach((c) => c.id && ids.add(c.id))
    if (f.kind === 'DRAFT') {
      for (const m of f.content.matchAll(/^\s*-\s+id:\s*["']?([A-Za-z0-9-]+)/gm)) ids.add(m[1])
    }
  }
  return [...ids]
}

/** The configuration of one chain across the given files. Uploaded files win over served ones. */
export function configFor(chainId: string, files: ConfigFile[]): ChainConfig {
  const ordered = [...files.filter((f) => f.uploaded), ...files.filter((f) => !f.uploaded)]
  let sealed: SealedChain | null = null
  let draft: DraftChain | null = null
  for (const f of ordered) {
    if (!sealed && f.kind === 'SEALED') sealed = sealedChain(f, chainId)
    if (!draft && f.kind === 'DRAFT') draft = draftChain(f, chainId)
  }
  const extras = ordered.filter((f) => (f.kind === 'BINDINGS' || f.kind === 'ROLE_PLAN')
    && f.path.split('/').pop()!.startsWith(`${chainId}.`))
  return { chainId, sealed, draft, extras, known: !!(sealed || draft || extras.length) }
}

/** Sealed settings grouped by their first dotted segment ("anchor", "block", "topics", …); bare keys under "chain". */
export function groupSettings(settings: Record<string, string>): [string, [string, string][]][] {
  const groups = new Map<string, [string, string][]>()
  for (const [key, value] of Object.entries(settings).sort(([a], [b]) => a.localeCompare(b))) {
    const group = key.includes('.') ? key.slice(0, key.indexOf('.')) : 'chain'
    if (!groups.has(group)) groups.set(group, [])
    groups.get(group)!.push([key, redactValue(key, value)])
  }
  const order = ['chain', 'block', 'sequencer', 'membership', 'state', 'anchor', 'topics', 'effects', 'machines', 'l1']
  return [...groups.entries()].sort(([a], [b]) => {
    const ia = order.indexOf(a), ib = order.indexOf(b)
    return (ia < 0 ? order.length : ia) - (ib < 0 ? order.length : ib) || a.localeCompare(b)
  })
}
