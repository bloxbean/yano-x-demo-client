import { useQuery } from '@tanstack/react-query'
import { Anchor, ArrowRight, Blocks, CheckCircle2, MousePointerClick, ShieldCheck, Users } from 'lucide-react'
import clsx from 'clsx'
import { api, memberWord, quorumText, type ChainCard, type ClientConfig } from './api'
import { useDemo } from './App'
import { Badge, Card, LiveDot, Spinner, accent, go } from './ui'

const steps = (config?: ClientConfig) => [
  { icon: MousePointerClick, title: 'Pick a ledger', text: 'Each app ledger runs its own state machine — logs, registries, approvals, balances, documents.' },
  { icon: Users, title: 'Act as a member', text: `${memberWord(config)} organisations run the cluster. Choose who you are; your message is signed by that member.` },
  { icon: ShieldCheck, title: 'Watch it finalize', text: `${quorumText(config)} members sign every block. See the outcome, a state proof, and the Cardano anchor.` },
]

export function Catalog() {
  const { readNode, config } = useDemo()
  const STEPS = steps(config)
  const chains = useQuery({
    queryKey: ['chains', readNode], queryFn: () => api.chains(readNode), refetchInterval: 8_000, enabled: !!readNode,
  })
  const supported = chains.data?.filter((c) => c.supported) ?? []
  const others = chains.data?.filter((c) => !c.supported) ?? []
  const groups = supported.reduce<Record<string, ChainCard[]>>((acc, card) => {
    (acc[card.spec.category] ??= []).push(card)
    return acc
  }, {})

  return (
    <div className="space-y-10">
      <section className="rise grid gap-8 pt-4 lg:grid-cols-[1.2fr_1fr] lg:items-end">
        <div>
          <Badge tone="accent"><LiveDot /> Live on a {config?.memberCount ?? 5}-member cluster</Badge>
          <h1 className="mt-4 text-4xl font-bold leading-[1.05] sm:text-5xl">
            Try Yano X app ledgers, <span className="bg-gradient-to-r from-accent to-lime bg-clip-text text-transparent">for real.</span>
          </h1>
          <p className="mt-4 max-w-xl text-base text-muted">
            Submit to real app ledgers, switch between the organisations that run them, and follow each message from
            submission to a finalized, provable result anchored on Cardano. No wallet, no setup.
          </p>
        </div>
        <div className="grid gap-3 sm:grid-cols-3 lg:grid-cols-1">
          {STEPS.map((step, i) => (
            <div key={step.title} className="flex gap-3 rounded-2xl border border-line/70 bg-surface p-3.5">
              <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-accent/10 text-accent">
                <step.icon size={18} />
              </span>
              <div>
                <div className="text-sm font-semibold"><span className="text-faint">{i + 1}.</span> {step.title}</div>
                <div className="text-xs text-muted">{step.text}</div>
              </div>
            </div>
          ))}
        </div>
      </section>

      {chains.isLoading && <div className="flex items-center gap-2 text-muted"><Spinner /> Loading chains…</div>}
      {chains.isError && <Card className="p-4 text-rose">Cannot reach the cluster right now. {(chains.error as Error).message}</Card>}

      {Object.entries(groups).map(([category, cards]) => (
        <section key={category} className="space-y-4">
          <h2 className="text-sm font-semibold uppercase tracking-[0.18em] text-faint">{category}</h2>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {cards.map((card, i) => <ChainTile key={card.spec.id} card={card} delay={i * 40} />)}
          </div>
        </section>
      ))}

      {others.length > 0 && (
        <section className="space-y-4">
          <h2 className="text-sm font-semibold uppercase tracking-[0.18em] text-faint">Also running on this cluster</h2>
          <div className="flex flex-wrap gap-2">
            {others.map((card) => (
              <span key={card.spec.id} className="inline-flex items-center gap-2 rounded-xl border border-line bg-surface px-3 py-1.5 text-xs text-muted">
                <Blocks size={12} /> {card.spec.id}
                <span className="text-faint">h {card.live.tipHeight}</span>
              </span>
            ))}
          </div>
          <p className="text-xs text-faint">Interactive demos for these chains are on the way.</p>
        </section>
      )}
    </div>
  )
}

function ChainTile({ card, delay }: { card: ChainCard; delay: number }) {
  const a = accent(card.spec.accent)
  const anchor = card.live.anchor
  return (
    <button onClick={() => go(`/chain/${card.spec.id}`)} style={{ animationDelay: `${delay}ms` }}
      className={clsx('rise group relative overflow-hidden rounded-2xl border border-line bg-surface p-5 text-left transition',
        'hover:-translate-y-0.5 hover:border-accent/40 hover:shadow-[0_18px_40px_-22px_rgba(11,17,21,0.35)]')}>
      <div className={clsx('pointer-events-none absolute -right-16 -top-16 h-40 w-40 rounded-full bg-gradient-to-br to-transparent blur-2xl', a.glow)} />
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className={clsx('text-[11px] font-semibold uppercase tracking-[0.16em]', a.text)}>{card.spec.machine}</div>
          <h3 className="mt-1 text-xl font-semibold">{card.spec.title}</h3>
        </div>
        <ArrowRight size={18} className="mt-1 text-faint transition group-hover:translate-x-0.5 group-hover:text-accent" />
      </div>
      <p className="mt-2 text-sm text-muted">{card.spec.tagline}</p>
      <div className="mt-4 flex flex-wrap gap-1.5">
        {card.spec.tags.map((tag) => <Badge key={tag}>{tag}</Badge>)}
      </div>
      <div className="mt-5 flex items-center gap-4 border-t border-line/70 pt-3 text-xs text-muted">
        <span className="flex items-center gap-1.5">
          {card.live.running ? <LiveDot /> : <span className="h-2 w-2 rounded-full bg-rose" />}
          height <b className="font-mono text-ink">{card.live.tipHeight}</b>
        </span>
        {anchor?.enabled && (
          <span className="flex items-center gap-1.5" title="Latest block height anchored on Cardano">
            <Anchor size={12} className="text-lime" /> anchored <b className="font-mono text-ink">{anchor.anchoredHeight ?? '—'}</b>
          </span>
        )}
        <span className="ml-auto flex items-center gap-1" title="Connected peers">
          <CheckCircle2 size={12} className="text-accent" /> {card.live.peersUp + 1}/{card.live.peers + 1}
        </span>
      </div>
    </button>
  )
}
