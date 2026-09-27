import { useMutation, useQuery } from '@tanstack/react-query'
import { Anchor, CheckCircle2, ExternalLink, FileCheck2, ShieldCheck, XCircle } from 'lucide-react'
import clsx from 'clsx'
import { api, explorerLink, short, type ChainLive, type Fact, type Proof } from './api'
import { useDemo } from './App'
import { Badge, Button, Card, LiveDot, Mono, Spinner } from './ui'

export function FactsGrid({ facts }: { facts: Fact[] }) {
  return (
    <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-1.5 rounded-xl border border-line/70 bg-surface-2 px-3 py-2.5 text-sm">
      {facts.map((fact, i) => (
        <div key={fact.label + i} className="contents">
          <dt className="text-xs text-faint" title={fact.hint ?? undefined}>{fact.label}</dt>
          <dd className={clsx('min-w-0 break-words', /^[0-9a-f]{40,}$/.test(fact.value) ? 'font-mono text-[12px] text-muted' : 'text-ink')}
            title={fact.hint ?? fact.value}>
            {/^[0-9a-f]{40,}$/.test(fact.value) ? short(fact.value, 16, 10) : fact.value}
          </dd>
        </div>
      ))}
    </dl>
  )
}

export function ProofButton({ chainId, keyHex }: { chainId: string; keyHex: string }) {
  const { readNode } = useDemo()
  const proof = useMutation({ mutationFn: () => api.proof(chainId, keyHex, readNode) })
  return (
    <div className="w-full space-y-3">
      {!proof.data && (
        <Button variant="ghost" onClick={() => proof.mutate()} disabled={proof.isPending} className="py-1.5 text-xs">
          {proof.isPending ? <Spinner /> : <FileCheck2 size={14} />} Show state proof
        </Button>
      )}
      {proof.isError && <div className="text-xs text-rose">{(proof.error as Error).message}</div>}
      {proof.data && <ProofView proof={proof.data} />}
    </div>
  )
}

function ProofView({ proof }: { proof: Proof }) {
  const { members } = useDemo()
  const signed = new Set(proof.signers.map((s) => s.memberKey))
  return (
    <div className="rise space-y-3 rounded-xl border border-accent/25 bg-mint/15 p-3">
      <div className="flex items-center gap-2 text-sm font-semibold text-accent-strong">
        <ShieldCheck size={16} /> Certified by {proof.signers.length} of {proof.memberCount} members
        <span className="font-normal text-muted">(threshold {proof.threshold})</span>
      </div>
      <div className="flex flex-wrap gap-1.5">
        {members.map((m) => {
          const ok = m.memberKey != null && signed.has(m.memberKey)
          return (
            <span key={m.nodeId} title={m.memberKey ?? ''}
              className={clsx('inline-flex items-center gap-1 rounded-lg border px-2 py-1 text-[11px]',
                ok ? 'border-accent/40 bg-surface text-accent-strong' : 'border-line bg-surface text-faint')}>
              {ok ? <CheckCircle2 size={12} /> : <XCircle size={12} />} {m.label}
            </span>
          )
        })}
      </div>
      <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1 text-xs">
        <dt className="text-faint">Presence</dt><dd><Badge tone={proof.presence === 'PRESENT' ? 'accent' : 'amber'}>{proof.presence}</Badge></dd>
        <dt className="text-faint">Block</dt><dd className="font-mono">#{proof.height} · {short(proof.blockHash, 12, 8)}</dd>
        <dt className="text-faint">State root</dt><dd className="font-mono" title={proof.stateRoot ?? ''}>{short(proof.stateRoot, 16, 10)}</dd>
        <dt className="text-faint">State key</dt><dd className="font-mono break-all">{short(proof.keyHex, 16, 10)}</dd>
        <dt className="text-faint">Merkle proof</dt><dd className="font-mono">{proof.proofWireHex ? `${proof.proofWireHex.length / 2} bytes (MPF, blake2b-256)` : '—'}</dd>
      </dl>
      <p className="text-[11px] text-muted">
        The value is proven against the block's state root, and the block is signed by a quorum of members — anyone can
        check this without trusting the node that served it.
      </p>
    </div>
  )
}

export function VerifyPanel({ chainId, live }: { chainId: string; live: ChainLive }) {
  const { config } = useDemo()
  const agreement = useQuery({ queryKey: ['agreement', chainId], queryFn: () => api.agreement(chainId), refetchInterval: 6_000 })
  const anchor = live.anchor
  const txLink = explorerLink(config?.explorerTxUrl, anchor?.tx)
  return (
    <div className="space-y-4">
      <Card className="p-5">
        <div className="flex items-center justify-between">
          <h3 className="text-lg font-semibold">All members agree</h3>
          {agreement.data && (agreement.data.agree
            ? <Badge tone="accent"><CheckCircle2 size={12} /> identical state</Badge>
            : <Badge tone="amber">checking…</Badge>)}
        </div>
        <p className="text-sm text-muted">
          Each member node is asked independently for block #{agreement.data?.comparedHeight ?? '…'}. Same height, same state
          root — that is replicated consensus.
        </p>
        <div className="mt-4 overflow-hidden rounded-xl border border-line">
          <table className="w-full text-sm">
            <thead className="bg-surface-2 text-left text-[11px] uppercase tracking-wider text-faint">
              <tr><th className="px-3 py-2">Member</th><th className="px-3 py-2">Tip</th><th className="px-3 py-2">State root</th><th className="px-3 py-2">Signatures</th></tr>
            </thead>
            <tbody className="divide-y divide-line/70">
              {agreement.isLoading && <tr><td className="px-3 py-3 text-muted" colSpan={4}><Spinner /> Asking every member…</td></tr>}
              {agreement.data?.members.map((m) => (
                <tr key={m.nodeId}>
                  <td className="px-3 py-2 font-medium">{m.label}</td>
                  <td className="px-3 py-2 font-mono text-xs">{m.reachable ? m.height : <span className="text-rose">unreachable</span>}</td>
                  <td className="px-3 py-2"><Mono>{short(m.stateRoot, 10, 6)}</Mono></td>
                  <td className="px-3 py-2 font-mono text-xs">{m.certSignatures ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <Card className="p-5">
        <div className="flex items-center justify-between">
          <h3 className="flex items-center gap-2 text-lg font-semibold"><Anchor size={18} className="text-lime" /> Anchored on Cardano</h3>
          {anchor?.enabled ? <Badge tone="lime"><LiveDot /> {anchor.mode ?? 'enabled'}</Badge> : <Badge>not anchored</Badge>}
        </div>
        <p className="text-sm text-muted">
          The members periodically write the chain's state root to a Cardano script address. After that, a block's
          content can be checked against Cardano alone.
        </p>
        {anchor?.enabled && (
          <dl className="mt-4 grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-2 text-sm">
            <dt className="text-faint">Anchored height</dt><dd className="font-mono">{anchor.anchoredHeight ?? '—'} <span className="text-faint">of {live.tipHeight}</span></dd>
            <dt className="text-faint">Blocks since anchor</dt><dd className="font-mono">{anchor.lagBlocks ?? '—'}</dd>
            <dt className="text-faint">Latest anchor tx</dt>
            <dd className="min-w-0">
              {txLink ? <a className="inline-flex items-center gap-1 font-mono text-xs text-accent hover:underline" href={txLink} target="_blank" rel="noreferrer">{short(anchor.tx, 14, 8)} <ExternalLink size={12} /></a> : '—'}
            </dd>
            <dt className="text-faint">Script address</dt><dd className="break-all font-mono text-xs text-muted">{anchor.scriptAddress ?? '—'}</dd>
          </dl>
        )}
      </Card>
    </div>
  )
}
