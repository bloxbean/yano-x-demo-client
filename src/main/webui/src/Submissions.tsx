import { useQuery } from '@tanstack/react-query'
import { Anchor, CheckCircle2, CircleDashed, Clock3, PenLine, Send, XCircle } from 'lucide-react'
import clsx from 'clsx'
import { ApiError, api, explorerLink, quorumText, short, type Anchor as AnchorInfo, type Stage, type Submission } from './api'
import { useDemo } from './App'
import { FactsGrid, ProofButton } from './Verify'
import { Badge, Card, LiveDot, Mono, STATUS_STYLE, Spinner } from './ui'

const TERMINAL: Stage[] = ['EVALUATED', 'REFUSED', 'FAILED']

export function SubmissionList({ chainId, anchor }: { chainId: string; anchor: AnchorInfo | null }) {
  const { config, submissions } = useDemo()
  const mine = submissions.filter((s) => s.chainId === chainId)
  return (
    <div className="space-y-3 lg:sticky lg:top-20 lg:self-start">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">Your submissions</h2>
        <span className="text-xs text-faint">this browser tab</span>
      </div>
      {mine.length === 0 && (
        <Card className="border-dashed p-6 text-center">
          <Send className="mx-auto text-faint" size={22} />
          <div className="mt-2 text-sm font-medium">Nothing submitted yet</div>
          <p className="mt-1 text-xs text-muted">
            Submit something on the left. You will see it get signed, ordered into a block, finalized by {quorumText(config)} members,
            evaluated against the chain's rules, proven, and anchored on Cardano.
          </p>
        </Card>
      )}
      {mine.map((s) => <SubmissionCard key={s.id} id={s.id} anchor={anchor} />)}
    </div>
  )
}

const STEPS: { stage: Stage; label: string }[] = [
  { stage: 'SUBMITTING', label: 'Signed' },
  { stage: 'ADMITTED', label: 'Admitted' },
  { stage: 'FINALIZED', label: 'Finalized' },
  { stage: 'EVALUATED', label: 'Outcome' },
]

function stepIndex(stage: Stage) {
  return { SUBMITTING: 0, ADMITTED: 1, FINALIZED: 2, EVALUATED: 3, REFUSED: 1, FAILED: -1 }[stage]
}

function SubmissionCard({ id, anchor }: { id: string; anchor: AnchorInfo | null }) {
  const { config } = useDemo()
  const query = useQuery({
    queryKey: ['submission', id], queryFn: () => api.submission(id),
    refetchInterval: (q) => (q.state.data && TERMINAL.includes(q.state.data.stage) ? false : 1_200),
    refetchIntervalInBackground: true,
    retry: (count, error) => !(error instanceof ApiError && error.status === 404) && count < 3,
  })
  const s = query.data
  if (query.isError) {
    return <Card className="p-4 text-sm text-faint">This submission is no longer tracked by the server.</Card>
  }
  if (!s) {
    return <Card className="p-4"><Spinner /></Card>
  }
  const current = stepIndex(s.stage)
  const style = s.outcome ? STATUS_STYLE[s.outcome.status] : null
  const anchored = s.height != null && anchor?.anchoredHeight != null && anchor.anchoredHeight >= s.height
  const txLink = explorerLink(config?.explorerTxUrl, anchor?.tx)

  return (
    <Card className={clsx('rise overflow-hidden', style?.ring)}>
      <div className="flex items-center justify-between gap-3 border-b border-line/70 px-4 py-3">
        <div className="min-w-0">
          <div className="truncate text-sm font-semibold">{s.summary}</div>
          <div className="text-[11px] text-faint">as {s.actingAs}{s.actor ? ` · ${s.actor}` : ''} · {s.topic}</div>
        </div>
        {style ? <Badge tone={style.tone}>{style.label}</Badge>
          : s.stage === 'FAILED' ? <Badge tone="rose">Failed</Badge>
            : <Badge><LiveDot tone="amber" /> in flight</Badge>}
      </div>

      <div className="flex items-center gap-1 px-4 pt-3">
        {STEPS.map((step, i) => {
          const done = current > i || (current === i && TERMINAL.includes(s.stage) && s.stage !== 'FAILED')
          const active = current === i && !TERMINAL.includes(s.stage)
          const refusedHere = s.stage === 'REFUSED' && i === 1
          return (
            <div key={step.stage} className="flex flex-1 items-center gap-1">
              <div className={clsx('flex items-center gap-1 text-[11px] font-medium',
                refusedHere ? 'text-rose' : done ? 'text-accent' : active ? 'text-ink' : 'text-faint')}>
                {refusedHere ? <XCircle size={13} /> : done ? <CheckCircle2 size={13} /> : active ? <Spinner className="h-3 w-3" /> : <CircleDashed size={13} />}
                {step.label}
              </div>
              {i < STEPS.length - 1 && <div className={clsx('h-px flex-1', done ? 'bg-accent/50' : 'bg-line')} />}
            </div>
          )
        })}
      </div>

      <div className="space-y-3 px-4 py-3">
        {s.height != null && (
          <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted">
            <span>block <b className="font-mono text-ink">#{s.height}</b></span>
            <span>position <b className="font-mono text-ink">{s.index}</b></span>
            {s.messageId && <span title={s.messageId}>message <Mono>{short(s.messageId)}</Mono></span>}
          </div>
        )}
        {s.outcome && (
          <div className="space-y-2">
            <div className="font-semibold">{s.outcome.headline}</div>
            <p className="text-sm text-muted">{s.outcome.detail}</p>
            {s.outcome.facts.length > 0 && <FactsGrid facts={s.outcome.facts} />}
          </div>
        )}
        {s.error && s.stage === 'FAILED' && <div className="text-sm text-rose">{s.error}</div>}

        {s.stage === 'EVALUATED' && (
          <div className="flex flex-wrap items-center gap-3 border-t border-line/70 pt-3">
            {s.outcome?.proofKeyHex && <ProofButton chainId={s.chainId} keyHex={s.outcome.proofKeyHex} />}
            <span className={clsx('inline-flex items-center gap-1.5 text-xs', anchored ? 'text-lime' : 'text-muted')}
              title="The cluster periodically writes its state root to a Cardano script address (every 30 blocks or 60 minutes).">
              {anchored ? <Anchor size={13} /> : <Clock3 size={13} />}
              {anchored ? <>Anchored on Cardano{txLink && <> · <a className="underline decoration-dotted hover:text-accent" href={txLink} target="_blank" rel="noreferrer">view tx</a></>}</>
                : 'Waiting for the next Cardano anchor'}
            </span>
          </div>
        )}
        <details className="text-[11px] text-faint">
          <summary className="cursor-pointer select-none hover:text-muted"><PenLine size={11} className="mr-1 inline" />Message details</summary>
          <div className="mt-2 space-y-1">
            {s.events.map((e) => <div key={e.at + e.stage}>{new Date(e.at).toLocaleTimeString()} · {e.text}</div>)}
            <div className="break-all font-mono">body {s.bodyHex}</div>
          </div>
        </details>
      </div>
    </Card>
  )
}

export type { Submission }
