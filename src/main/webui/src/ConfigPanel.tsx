import { useQuery } from '@tanstack/react-query'
import { FileCode2, FileJson, Layers, RadioTower, Upload } from 'lucide-react'
import clsx from 'clsx'
import { useMemo, useRef, useState, type ReactNode } from 'react'
import { api, short, type LiveConfig, type MemberInfo } from './api'
import { useDemo } from './App'
import {
  configFor, groupSettings, kindOf, redactYaml, type ChainConfig, type ConfigFile, type SealedChain,
} from './chainConfig'
import { Badge, Card, Spinner } from './ui'

type View = 'yaml' | 'sealed' | 'live'
const MAX_UPLOAD_BYTES = 2 * 1024 * 1024

/** "How is this chain configured?": the draft as written, the sealed genesis settings, and what the node runs. */
export function ConfigPanel({ chainId }: { chainId: string }) {
  const { readNode, uploads } = useDemo()
  const served = useQuery({ queryKey: ['config-files'], queryFn: api.configFiles, staleTime: 60_000 })
  const live = useQuery({
    queryKey: ['live-config', chainId, readNode], queryFn: () => api.liveConfig(chainId, readNode),
    enabled: !!readNode, staleTime: 15_000, retry: 1,
  })
  const files = useMemo(() => [...uploads, ...(served.data?.files ?? [])], [uploads, served.data])
  const cfg = useMemo(() => configFor(chainId, files), [chainId, files])
  const [chosen, setChosen] = useState<View | null>(null)
  const view: View = chosen ?? (cfg.draft ? 'yaml' : cfg.sealed ? 'sealed' : 'live')

  const views: { id: View; label: string; icon: typeof Layers; available: boolean; hint: string }[] = [
    { id: 'yaml', label: 'As written', icon: FileCode2, available: !!cfg.draft || cfg.extras.length > 0, hint: 'The draft YAML the operators sealed' },
    { id: 'sealed', label: 'Sealed genesis', icon: FileJson, available: !!cfg.sealed, hint: 'Every setting fixed at seal, defaults included' },
    { id: 'live', label: 'Running', icon: RadioTower, available: true, hint: 'What the node reports now' },
  ]

  return (
    <div className="space-y-3">
      <Card className="p-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <div className="text-sm font-semibold">How {chainId} is configured</div>
            <div className="mt-1 flex flex-wrap gap-1.5 text-[11px]">
              {cfg.draft && <SourceChip file={cfg.draft.file} label="draft" />}
              {cfg.sealed && <SourceChip file={cfg.sealed.file} label="sealed" />}
              {cfg.extras.map((f) => <SourceChip key={f.path} file={f} label={f.kind === 'BINDINGS' ? 'bindings' : 'role plan'} />)}
              {live.data && <Badge tone="cyan"><RadioTower size={11} /> live · {live.data.node}</Badge>}
              {served.isLoading && <span className="text-faint"><Spinner /> reading files…</span>}
            </div>
          </div>
          <UploadButton />
        </div>
        {!cfg.known && !served.isLoading && <NoFilesNote configured={served.data?.configured ?? false} note={served.data?.note ?? null} />}
      </Card>

      <div className="flex gap-1 rounded-2xl border border-line bg-surface p-1">
        {views.map((v) => (
          <button key={v.id} onClick={() => setChosen(v.id)} disabled={!v.available} title={v.hint}
            className={clsx('flex flex-1 items-center justify-center gap-1.5 rounded-xl px-2 py-1.5 text-xs font-medium transition',
              view === v.id ? 'bg-raised text-ink shadow-inner' : 'text-muted hover:text-ink',
              !v.available && 'cursor-not-allowed opacity-40')}>
            <v.icon size={13} className={view === v.id ? 'text-accent' : ''} /> {v.label}
          </button>
        ))}
      </div>

      {view === 'yaml' && <YamlView cfg={cfg} />}
      {view === 'sealed' && cfg.sealed && <SealedView sealed={cfg.sealed} />}
      {view === 'live' && <LiveView query={live} sealed={cfg.sealed} />}
    </div>
  )
}

function SourceChip({ file, label }: { file: ConfigFile; label: string }) {
  return (
    <Badge tone={file.uploaded ? 'amber' : 'default'} className="font-mono">
      {label}: {file.path.split('/').slice(-2).join('/')}{file.uploaded ? ' (uploaded)' : ''}
    </Badge>
  )
}

function NoFilesNote({ configured, note }: { configured: boolean; note: string | null }) {
  return (
    <div className="mt-3 rounded-xl border border-dashed border-line bg-surface-2 p-3 text-xs text-muted">
      <p>
        No draft or sealed file for this chain {configured ? `(${note ?? 'not in the configured directory'})` : 'is configured on this deployment'}.
        The <b>Running</b> view below comes straight from the node.
      </p>
      <p className="mt-1.5">
        To see the YAML too, drop the application's <code className="font-mono">&lt;app&gt;.chain</code> or{' '}
        <code className="font-mono">&lt;app&gt;.draft.yaml</code> here (it stays in your browser), or start the client with{' '}
        <code className="whitespace-nowrap font-mono">-Ddemo.config-dir=&lt;dir&gt;</code>: the folder where you ran{' '}
        <code className="font-mono">yano.sh appchain new/seal</code>, a showcase instance (<code className="font-mono">data/showcase/&lt;instance&gt;</code>)
        or a node's <code className="font-mono">apps/</code> directory.
      </p>
    </div>
  )
}

function UploadButton() {
  const { addUploads } = useDemo()
  const input = useRef<HTMLInputElement>(null)
  const [message, setMessage] = useState<string | null>(null)
  const onFiles = async (list: FileList | null) => {
    if (!list?.length) return
    const accepted: ConfigFile[] = []
    const skipped: string[] = []
    for (const file of Array.from(list)) {
      if (file.size > MAX_UPLOAD_BYTES) { skipped.push(`${file.name} (too large)`); continue }
      const content = await file.text()
      const kind = kindOf(file.name, content)
      if (!kind) { skipped.push(file.name); continue }
      accepted.push({ path: file.name, kind, content, uploaded: true })
    }
    addUploads(accepted)
    setMessage(accepted.length
      ? `Loaded ${accepted.map((f) => f.path).join(', ')}${skipped.length ? `; skipped ${skipped.join(', ')}` : ''}`
      : `Not an app-chain file: ${skipped.join(', ')}`)
    if (input.current) input.current.value = ''
  }
  return (
    <div className="text-right">
      <button onClick={() => input.current?.click()}
        className="inline-flex items-center gap-1.5 rounded-xl border border-line px-2.5 py-1.5 text-xs font-medium text-muted hover:border-accent/50 hover:text-accent"
        title="Load a sealed .chain or a .draft.yaml from your machine. It is read in this page only and never uploaded.">
        <Upload size={13} /> Open a file
      </button>
      <input ref={input} type="file" multiple accept=".chain,.yaml,.yml,.json" className="hidden"
        onChange={(e) => void onFiles(e.target.files)} />
      {message && <div className="mt-1 max-w-[260px] text-[11px] text-faint">{message}</div>}
    </div>
  )
}

// ------------------------------------------------------------------ as written

function YamlView({ cfg }: { cfg: ChainConfig }) {
  const [showHeader, setShowHeader] = useState(false)
  if (!cfg.draft && !cfg.extras.length) {
    return <Card className="p-4 text-sm text-muted">No draft YAML for this chain. Open the application's <code className="font-mono">.draft.yaml</code> to see it as written.</Card>
  }
  return (
    <div className="space-y-3">
      {cfg.draft && (
        <Card className="overflow-hidden">
          <div className="flex items-center justify-between gap-2 border-b border-line/70 px-4 py-2 text-xs text-muted">
            <span>Application <b className="text-ink">{cfg.draft.app ?? '?'}</b> · this chain's entry</span>
            <button onClick={() => setShowHeader((v) => !v)} className="text-accent hover:underline">
              {showHeader ? 'Hide' : 'Show'} application settings
            </button>
          </div>
          {showHeader && <Yaml text={redactYaml(cfg.draft.header)} className="border-b border-line/70 bg-surface-2/60" />}
          <Yaml text={redactYaml(cfg.draft.section)} />
        </Card>
      )}
      {cfg.extras.map((file) => (
        <Card key={file.path} className="overflow-hidden">
          <div className="border-b border-line/70 px-4 py-2 text-xs text-muted">
            {file.kind === 'BINDINGS' ? 'Bindings plan' : 'Role plan'} · <span className="font-mono">{file.path.split('/').pop()}</span>
          </div>
          <Yaml text={redactYaml(file.content.trimEnd())} />
        </Card>
      ))}
      <p className="px-1 text-[11px] text-faint">
        Recipes expand at seal time into the full settings shown under <b>Sealed genesis</b>; anything not written here
        takes the recipe's default.
      </p>
    </div>
  )
}

/** Small YAML highlighter: comments, keys, list dashes, strings, numbers and booleans. */
export function Yaml({ text, className }: { text: string; className?: string }) {
  return (
    <pre className={clsx('max-h-[520px] overflow-auto px-4 py-3 font-mono text-[12px] leading-[1.6]', className)}>
      {text.split('\n').map((line, i) => <div key={i}>{yamlLine(line)}</div>)}
    </pre>
  )
}

function yamlLine(line: string): ReactNode {
  if (!line.trim()) return ' '
  const comment = /^(\s*)(#.*)$/.exec(line)
  if (comment) return <>{comment[1]}<span className="text-faint italic">{comment[2]}</span></>
  const m = /^(\s*)(-\s+)?([A-Za-z0-9_."'-]+)(\s*:)(.*)$/.exec(line)
  if (!m) {
    const dash = /^(\s*)(-\s+)(.*)$/.exec(line)
    return dash ? <>{dash[1]}<span className="text-faint">{dash[2]}</span>{value(dash[3])}</> : line
  }
  return (
    <>
      {m[1]}{m[2] && <span className="text-faint">{m[2]}</span>}
      <span className="text-cyan">{m[3]}</span><span className="text-faint">{m[4]}</span>{value(m[5])}
    </>
  )
}

function value(rest: string): ReactNode {
  const hash = rest.search(/\s#/)
  const body = hash >= 0 ? rest.slice(0, hash) : rest
  const tail = hash >= 0 ? rest.slice(hash) : ''
  const trimmed = body.trim()
  const tone = /^["'].*["']$/.test(trimmed) ? 'text-accent'
    : /^-?\d+(\.\d+)?$/.test(trimmed) || /^(true|false|null)$/.test(trimmed) ? 'text-violet' : 'text-ink'
  return <><span className={tone}>{body}</span>{tail && <span className="text-faint italic">{tail}</span>}</>
}

// ------------------------------------------------------------------ sealed

function memberName(members: MemberInfo[], key: string | undefined | null): string {
  if (!key) return '—'
  const m = members.find((x) => x.memberKey === key)
  return m ? `${m.label} · ${short(key, 8, 4)}` : short(key, 8, 4)
}

function Facts({ items }: { items: [string, ReactNode][] }) {
  return (
    <dl className="grid grid-cols-1 gap-x-4 gap-y-2 text-sm sm:grid-cols-2">
      {items.map(([label, val]) => (
        <div key={label} className={clsx('min-w-0', typeof val !== 'string' && 'sm:col-span-2')}>
          <dt className="text-[11px] text-faint">{label}</dt>
          <dd className="break-words font-medium text-ink">{val}</dd>
        </div>
      ))}
    </dl>
  )
}

function SealedView({ sealed }: { sealed: SealedChain }) {
  const { members } = useDemo()
  const s = sealed.settings
  const anchor = s['anchor.mode']
    ? `${s['anchor.mode']}${s['anchor.every-blocks'] ? `, every ${s['anchor.every-blocks']} blocks` : ''}${s['anchor.max-interval-minutes'] ? ` or ${s['anchor.max-interval-minutes']} min` : ''}`
    : 'not configured'
  const facts: [string, ReactNode][] = [
    ['Application', `${sealed.app}${sealed.network ? ` · ${sealed.network}` : ''}`],
    ['Recipe', sealed.recipe ?? '—'],
    ['State machine', s['state-machine'] ?? '—'],
    ['Finality', s.threshold ? `${s.threshold} of ${sealed.members.length} signatures` : '—'],
    ['Membership', s['membership.mode'] ?? sealed.membership ?? '—'],
    ['Sequencing', `${s['sequencer.mode'] ?? '—'}${s['sequencer.proposer'] ? ` · ${memberName(members, s['sequencer.proposer'])}` : ''}`],
    ['Block interval', s['block.interval-ms'] ? `${s['block.interval-ms']} ms` : '—'],
    ['Cardano anchoring', anchor],
    ['Topics', s['topics.mode'] ? `${s['topics.mode']} · unregistered ${s['topics.unregistered-topics'] ?? '—'}` : '—'],
    ['State commitment', s['state.commitment-profile'] ?? '—'],
  ]
  if (s['effects.enabled'] === 'true') facts.push(['Effects', `on · gate ${s['effects.default-gate'] ?? '—'} · max ${s['effects.max-per-block'] ?? '—'}/block`])
  const machineKeys = Object.keys(s).filter((k) => k.startsWith('machines.'))
  if (machineKeys.length) {
    facts.push(['Machine settings', (
      <ul className="space-y-0.5 font-mono text-[12px] font-normal">
        {machineKeys.map((k) => <li key={k} className="break-all"><span className="text-cyan">{k.slice(9)}</span> = {s[k]}</li>)}
      </ul>
    )])
  }

  return (
    <div className="space-y-3">
      <Card className="p-4">
        <Facts items={facts} />
        <div className="mt-3 flex flex-wrap gap-1.5">
          {sealed.bundles.map((b) => <Badge key={b} className="font-mono">{b}</Badge>)}
        </div>
      </Card>
      <Card className="p-4">
        <div className="mb-2 text-xs font-semibold text-muted">Members ({sealed.members.length})</div>
        <ul className="grid gap-1 text-xs sm:grid-cols-2">
          {sealed.members.map((key) => <li key={key} className="font-mono text-muted">{memberName(members, key)}</li>)}
        </ul>
      </Card>
      <Card className="overflow-hidden">
        <div className="border-b border-line/70 px-4 py-2 text-xs text-muted">
          All sealed settings ({Object.keys(s).length}) from <span className="font-mono">{sealed.file.path.split('/').pop()}</span>
          {sealed.yanoXVersion && <> · Yano X {sealed.yanoXVersion}</>}
        </div>
        <div className="max-h-[520px] overflow-auto">
          {groupSettings(s).map(([group, rows]) => (
            <div key={group} className="border-b border-line/50 last:border-0">
              <div className="bg-surface-2/70 px-4 py-1 text-[10px] font-semibold uppercase tracking-[0.16em] text-faint">{group}</div>
              <table className="w-full text-[12px]">
                <tbody>
                  {rows.map(([key, val]) => (
                    <tr key={key} className="align-top">
                      <td className="w-[45%] px-4 py-1 font-mono text-cyan">{key}</td>
                      <td className="break-all px-4 py-1 font-mono text-ink">{key === 'members' ? `${val.split(',').length} keys` : val}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ))}
          {Object.keys(sealed.nodeSettings).length > 0 && (
            <div>
              <div className="bg-surface-2/70 px-4 py-1 text-[10px] font-semibold uppercase tracking-[0.16em] text-faint">per node (filled in on each member)</div>
              <table className="w-full text-[12px]"><tbody>
                {Object.entries(sealed.nodeSettings).map(([key, val]) => (
                  <tr key={key}><td className="w-[45%] px-4 py-1 font-mono text-cyan">{key}</td><td className="px-4 py-1 font-mono text-muted">{val}</td></tr>
                ))}
              </tbody></table>
            </div>
          )}
        </div>
      </Card>
    </div>
  )
}

// ------------------------------------------------------------------ live

interface Manifest {
  applicationId?: string; applicationVersion?: string
  components?: { id: string; version: string; configurationId?: string; topics?: string[]; querySubjects?: string[] }[]
  workflows?: { id: string; version: string; participantComponentIds?: string[]; topic?: string; effectTypes?: string[] }[]
  crossCutting?: { capabilityId: string; enabled: boolean; origin?: string }[]
}

function LiveView({ query, sealed }: {
  query: { data?: LiveConfig; isLoading: boolean; isError: boolean }
  sealed: SealedChain | null
}) {
  const { members } = useDemo()
  const [raw, setRaw] = useState(false)
  if (query.isLoading) return <Card className="p-4 text-sm text-muted"><Spinner /> Reading the node…</Card>
  if (query.isError || !query.data) return <Card className="p-4 text-sm text-rose">The node did not return this chain's status.</Card>
  const d = query.data
  const cp = d.consensusProfile ?? {}
  const facts: [string, ReactNode][] = [
    ['State machine', d.stateMachine ?? '—'],
    ['Finality', `${d.membershipActiveThreshold ?? d.threshold ?? '—'} of ${d.membershipActiveMembers ?? d.members ?? '—'} signatures`],
    ['Membership', `${d.membershipMode ?? '—'}${d.membershipEpochFromHeight ? ` · current set since height ${d.membershipEpochFromHeight}` : ''}`],
    ['Sequencer', `${d.sequencer?.mode ?? '—'} · ${d.sequencer?.consensusProtocol ?? ''} · proposer ${memberName(members, d.sequencer?.proposer)}`],
    ['Block interval', d.configuredBlockIntervalMs ? `${d.configuredBlockIntervalMs} ms` : '—'],
    ['Limits', `${cp.maxBlockMessages ?? '—'} msgs / ${fmtBytes(cp.maxBlockBytes)} per block · ${fmtBytes(cp.maxMessageBytes)} per message`],
    ['L1 stability depth', cp.l1StabilityDepth != null ? `${cp.l1StabilityDepth} Cardano blocks` : '—'],
    ['State commitment', `${d.stateCommitment?.profile ?? '—'} (${d.stateCommitment?.verificationTarget ?? d.stateCommitment?.backend ?? ''})`],
    ['Cardano anchoring', d.anchor ? `${d.anchor.enabled ? 'on' : 'off'} · ${d.anchor.mode ?? '—'}${d.anchor.bootstrapped ? ' · bootstrapped' : ''}` : '—'],
  ]
  if (d.effects?.enabled) facts.push(['Effects', `on · gate ${d.effects.defaultGate ?? '—'} · max ${d.effects.maxPerBlock ?? '—'}/block${d.effects.executorOnThisNode ? ' · executor on this node' : ''}`])
  const sealedThreshold = sealed?.settings.threshold
  const changed = sealedThreshold && d.membershipActiveThreshold != null && String(d.membershipActiveThreshold) !== sealedThreshold
  const manifest = d.capabilityManifest as Manifest | undefined

  return (
    <div className="space-y-3">
      <Card className="p-4">
        <Facts items={facts} />
        {changed && (
          <p className="mt-3 rounded-lg bg-amber/10 px-3 py-2 text-xs text-amber">
            The running threshold ({d.membershipActiveThreshold}) differs from the sealed one ({sealedThreshold}): membership
            changed through governance after the seal.
          </p>
        )}
      </Card>
      {manifest && ((manifest.components?.length ?? 0) > 1 || (manifest.workflows?.length ?? 0) > 0)
        ? <ManifestView manifest={manifest} /> : null}
      <Card className="overflow-hidden">
        <button onClick={() => setRaw((v) => !v)} className="w-full px-4 py-2 text-left text-xs text-accent hover:underline">
          {raw ? 'Hide' : 'Show'} the node's configuration JSON (from /status on {d.node})
        </button>
        {raw && <pre className="max-h-[420px] overflow-auto border-t border-line/70 px-4 py-3 font-mono text-[11px]">{JSON.stringify(d, null, 2)}</pre>}
      </Card>
    </div>
  )
}

function ManifestView({ manifest }: { manifest: Manifest }) {
  return (
    <Card className="p-4">
      <div className="flex items-center gap-2 text-sm font-semibold">
        <Layers size={15} className="text-violet" /> Composition · {manifest.applicationId} {manifest.applicationVersion}
      </div>
      <p className="mt-1 text-xs text-muted">This chain is composed from reusable components; a workflow ties them together.</p>
      <div className="mt-3 grid gap-2 sm:grid-cols-2">
        {manifest.components!.map((c) => (
          <div key={c.id} className="rounded-xl border border-line/70 bg-surface-2 px-3 py-2">
            <div className="text-sm font-semibold">{c.id} <span className="font-mono text-[11px] text-faint">{c.version}</span></div>
            {c.configurationId && <div className="font-mono text-[11px] text-muted">{c.configurationId}</div>}
            {!!c.topics?.length && <div className="mt-1 font-mono text-[11px] text-cyan">{c.topics.join(', ')}</div>}
            {!!c.querySubjects?.length && <div className="font-mono text-[11px] text-faint">queries: {c.querySubjects.join(', ')}</div>}
          </div>
        ))}
      </div>
      {manifest.workflows?.map((w) => (
        <div key={w.id} className="mt-3 rounded-xl border border-violet/30 bg-violet/5 px-3 py-2 text-xs">
          <div className="font-semibold text-ink">Workflow {w.id} <span className="font-mono text-faint">{w.version}</span></div>
          <div className="mt-1 text-muted">
            {(w.participantComponentIds ?? []).join(' → ')}
            {w.topic && <> · topic <span className="font-mono text-cyan">{w.topic}</span></>}
            {!!w.effectTypes?.length && <> · effects <span className="font-mono">{w.effectTypes.join(', ')}</span></>}
          </div>
        </div>
      ))}
      {!!manifest.crossCutting?.length && (
        <div className="mt-3 flex flex-wrap gap-1.5">
          {manifest.crossCutting.filter((c) => c.enabled).map((c) => (
            <Badge key={c.capabilityId} tone="cyan" className="font-mono">{c.capabilityId}</Badge>
          ))}
        </div>
      )}
    </Card>
  )
}

function fmtBytes(n?: number): string {
  if (n == null) return '—'
  if (n >= 1024 * 1024) return `${(n / 1024 / 1024).toFixed(n % (1024 * 1024) ? 1 : 0)} MiB`
  if (n >= 1024) return `${(n / 1024).toFixed(n % 1024 ? 1 : 0)} KiB`
  return `${n} B`
}
