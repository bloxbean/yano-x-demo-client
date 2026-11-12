// On Cardano: validators that act on facts proven against a chain's script-anchored state root.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Anchor, BadgeCheck, Coins, ExternalLink, Gift, KeyRound, Lock, PackageCheck, RefreshCw, Search, ShieldCheck, Sparkles, Truck, Undo2, Wallet } from 'lucide-react'
import clsx from 'clsx'
import { useEffect, useState, type ReactNode } from 'react'
import {
  cardano, explorerLink, operatorToken, short, type CardanoChain, type CardanoJob, type CardanoOverview,
  type Escrow, type ReleaseLookup,
} from './api'
import { useDemo } from './App'
import { Badge, Button, Card, LiveDot, Mono, Spinner, go } from './ui'

export const CARDANO_CHAINS: Record<string, CardanoChain['useCase']> = {
  'document-review-chain': 'certificates', 'orders-chain': 'escrow', 'balances-chain': 'rewards',
}

export function useCardano() {
  return useQuery({ queryKey: ['cardano'], queryFn: cardano.overview, refetchInterval: 15_000, retry: 1 })
}

function TxLink({ tx, label }: { tx?: string | null; label?: string }) {
  const { config } = useDemo()
  const href = explorerLink(config?.explorerTxUrl, tx)
  if (!tx) return null
  return href
    ? <a href={href} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 font-mono text-[12px] text-accent hover:underline">{label ?? short(tx, 8, 6)} <ExternalLink size={11} /></a>
    : <Mono>{short(tx, 8, 6)}</Mono>
}

/** Jobs this page started, with their live step logs. */
function useJobs() {
  const [ids, setIds] = useState<string[]>([])
  const qc = useQueryClient()
  const jobs = useQuery({
    queryKey: ['cardano-jobs', ids], enabled: ids.length > 0, refetchInterval: 2_500,
    queryFn: async () => Promise.all(ids.map((id) => cardano.job(id).catch(() => null))),
  })
  const list = (jobs.data ?? []).filter((j): j is CardanoJob => j != null)
  const busy = list.some((j) => j.state === 'queued' || j.state === 'running')
  useEffect(() => {
    if (!busy && list.length) qc.invalidateQueries({ predicate: (q) => q.queryKey[0] !== 'cardano-jobs' })
  }, [busy, list.length, qc])
  return { list, busy, track: (id: string) => setIds((prev) => [id, ...prev.filter((p) => p !== id)].slice(0, 6)) }
}

function JobLog({ jobs }: { jobs: CardanoJob[] }) {
  if (!jobs.length) return null
  return (
    <div className="space-y-2">
      {jobs.map((j) => (
        <div key={j.id} className={clsx('rounded-xl border px-3 py-2.5', j.state === 'failed' ? 'border-rose/30 bg-rose/5'
          : j.state === 'done' ? 'border-accent/30 bg-accent/5' : 'border-amber/30 bg-amber/5')}>
          <div className="flex items-center justify-between gap-2 text-sm font-medium">
            <span>{j.title}</span>
            <Badge tone={j.state === 'failed' ? 'rose' : j.state === 'done' ? 'accent' : 'amber'}>
              {(j.state === 'running' || j.state === 'queued') && <LiveDot tone="amber" />}{j.state}
            </Badge>
          </div>
          <pre className="mt-1.5 whitespace-pre-wrap font-mono text-[11.5px] leading-relaxed text-muted">{j.log.join('\n')}</pre>
        </div>
      ))}
    </div>
  )
}

function Explain({ fact, validator, nullifier }: { fact: ReactNode; validator: ReactNode; nullifier: ReactNode }) {
  return (
    <div className="grid gap-3 sm:grid-cols-3">
      {[['The fact', fact], ['The validator', validator], ['The nullifier', nullifier]].map(([title, body]) => (
        <div key={title as string} className="rounded-xl border-l-2 border-lime/60 bg-surface-2 px-3 py-2">
          <div className="text-xs font-semibold uppercase tracking-wider text-lime">{title}</div>
          <div className="mt-1 text-[13px] leading-relaxed text-muted">{body}</div>
        </div>
      ))}
    </div>
  )
}

function Disabled({ overview }: { overview?: CardanoOverview }) {
  return (
    <Card className="p-5 text-sm text-muted">
      <div className="flex items-center gap-2 font-semibold text-ink"><Anchor size={16} className="text-lime" /> On Cardano is off</div>
      <p className="mt-2">{overview?.reason ?? 'Loading…'}</p>
    </Card>
  )
}

/** The "On Cardano" tab of a chain workspace. */
export function CardanoTab({ chainId }: { chainId: string }) {
  const overview = useCardano()
  const jobs = useJobs()
  if (!overview.data?.enabled) return <Disabled overview={overview.data} />
  const chain = overview.data.chains?.find((c) => c.chainId === chainId)
  const useCase = CARDANO_CHAINS[chainId]
  return (
    <div className="space-y-4">
      {chain && <AnchorStrip chain={chain} />}
      {useCase === 'certificates' && <Certificates overview={overview.data} track={jobs.track} />}
      {useCase === 'escrow' && <Escrows track={jobs.track} />}
      {useCase === 'rewards' && <Rewards overview={overview.data} track={jobs.track} />}
      <JobLog jobs={jobs.list} />
      <button onClick={() => go('/cardano')} className="text-xs text-muted hover:text-accent">Anchor &amp; mint page →</button>
    </div>
  )
}

function AnchorStrip({ chain }: { chain: CardanoChain }) {
  const a = chain.anchor
  return (
    <Card className="flex flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3 text-sm">
      <span className="flex items-center gap-2 font-semibold"><Anchor size={15} className="text-lime" /> Anchored on Cardano at <span className="font-mono">{a?.height ?? '—'}</span></span>
      <span className="text-muted">app tip <span className="font-mono text-ink">{chain.tip}</span></span>
      {chain.blocksToAnchor > 0
        ? <span className="text-amber">{chain.blocksToAnchor} blocks to the next anchor (or ≤ 60 min)</span>
        : <span className="text-lime">due now</span>}
      {a && <span className="text-xs text-faint">co-signed by {a.threshold} of {a.members} · <TxLink tx={a.utxoTx} label="anchor UTxO" /></span>}
    </Card>
  )
}

// ------------------------------------------------------------------ 1 · certified release

function Certificates({ overview, track }: { overview: CardanoOverview; track: (id: string) => void }) {
  const [proposal, setProposal] = useState('')
  const [found, setFound] = useState<ReleaseLookup | null>(null)
  const minted = useQuery({ queryKey: ['cardano-certs'], queryFn: cardano.certificates, refetchInterval: 20_000 })
  const look = useMutation({ mutationFn: () => cardano.lookup(proposal.trim()), onSuccess: setFound })
  const mint = useMutation({ mutationFn: (p: string) => cardano.mint(p), onSuccess: (r) => track(r.job) })
  return (
    <>
      <Card className="space-y-4 p-5">
        <div className="flex items-center gap-2 text-lg font-semibold"><BadgeCheck size={18} className="text-lime" /> Certified release NFT</div>
        <Explain
          fact={<>A release receipt in this chain's state: two auditors from different organisations approved, and the release consumed that approval.</>}
          validator={<>Reads the chain's anchor UTxO as a reference input, rebuilds the receipt's exact bytes and proves them with <code>mpf.has</code> against the anchored root.</>}
          nullifier={<>The proposal id goes into the registry's claimed-set trie with <code>mpf.insert</code>: one NFT per release, ever.</>} />
        <form className="flex flex-wrap items-end gap-2" onSubmit={(e) => { e.preventDefault(); if (proposal.trim()) look.mutate() }}>
          <label className="min-w-[240px] flex-1 text-sm font-medium">Released proposal id
            <input value={proposal} onChange={(e) => setProposal(e.target.value)} placeholder="release-contract-17"
              className="mt-1.5 w-full rounded-xl border border-line bg-surface px-3 py-2 font-mono text-sm outline-none focus:border-accent/60" />
          </label>
          <Button type="submit" variant="ghost" disabled={look.isPending}>{look.isPending ? <Spinner /> : <Search size={15} />} Look up</Button>
        </form>
        <p className="text-xs text-faint">Propose, approve twice and release in <b>Try it</b>, then look the proposal up here.</p>
        {look.isError && <div className="text-sm text-rose">{(look.error as Error).message}</div>}
        {found && <LookupResult r={found} registryOpen={!!overview.certificates?.policy}
          onMint={() => mint.mutate(found.proposal)} pending={mint.isPending} />}
        {mint.isError && <div className="text-sm text-rose">{(mint.error as Error).message}</div>}
      </Card>
      {!!minted.data?.length && (
        <Card className="p-5">
          <h3 className="mb-2 font-semibold">Minted certificates</h3>
          <div className="divide-y divide-line/60 text-sm">
            {minted.data.slice().reverse().map((m) => (
              <div key={m.tx} className="flex flex-wrap items-center justify-between gap-2 py-2">
                <span className="font-mono">{m.proposal}</span>
                <span className="text-xs text-muted">proved at anchored height {m.anchoredHeight} · <TxLink tx={m.tx} /></span>
              </div>
            ))}
          </div>
        </Card>
      )}
    </>
  )
}

function LookupResult({ r, registryOpen, onMint, pending }: { r: ReleaseLookup; registryOpen: boolean; onMint: () => void; pending: boolean }) {
  if (!r.released) {
    return <Note tone="amber">No release receipt for <code>{r.proposal}</code> on this chain (tip {r.appTip}). Propose, approve twice and release it first.</Note>
  }
  return (
    <div className="space-y-2 rounded-xl border border-line bg-surface-2 p-3 text-sm">
      <div><Badge tone="accent">released</Badge> at height <b>{r.releasedHeight}</b> · document <code>{r.entity}</code> · policy <code>{r.policy}</code></div>
      {r.minted ? (
        <div className="flex flex-wrap items-center gap-2"><Badge tone="lime">minted</Badge> <TxLink tx={r.mintTx} />
          <Button variant="ghost" onClick={onMint} disabled={pending} className="py-1 text-xs">Mint again (the validator refuses)</Button></div>
      ) : !registryOpen ? (
        <Note tone="amber">The certificate registry is not open yet (Anchor &amp; mint page).</Note>
      ) : !r.anchored ? (
        <Note tone="amber">Not anchored yet: Cardano holds height {r.anchoredHeight}. Wait for the next anchor (Anchor &amp; mint page can speed it up).</Note>
      ) : (
        <div className="flex flex-wrap items-center gap-3"><span className="text-lime">Covered by the anchor at height {r.anchoredHeight}</span>
          <Button onClick={onMint} disabled={pending}>{pending ? <Spinner /> : <Sparkles size={15} />} Mint certificate NFT</Button></div>
      )}
    </div>
  )
}

function Note({ tone, children }: { tone: 'amber' | 'rose'; children: ReactNode }) {
  return <div className={clsx('rounded-xl border px-3 py-2 text-sm', tone === 'amber' ? 'border-amber/30 bg-amber/10 text-amber' : 'border-rose/30 bg-rose/10 text-rose')}>{children}</div>
}

// ------------------------------------------------------------------ 2 · delivery escrow

function Escrows({ track }: { track: (id: string) => void }) {
  const { members } = useDemo()
  const view = useQuery({ queryKey: ['cardano-escrow'], queryFn: cardano.escrows, refetchInterval: 20_000 })
  const [order, setOrder] = useState(() => 'A-' + (1000 + Math.floor(Date.now() / 1000) % 9000))
  const [carrier, setCarrier] = useState('')
  const act = useMutation({ mutationFn: (f: () => Promise<{ job: string }>) => f(), onSuccess: (r) => track(r.job) })
  const rows = (view.data?.escrows ?? []).slice().reverse()
  return (
    <Card className="space-y-4 p-5">
      <div className="flex items-center gap-2 text-lg font-semibold"><Truck size={18} className="text-lime" /> Delivery escrow</div>
      <Explain
        fact={<>A finalized <code>{'{"event":"delivered","order":…}'}</code> on this order log. Every message leaves a record: <code>sha256(namespace ‖ id) → [1, height, index, topic, sender]</code>.</>}
        validator={<>Rebuilds the message id from its envelope, checks it is a delivery of this order (from the carrier's member, if set), proves the record against the anchored root, and requires the supplier to be paid.</>}
        nullifier={<>The escrow UTxO itself: it can be spent only once. After two hours the buyer can take the ADA back.</>} />
      <form className="grid gap-3 sm:grid-cols-[1fr_1.4fr_auto] sm:items-end" onSubmit={(e) => { e.preventDefault(); act.mutate(() => cardano.createEscrow(order, carrier)) }}>
        <label className="text-sm font-medium">Order id
          <input value={order} onChange={(e) => setOrder(e.target.value)} className="mt-1.5 w-full rounded-xl border border-line bg-surface px-3 py-2 font-mono text-sm outline-none focus:border-accent/60" /></label>
        <label className="text-sm font-medium">Delivery confirmed by
          <select value={carrier} onChange={(e) => setCarrier(e.target.value)} className="mt-1.5 w-full rounded-xl border border-line bg-surface px-3 py-2 text-sm outline-none">
            <option value="">any member</option>
            {members.filter((m) => m.memberKey).map((m) => <option key={m.nodeId} value={m.memberKey!}>{m.label} only</option>)}
          </select></label>
        <Button type="submit" disabled={act.isPending}><Lock size={15} /> Lock {view.data?.escrowAda ?? 10} ADA</Button>
      </form>
      <p className="text-xs text-faint">Then record <code>delivered</code> for the order in <b>Try it</b> (through the carrier's member, if you chose one), or use the button below. Supplier: <Mono>{short(view.data?.supplier, 14, 8)}</Mono> · {((view.data?.supplierLovelace ?? 0) / 1e6).toFixed(2)} tADA</p>
      {act.isError && <div className="text-sm text-rose">{(act.error as Error).message}</div>}
      {rows.length > 0 && (
        <div className="divide-y divide-line/60 text-sm">
          {rows.map((e) => <EscrowRow key={e.createTx} e={e} act={(f) => act.mutate(f)} />)}
        </div>
      )}
    </Card>
  )
}

function EscrowRow({ e, act }: { e: Escrow; act: (f: () => Promise<{ job: string }>) => void }) {
  const { members } = useDemo()
  const carrier = members.find((m) => m.memberKey === e.carrier)
  const [now] = useState(() => Date.now())
  const node = carrier?.nodeId ?? 'node-1'
  return (
    <div className="flex flex-wrap items-center justify-between gap-2 py-2.5">
      <div>
        <div className="font-mono">{e.order} <span className="text-xs text-muted">· {e.ada} ADA{carrier ? ` · carrier ${carrier.label}` : ''}</span></div>
        <div className="text-xs text-faint">locked <TxLink tx={e.createTx} />{e.deliveryHeight ? ` · delivered at height ${e.deliveryHeight}` : ''}</div>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        {e.releaseTx ? <><Badge tone="lime">released</Badge> <TxLink tx={e.releaseTx} />
          <Button variant="ghost" className="py-1 text-xs" onClick={() => act(() => cardano.releaseEscrow(e.createTx))}>Release again (refused)</Button></>
          : e.refundTx ? <><Badge>refunded</Badge> <TxLink tx={e.refundTx} /></>
          : <>
            {!e.deliveryMessage && <Button variant="ghost" className="py-1 text-xs" onClick={() => act(() => cardano.deliver(e.order, node))}><PackageCheck size={13} /> Record delivery{carrier ? ` via ${carrier.label.split(' (')[0]}` : ''}</Button>}
            <Button className="py-1 text-xs" onClick={() => act(() => cardano.releaseEscrow(e.createTx))}><ShieldCheck size={13} /> Release to supplier</Button>
            {now > e.refundAfter && <Button variant="subtle" className="py-1 text-xs" onClick={() => act(() => cardano.refundEscrow(e.createTx))}><Undo2 size={13} /> Refund</Button>}
          </>}
      </div>
    </div>
  )
}

// ------------------------------------------------------------------ 3 · snapshot rewards

function Rewards({ overview, track }: { overview: CardanoOverview; track: (id: string) => void }) {
  const accounts = useQuery({ queryKey: ['cardano-rewards'], queryFn: cardano.accounts, refetchInterval: 30_000 })
  const claim = useMutation({ mutationFn: (key: string) => cardano.claim(key), onSuccess: (r) => track(r.job) })
  const season = overview.rewards
  return (
    <Card className="space-y-4 p-5">
      <div className="flex items-center gap-2 text-lg font-semibold"><Gift size={18} className="text-lime" /> Snapshot rewards</div>
      <Explain
        fact={<>Each member's balance in this chain's state: <code>b/&lt;member key&gt; → amount</code>, at the season's snapshot height.</>}
        validator={<>Opening a season copies the anchored root into the registry (checked against the anchor). A claim proves the balance against that frozen root and mints that many POINTS to the member's own key address.</>}
        nullifier={<>The account key, inserted into the season's claimed set: one claim per account. Anyone may pay the fee; only the member can spend the POINTS.</>} />
      {!season?.policy
        ? <Note tone="amber">No season is open yet (Anchor &amp; mint page).</Note>
        : <div className="text-sm text-muted">Season snapshot at height <b className="text-ink">{season.snapshotHeight}</b> · root <Mono>{short(season.snapshotRoot, 10, 6)}</Mono> · <TxLink tx={season.openTx} label="opened" /></div>}
      {claim.isError && <div className="text-sm text-rose">{(claim.error as Error).message}</div>}
      <div className="divide-y divide-line/60 text-sm">
        {(accounts.data ?? []).map((a) => (
          <div key={a.memberKey} className="flex flex-wrap items-center justify-between gap-2 py-2.5">
            <div>
              <div>{a.label} <span className="text-xs text-muted">· {a.snapshotBalance ?? '—'} at the snapshot</span></div>
              <Mono className="text-[11px]">POINTS go to {short(a.owner, 14, 8)}</Mono>
            </div>
            {season?.policy && (a.snapshotBalance ?? 0) > 0 && (a.claimed
              ? <span className="flex items-center gap-2"><Badge tone="lime">claimed</Badge> <TxLink tx={a.claimTx} />
                <Button variant="ghost" className="py-1 text-xs" onClick={() => claim.mutate(a.memberKey)}>Claim again (refused)</Button></span>
              : <Button className="py-1 text-xs" onClick={() => claim.mutate(a.memberKey)} disabled={claim.isPending}><Coins size={13} /> Claim {a.snapshotBalance} POINTS</Button>)}
          </div>
        ))}
      </div>
    </Card>
  )
}

// ------------------------------------------------------------------ Anchor & mint page

export function CardanoPage() {
  const overview = useCardano()
  const jobs = useJobs()
  const all = useQuery({ queryKey: ['cardano-all-jobs'], queryFn: cardano.jobs, refetchInterval: 4_000, enabled: !!overview.data?.enabled })
  const { members } = useDemo()
  const [token, setToken] = useState(operatorToken.get())
  const [member, setMember] = useState('')
  const [amount, setAmount] = useState('40')
  const op = useMutation({ mutationFn: (f: () => Promise<{ job: string }>) => f(), onSuccess: (r) => track(r.job) })
  const track = jobs.track
  const o = overview.data
  if (!o?.enabled) return <div className="py-8"><Disabled overview={o} /></div>
  const label = { certificates: 'Certified release NFT', escrow: 'Delivery escrow', rewards: 'Snapshot rewards' }
  return (
    <div className="space-y-6">
      <section className="rise rounded-3xl border border-line bg-surface p-6 sm:p-8">
        <div className="text-xs font-semibold uppercase tracking-[0.18em] text-lime">On Cardano</div>
        <h1 className="mt-2 text-3xl font-bold">Anchor &amp; mint</h1>
        <p className="mt-2 max-w-3xl text-sm text-muted">Validators on Cardano preprod read each chain's script anchor as a reference input, check an MPF proof
          against its root with <code>aiken-lang/merkle-patricia-forestry</code>, and spend a nullifier. New app-ledger facts become usable once the next anchor lands:
          every {o.anchorEveryBlocks} blocks, or at most every 60 minutes.</p>
        <div className="mt-4 flex flex-wrap items-center gap-4 text-sm">
          <span className="flex items-center gap-2"><Wallet size={15} className="text-accent" /> Demo wallet <Mono>{short(o.wallet, 14, 8)}</Mono> · <b>{((o.walletLovelace ?? 0) / 1e6).toFixed(2)} tADA</b></span>
          <span className="text-xs text-faint">pays every fee · at most {o.transactionsPerHour} transactions an hour</span>
        </div>
      </section>

      <div className="grid gap-4 lg:grid-cols-3">
        {o.chains?.map((c) => (
          <Card key={c.chainId} className="space-y-3 p-5">
            <div className="text-xs text-faint">{label[c.useCase]} · <code>{c.chainId}</code></div>
            <div className="flex items-center gap-2 text-2xl font-bold"><Anchor size={20} className="text-lime" /> {c.anchor?.height ?? '—'}</div>
            <div className="text-sm text-muted">app tip <b className="text-ink">{c.tip}</b> · {c.blocksToAnchor > 0 ? <span className="text-amber">{c.blocksToAnchor} blocks to the next anchor</span> : <span className="text-lime">due now</span>}</div>
            <div className="h-1.5 overflow-hidden rounded-full bg-ink/10"><div className="h-full bg-lime" style={{ width: `${Math.min(100, (1 - c.blocksToAnchor / (o.anchorEveryBlocks ?? 30)) * 100)}%` }} /></div>
            <div className="text-xs text-faint">root <Mono>{short(c.anchor?.stateRoot, 10, 6)}</Mono> · <TxLink tx={c.anchor?.utxoTx} label="anchor UTxO" />{c.anchorError ? ` · ${c.anchorError}` : ''}</div>
            <div className="flex flex-wrap gap-2">
              <Button variant="ghost" className="py-1.5 text-xs" onClick={() => op.mutate(() => cardano.populate(c.chainId))}><RefreshCw size={13} /> Populate until anchored</Button>
              {o.anchorNowAvailable && <Button variant="ghost" className="py-1.5 text-xs" onClick={() => op.mutate(() => cardano.anchorNow(c.chainId))}><Anchor size={13} /> Anchor now</Button>}
              <Button variant="subtle" className="py-1.5 text-xs" onClick={() => go(`/chain/${c.chainId}`)}>Open chain →</Button>
            </div>
          </Card>
        ))}
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card className="space-y-3 p-5">
          <h3 className="flex items-center gap-2 font-semibold"><KeyRound size={16} className="text-violet" /> Operator</h3>
          {o.operatorTokenRequired && (
            <label className="block text-sm">Operator token
              <input type="password" value={token} onChange={(e) => { setToken(e.target.value); operatorToken.set(e.target.value) }}
                className="mt-1.5 w-full rounded-xl border border-line bg-surface px-3 py-2 font-mono text-sm outline-none" /></label>
          )}
          <div className="flex flex-wrap gap-2">
            <Button variant="ghost" disabled={!!o.certificates?.policy} onClick={() => op.mutate(cardano.openCertificates)}><BadgeCheck size={14} /> {o.certificates?.policy ? 'Certificate registry open' : 'Open certificate registry'}</Button>
            <Button variant="ghost" disabled={!!o.rewards?.policy} onClick={() => op.mutate(cardano.openSeason)}><Gift size={14} /> {o.rewards?.policy ? `Season open at ${o.rewards.snapshotHeight}` : 'Open rewards season'}</Button>
          </div>
          <div className="flex flex-wrap items-end gap-2 border-t border-line/70 pt-3">
            <label className="text-sm">Give points to
              <select value={member} onChange={(e) => setMember(e.target.value)} className="mt-1 block rounded-xl border border-line bg-surface px-3 py-2 text-sm">
                <option value="">Choose…</option>
                {members.filter((m) => m.memberKey).map((m) => <option key={m.nodeId} value={m.memberKey!}>{m.label}</option>)}
              </select></label>
            <label className="text-sm">Amount<input value={amount} onChange={(e) => setAmount(e.target.value)} className="mt-1 block w-24 rounded-xl border border-line bg-surface px-3 py-2 font-mono text-sm" /></label>
            <Button variant="ghost" disabled={!member} onClick={() => op.mutate(() => cardano.points(member, Number(amount) || 10))}><Coins size={14} /> Mint points</Button>
          </div>
          {op.isError && <div className="text-sm text-rose">{(op.error as Error).message}</div>}
          <JobLog jobs={jobs.list} />
        </Card>
        <Card className="space-y-3 p-5">
          <h3 className="flex items-center gap-2 font-semibold"><LiveDot /> All activity</h3>
          <JobLog jobs={(all.data ?? []).filter((j) => !jobs.list.some((m) => m.id === j.id)).slice(0, 8)} />
          {!all.data?.length && <div className="text-sm text-faint">Nothing yet.</div>}
        </Card>
      </div>
    </div>
  )
}
