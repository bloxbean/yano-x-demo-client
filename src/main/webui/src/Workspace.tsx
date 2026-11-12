import { useMutation, useQuery } from '@tanstack/react-query'
import { Activity, Anchor, ArrowLeft, Coins, Dices, FileCog, FlaskConical, KeyRound, Lightbulb, Search, Send, ShieldCheck, UserRound } from 'lucide-react'
import clsx from 'clsx'
import { useEffect, useState } from 'react'
import { api, quorumText, type ActorInfo, type ChainSpec, type Command, type Field, type LookupResult, type MemberInfo } from './api'
import { useDemo } from './App'
import { ConfigPanel } from './ConfigPanel'
import { CARDANO_CHAINS, CardanoTab, useCardano } from './OnCardano'
import { SubmissionList } from './Submissions'
import { FactsGrid, ProofButton, VerifyPanel } from './Verify'
import { Badge, Button, Card, LiveDot, Mono, Spinner, accent, go } from './ui'

type Tab = 'try' | 'lookup' | 'activity' | 'verify' | 'cardano' | 'config'

/** Identifier fields carried from one command to the next within a chain (per page session). */
const CARRIED = ['item', 'key', 'entity', 'order', 'proposal', 'release', 'content', 'reference', 'product', 'note']
const lastIds: Record<string, Record<string, string>> = {}

export function Workspace({ chainId }: { chainId: string }) {
  const { readNode, config, members } = useDemo()
  const chain = useQuery({
    queryKey: ['chain', chainId, readNode], queryFn: () => api.chain(chainId, readNode), refetchInterval: 5_000,
    enabled: !!readNode,
  })
  const [tab, setTab] = useState<Tab>('try')
  const onCardano = useCardano()
  const [actingNode, setActingNode] = useState<string | undefined>()

  useEffect(() => {
    if (!actingNode && config?.nodes.length) {
      setActingNode((config.nodes.find((n) => n.isDefault) ?? config.nodes[0]).id)
    }
  }, [config, actingNode])

  if (chain.isLoading || !chain.data) {
    return <div className="flex items-center gap-2 py-16 text-muted">{chain.isError ? 'Chain not available.' : <><Spinner /> Loading…</>}</div>
  }
  const spec = chain.data.spec
  const live = chain.data.live
  const a = accent(spec.accent)
  const tabs: { id: Tab; label: string; icon: typeof Send; hidden?: boolean }[] = [
    { id: 'try', label: 'Try it', icon: FlaskConical, hidden: spec.commands.length === 0 },
    { id: 'lookup', label: 'Look up', icon: Search, hidden: spec.lookups.length === 0 },
    { id: 'activity', label: 'Activity', icon: Activity },
    { id: 'verify', label: 'Verify', icon: ShieldCheck },
    { id: 'cardano', label: 'On Cardano', icon: Coins, hidden: !CARDANO_CHAINS[spec.id] || !onCardano.data?.enabled },
    { id: 'config', label: 'Config', icon: FileCog },
  ]

  return (
    <div className="space-y-6">
      <button onClick={() => go('/')} className="inline-flex items-center gap-1.5 text-sm text-muted hover:text-accent">
        <ArrowLeft size={15} /> All chains
      </button>

      <section className="rise relative overflow-hidden rounded-3xl border border-line bg-surface p-6 sm:p-8">
        <div className={clsx('pointer-events-none absolute -right-24 -top-24 h-72 w-72 rounded-full bg-gradient-to-br to-transparent blur-3xl', a.glow)} />
        <div className="relative flex flex-wrap items-start justify-between gap-6">
          <div className="max-w-2xl">
            <div className={clsx('text-xs font-semibold uppercase tracking-[0.18em]', a.text)}>{spec.machine} · {spec.id}</div>
            <h1 className="mt-2 text-3xl font-bold sm:text-4xl">{spec.title}</h1>
            <p className="mt-2 text-lg text-muted">{spec.tagline}</p>
            <p className="mt-4 text-sm leading-relaxed text-muted">{spec.story}</p>
          </div>
          <div className="grid min-w-[220px] grid-cols-2 gap-3 text-sm">
            <Stat label="Block height" value={live.tipHeight} live={live.running} />
            <Stat label="Anchored on Cardano" value={live.anchor?.anchoredHeight ?? '—'} icon={<Anchor size={13} className="text-lime" />} />
            <Stat label="Members connected" value={`${live.peersUp + 1} / ${live.peers + 1}`} />
            <Stat label="Finality" value={quorumText(config)} />
          </div>
        </div>
      </section>

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)]">
        <div className="space-y-4">
          {spec.commands.length > 0 && (
            <PersonaSwitcher members={members} acting={actingNode} onChange={setActingNode}
              relayOnly={spec.commands.every((c) => c.personaKind === 'ACTOR')}
              mixed={spec.commands.some((c) => c.personaKind === 'ACTOR')} />
          )}
          <div className="flex gap-1 rounded-2xl border border-line bg-surface p-1">
            {tabs.filter((t) => !t.hidden).map((t) => (
              <button key={t.id} onClick={() => setTab(t.id)}
                className={clsx('flex flex-1 items-center justify-center gap-2 whitespace-nowrap rounded-xl px-3 py-2 text-sm font-medium transition',
                  tab === t.id ? 'bg-raised text-ink shadow-inner' : 'text-muted hover:text-ink')}>
                <t.icon size={15} className={tab === t.id ? 'text-accent' : ''} /> {t.label}
              </button>
            ))}
          </div>
          {tab === 'try' && actingNode && <TryIt spec={spec} actingNode={actingNode} />}
          {tab === 'lookup' && <Lookups spec={spec} />}
          {tab === 'activity' && <ActivityList chainId={spec.id} />}
          {tab === 'verify' && <VerifyPanel chainId={spec.id} live={live} />}
          {tab === 'cardano' && <CardanoTab chainId={spec.id} />}
          {tab === 'config' && <ConfigPanel chainId={spec.id} />}
        </div>
        <SubmissionList chainId={spec.id} anchor={live.anchor} />
      </div>
    </div>
  )
}

function Stat({ label, value, live, icon }: { label: string; value: string | number; live?: boolean; icon?: React.ReactNode }) {
  return (
    <div className="rounded-2xl border border-line/70 bg-surface-2 px-3 py-2.5">
      <div className="text-[11px] text-faint">{label}</div>
      <div className="mt-0.5 flex items-center gap-1.5 font-mono text-base text-ink">
        {live && <LiveDot />}{icon}{value}
      </div>
    </div>
  )
}

function PersonaSwitcher({ members, acting, onChange, relayOnly, mixed }: {
  members: MemberInfo[]; acting: string | undefined; onChange: (node: string) => void; relayOnly?: boolean; mixed?: boolean
}) {
  return (
    <Card className="p-4">
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-2 text-sm font-semibold">
          <UserRound size={16} className="text-accent" /> {relayOnly ? 'Relayed by' : mixed ? 'Member node' : 'Acting as'}
        </div>
        <span className="text-right text-[11px] text-faint">
          {mixed ? 'People sign their own statements; the member node relays and signs the envelope'
            : 'Messages are signed by the member you choose'}
        </span>
      </div>
      <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-5">
        {members.map((m, i) => (
          <button key={m.nodeId} onClick={() => onChange(m.nodeId)} disabled={!m.memberKey}
            className={clsx('rounded-xl border px-3 py-2 text-left transition',
              acting === m.nodeId ? 'border-accent/60 bg-accent/10 shadow-[0_0_0_1px_rgba(19,134,106,0.2)]' : 'border-line hover:border-accent/30',
              !m.memberKey && 'opacity-40')}>
            <span className={clsx('grid h-6 w-6 place-items-center rounded-lg text-[11px] font-bold',
              acting === m.nodeId ? 'bg-accent text-bg' : 'bg-ink/5 text-muted')}>{i}</span>
            <span className="mt-1.5 block truncate text-sm font-medium" title={m.label}>{m.label}</span>
            <Mono className="block truncate text-[10px]">{m.shortKey ?? 'unreachable'}</Mono>
          </button>
        ))}
      </div>
    </Card>
  )
}

function TryIt({ spec, actingNode }: { spec: ChainSpec; actingNode: string }) {
  const [commandId, setCommandId] = useState(spec.commands[0]?.id)
  const command = spec.commands.find((c) => c.id === commandId) ?? spec.commands[0]
  return (
    <div className="space-y-3">
      {spec.commands.length > 1 && (
        <div className="flex flex-wrap gap-2">
          {spec.commands.map((c) => (
            <button key={c.id} onClick={() => setCommandId(c.id)}
              className={clsx('rounded-full border px-3.5 py-1.5 text-sm transition',
                c.id === command.id ? 'border-accent/60 bg-accent/10 text-accent' : 'border-line text-muted hover:text-ink')}>
              {c.title}
            </button>
          ))}
        </div>
      )}
      <CommandForm key={`${spec.id}:${command.id}`} chainId={spec.id} command={command} actingNode={actingNode} />
    </div>
  )
}

function CommandForm({ chainId, command, actingNode }: { chainId: string; command: Command; actingNode: string }) {
  const { members, track, config } = useDemo()
  const actorsQuery = useQuery({ queryKey: ['actors'], queryFn: api.actors, staleTime: Infinity })
  const allowed = (actorsQuery.data ?? []).filter((a) => command.actors.includes(a.id))
  const [actorId, setActorId] = useState<string | undefined>(command.actors[0])
  const actor = allowed.find((a) => a.id === actorId)
  const [values, setValues] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const acting = members.find((m) => m.nodeId === actingNode)

  const mock = useMutation({
    mutationFn: () => api.mock(chainId, command.id, actingNode),
    retry: 2,
    onSuccess: (data) => {
      // Follow-up commands (approve, reject, delete, …) continue with the id the visitor just used.
      const carried = lastIds[chainId] ?? {}
      const merged = { ...data }
      for (const name of CARRIED) if (name in merged && carried[name]) merged[name] = carried[name]
      setValues(merged)
      setError(null)
    },
  })
  const loadMock = mock.mutate
  useEffect(() => { loadMock() }, [loadMock])

  const submit = useMutation({
    mutationFn: () => api.submit(chainId, command.id, actingNode, values, command.personaKind === 'ACTOR' ? actorId : undefined),
    onSuccess: (submission) => {
      track(submission.id, chainId)
      setError(null)
      lastIds[chainId] = { ...(lastIds[chainId] ?? {}), ...Object.fromEntries(CARRIED.filter((n) => values[n]).map((n) => [n, values[n]])) }
    },
    onError: (e: Error) => setError(e.message),
  })

  return (
    <Card className="p-5">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h3 className="text-lg font-semibold">{command.title}</h3>
          <p className="text-sm text-muted">{command.summary}</p>
        </div>
        <Button variant="subtle" onClick={() => mock.mutate()} title="Fill in a fresh example">
          <Dices size={15} /> New example
        </Button>
      </div>
      <div className="mt-3 flex gap-2 rounded-xl border border-lime/20 bg-lime/5 px-3 py-2 text-xs text-muted">
        <Lightbulb size={14} className="mt-0.5 shrink-0 text-lime" />
        <span><b className="text-ink">What this shows:</b> {command.demonstrates}</span>
      </div>
      {command.personaKind === 'ACTOR' && (
        <ActorPicker actors={allowed} selected={actorId} onChange={setActorId} />
      )}
      <form className="mt-4 space-y-4" onSubmit={(e) => { e.preventDefault(); submit.mutate() }}>
        {command.fields.map((field) => (
          <FieldInput key={field.name} field={field} value={values[field.name] ?? ''} members={members}
            onChange={(v) => setValues((prev) => ({ ...prev, [field.name]: v }))} />
        ))}
        {error && <div className="rounded-xl border border-rose/30 bg-rose/10 px-3 py-2 text-sm text-rose">{error}</div>}
        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-line/70 pt-4">
          <span className="text-xs text-faint">
            {command.personaKind === 'ACTOR'
              ? <>Signed by <b className="text-ink">{actor?.label ?? '…'}</b>, relayed by <b className="text-ink">{acting?.label ?? actingNode}</b></>
              : <>Signed by <b className="text-ink">{acting?.label ?? actingNode}</b></>}
            {' '}· limit {config?.writesPerMinute ?? 20} / minute
          </span>
          <Button type="submit" disabled={submit.isPending || mock.isPending || (command.personaKind === 'ACTOR' && !actor)}>
            {submit.isPending ? <Spinner /> : <Send size={15} />} {command.personaKind === 'ACTOR'
              ? `Sign as ${actor?.label.split(' ')[0] ?? '…'}` : `Submit as ${acting?.label ?? actingNode}`}
          </Button>
        </div>
      </form>
      {command.tryNext.length > 0 && (
        <div className="mt-4 text-xs text-faint">
          <span className="font-semibold text-muted">Try next: </span>
          {command.tryNext.join(' · ')}
        </div>
      )}
    </Card>
  )
}

function ActorPicker({ actors, selected, onChange }: {
  actors: ActorInfo[]; selected: string | undefined; onChange: (id: string) => void
}) {
  return (
    <div className="mt-4">
      <div className="mb-2 flex items-center gap-2 text-sm font-medium"><KeyRound size={15} className="text-violet" /> Signed by</div>
      <div className="grid gap-2 sm:grid-cols-3">
        {actors.map((a) => (
          <button key={a.id} type="button" onClick={() => onChange(a.id)}
            className={clsx('rounded-xl border px-3 py-2.5 text-left transition',
              selected === a.id ? 'border-violet/50 bg-violet/5 shadow-[0_0_0_1px_rgba(109,40,217,0.15)]' : 'border-line hover:border-violet/30')}>
            <div className="flex items-center gap-2">
              <span className={clsx('grid h-7 w-7 place-items-center rounded-full text-[11px] font-bold',
                selected === a.id ? 'bg-violet text-white' : 'bg-ink/5 text-muted')}>
                {a.label.split(' ').map((w) => w[0]).join('').slice(0, 2)}
              </span>
              <div className="min-w-0">
                <div className="truncate text-sm font-medium">{a.label}</div>
                <div className="truncate text-[11px] text-faint">{a.role} · {a.organization}</div>
              </div>
            </div>
            <Mono className="mt-1.5 block truncate text-[10px]" >key {a.keyId} · {a.publicKey.slice(0, 10)}…</Mono>
          </button>
        ))}
      </div>
    </div>
  )
}

export function FieldInput({ field, value, onChange, members }: {
  field: Field; value: string; onChange: (v: string) => void; members: MemberInfo[]
}) {
  const base = 'w-full rounded-xl border border-line bg-surface px-3 py-2 text-sm text-ink outline-none transition placeholder:text-faint focus:border-accent/60 focus:ring-2 focus:ring-accent/15'
  const isMemberValue = members.some((m) => m.memberKey === value)
  const [custom, setCustom] = useState(false)
  return (
    <label className="block">
      <span className="mb-1.5 flex items-baseline justify-between text-sm font-medium">
        {field.label}{!field.required && <span className="text-[11px] font-normal text-faint">optional</span>}
      </span>
      {field.type === 'textarea' && (
        <textarea className={clsx(base, 'min-h-[88px] resize-y')} value={value} maxLength={field.maxLength ?? undefined}
          onChange={(e) => onChange(e.target.value)} />
      )}
      {field.type === 'select' && (
        <select className={base} value={value} onChange={(e) => onChange(e.target.value)}>
          {field.options.map((o) => <option key={o.value} value={o.value} className="bg-surface">{o.label}</option>)}
        </select>
      )}
      {field.type === 'member' && !custom && (value === '' || isMemberValue) ? (
        <select className={base} value={value} onChange={(e) => e.target.value === '__custom' ? setCustom(true) : onChange(e.target.value)}>
          <option value="" className="bg-surface">Choose…</option>
          {members.filter((m) => m.memberKey).map((m) => (
            <option key={m.nodeId} value={m.memberKey!} className="bg-surface">{m.label} · {m.shortKey}</option>
          ))}
          <option value="__custom" className="bg-surface">Other account name…</option>
        </select>
      ) : null}
      {(field.type === 'text' || field.type === 'number' || (field.type === 'member' && (custom || (value !== '' && !isMemberValue)))) && (
        <input className={clsx(base, field.type !== 'text' && 'font-mono')} value={value}
          inputMode={field.type === 'number' ? 'numeric' : undefined} maxLength={field.maxLength ?? undefined}
          onChange={(e) => onChange(e.target.value)} />
      )}
      {field.help && <span className="mt-1 block text-[11px] text-faint">{field.help}</span>}
    </label>
  )
}

function Lookups({ spec }: { spec: ChainSpec }) {
  const { readNode, members } = useDemo()
  const [lookupId, setLookupId] = useState(spec.lookups[0]?.id)
  const lookup = spec.lookups.find((l) => l.id === lookupId) ?? spec.lookups[0]
  const [values, setValues] = useState<Record<string, string>>({})
  const [result, setResult] = useState<LookupResult | null>(null)
  const run = useMutation({
    mutationFn: () => api.lookup(spec.id, lookup.id, values, readNode),
    onSuccess: setResult,
  })
  return (
    <Card className="p-5">
      {spec.lookups.length > 1 && (
        <div className="mb-3 flex gap-2">
          {spec.lookups.map((l) => (
            <button key={l.id} onClick={() => { setLookupId(l.id); setResult(null) }}
              className={clsx('rounded-full border px-3 py-1 text-sm', l.id === lookup.id ? 'border-accent/60 text-accent' : 'border-line text-muted')}>{l.title}</button>
          ))}
        </div>
      )}
      <h3 className="text-lg font-semibold">{lookup.title}</h3>
      <p className="text-sm text-muted">{lookup.summary} — read from the committed state of the selected node.</p>
      <form className="mt-4 space-y-3" onSubmit={(e) => { e.preventDefault(); run.mutate() }}>
        {lookup.fields.map((field) => (
          <FieldInput key={field.name} field={field} value={values[field.name] ?? ''} members={members}
            onChange={(v) => setValues((prev) => ({ ...prev, [field.name]: v }))} />
        ))}
        <Button type="submit" variant="ghost" disabled={run.isPending}>{run.isPending ? <Spinner /> : <Search size={15} />} Look up</Button>
      </form>
      {run.isError && <div className="mt-3 text-sm text-rose">{(run.error as Error).message}</div>}
      {result && (
        <div className="rise mt-5 space-y-3 border-t border-line/70 pt-4">
          <div className="font-semibold">{result.headline}</div>
          <FactsGrid facts={result.facts} />
          {result.proofKeyHex && <ProofButton chainId={spec.id} keyHex={result.proofKeyHex} />}
        </div>
      )}
    </Card>
  )
}

function ActivityList({ chainId }: { chainId: string }) {
  const { readNode } = useDemo()
  const activity = useQuery({
    queryKey: ['activity', chainId, readNode], queryFn: () => api.activity(chainId, readNode), refetchInterval: 6_000,
  })
  return (
    <Card className="p-5">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold">Recent activity</h3>
        <Badge tone="accent"><LiveDot /> live</Badge>
      </div>
      <p className="text-sm text-muted">The latest finalized messages on this chain, from every visitor and member.</p>
      <div className="mt-4 divide-y divide-line/60">
        {activity.isLoading && <div className="py-3 text-sm text-muted"><Spinner /> Loading…</div>}
        {activity.data?.length === 0 && <div className="py-3 text-sm text-faint">No messages yet — be the first.</div>}
        {activity.data?.map((item) => (
          <div key={item.messageId} className="flex items-start gap-3 py-2.5">
            <span className="mt-0.5 rounded-lg bg-ink/5 px-2 py-0.5 font-mono text-[11px] text-muted">#{item.height}</span>
            <div className="min-w-0 flex-1">
              <div className="truncate text-sm text-ink">{item.summary ?? item.topic}</div>
              <div className="text-[11px] text-faint">{item.senderName}{item.timestamp ? ` · ${new Date(Number(item.timestamp) || item.timestamp).toLocaleString()}` : ''}</div>
            </div>
          </div>
        ))}
      </div>
    </Card>
  )
}
