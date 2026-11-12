// Typed client for the demo backend (/api/demo). The browser never talks to Yano nodes directly.
import type { ConfigListing } from './chainConfig'

export type PersonaKind = 'MEMBER' | 'ACTOR' | 'NONE'
export type Status = 'APPLIED' | 'NO_EFFECT' | 'REJECTED' | 'RECORDED'
export type Stage = 'SUBMITTING' | 'ADMITTED' | 'FINALIZED' | 'EVALUATED' | 'REFUSED' | 'FAILED'

export interface NodeInfo { id: string; label: string; host: string; isDefault: boolean }
export interface ClientConfig {
  clusterName: string
  nodes: NodeInfo[]
  customNodeEnabled: boolean
  explorerTxUrl: string
  writesPerMinute: number
  /** Signatures that finalize a block, and the cluster's member count (demo.quorum.*). */
  threshold: number
  memberCount: number
}

const NUMBER_WORDS = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten']
/** "3 of 5", from the deployment's quorum (falls back to the pre3 showcase's 4 of 5 until the config loads). */
export const quorumText = (c?: ClientConfig) => `${c?.threshold ?? 4} of ${c?.memberCount ?? 5}`
/** "Five", "Three", … for prose. */
export const memberWord = (c?: ClientConfig) => {
  const n = c?.memberCount ?? 5
  const w = NUMBER_WORDS[n] ?? String(n)
  return w.charAt(0).toUpperCase() + w.slice(1)
}
export interface ActorInfo { id: string; label: string; organization: string; role: string; keyId: string; publicKey: string }
export interface MemberInfo { nodeId: string; label: string; memberKey: string | null; shortKey: string | null }

export interface Option { value: string; label: string }
export interface Field {
  name: string; label: string; type: 'text' | 'textarea' | 'number' | 'select' | 'member'
  required: boolean; maxLength: number | null; pattern: string | null; help: string | null; options: Option[]
}
export interface Command {
  id: string; title: string; summary: string; demonstrates: string; personaKind: PersonaKind
  actors: string[]; fields: Field[]; tryNext: string[]
}
export interface Lookup { id: string; title: string; summary: string; fields: Field[] }
export interface ChainSpec {
  id: string; title: string; tagline: string; story: string; machine: string; category: string
  tags: string[]; readOnly: boolean; accent: string; commands: Command[]; lookups: Lookup[]
}
export interface Anchor {
  enabled: boolean; mode: string | null; anchoredHeight: number | null; tx: string | null
  lagBlocks: number | null; scriptAddress: string | null
}
export interface ChainLive {
  chainId: string; tipHeight: number; stateRoot: string | null; running: boolean
  peersUp: number; peers: number; anchor: Anchor | null
}
export interface ChainCard { spec: ChainSpec; live: ChainLive; supported: boolean }

export interface Fact { label: string; value: string; hint: string | null }
export interface Outcome { status: Status; headline: string; detail: string; facts: Fact[]; proofKeyHex: string | null }
export interface SubmissionEvent { at: string; stage: Stage; text: string }
export interface Submission {
  id: string; chainId: string; commandId: string; summary: string; topic: string; bodyHex: string
  nodeId: string; actingAs: string; actor: string | null; stage: Stage; messageId: string | null
  height: number | null; index: number | null; outcome: Outcome | null; error: string | null
  events: SubmissionEvent[]
}
export interface LookupResult { headline: string; facts: Fact[]; proofKeyHex: string | null }
export interface MemberView {
  nodeId: string; label: string; reachable: boolean; height: number | null; stateRoot: string | null
  certSignatures: number | null
}
export interface Agreement { chainId: string; comparedHeight: number; agree: boolean; members: MemberView[] }
export interface ActivityItem {
  height: number; timestamp: string | null; messageId: string; topic: string; sender: string
  senderName: string; summary: string | null
}
export interface Signer { memberKey: string; name: string }
export interface Proof {
  keyHex: string; presence: string; valueHex: string | null; height: number; stateRoot: string | null
  blockHash: string | null; proofWireHex: string | null; signers: Signer[]; threshold: number; memberCount: number
}

/** Configuration parts of a chain's /status on one node (see ClusterService.liveConfig). */
export interface LiveConfig {
  node: string; chainId: string; stateMachine?: string; members?: number; threshold?: number; membershipMode?: string
  membershipEpochFromHeight?: number; membershipActiveMembers?: number; membershipActiveThreshold?: number
  configuredBlockIntervalMs?: number; tipHeight?: number
  consensusProfile?: { maxMessageBytes?: number; maxBlockMessages?: number; maxBlockBytes?: number; l1StabilityDepth?: number; effectsEnabled?: boolean; digest?: string }
  sequencer?: { mode?: string; proposer?: string; consensusProtocol?: string; roundTimeoutMs?: number }
  stateCommitment?: { profile?: string; backend?: string; verificationTarget?: string; genesisId?: string }
  anchor?: { enabled?: boolean; mode?: string; scriptAddress?: string; bootstrapped?: boolean }
  effects?: { enabled?: boolean; defaultGate?: string; maxPerBlock?: number; executorOnThisNode?: boolean }
  capabilityManifest?: unknown
  stateMachineStatus?: unknown
}

export class ApiError extends Error {
  readonly status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

const SESSION_KEY = 'yano-demo-session'
export function sessionTag(): string {
  try {
    let tag = localStorage.getItem(SESSION_KEY)
    if (!tag || !/^[a-z0-9]{4,8}$/.test(tag)) {
      tag = Math.random().toString(36).slice(2, 6).padEnd(4, 'x')
      localStorage.setItem(SESSION_KEY, tag)
    }
    return tag
  } catch {
    return 'anon'
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api/demo${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', 'X-Demo-Session': sessionTag(), ...(init?.headers ?? {}) },
  })
  const text = await response.text()
  const body = text ? JSON.parse(text) : {}
  if (!response.ok) {
    throw new ApiError(response.status, body?.error ?? `Request failed (${response.status})`)
  }
  return body as T
}

const nodeQuery = (node?: string) => (node ? `?node=${encodeURIComponent(node)}` : '')

export const api = {
  configFiles: () => request<ConfigListing>('/config-files'),
  liveConfig: (id: string, node?: string) => request<LiveConfig>(`/chains/${id}/live-config${nodeQuery(node)}`),
  config: () => request<ClientConfig>('/config'),
  members: () => request<MemberInfo[]>('/members'),
  actors: () => request<ActorInfo[]>('/actors'),
  chains: (node?: string) => request<ChainCard[]>(`/chains${nodeQuery(node)}`),
  chain: (id: string, node?: string) => request<ChainCard>(`/chains/${id}${nodeQuery(node)}`),
  agreement: (id: string) => request<Agreement>(`/chains/${id}/agreement`),
  activity: (id: string, node?: string) =>
    request<ActivityItem[]>(`/chains/${id}/activity${node ? `?node=${node}&limit=20` : '?limit=20'}`),
  proof: (id: string, keyHex: string, node?: string) => request<Proof>(`/chains/${id}/proof/${keyHex}${nodeQuery(node)}`),
  mock: (id: string, command: string, node?: string) =>
    request<Record<string, string>>(`/chains/${id}/commands/${command}/mock${nodeQuery(node)}`),
  submit: (id: string, command: string, node: string, fields: Record<string, string>, actor?: string) =>
    request<Submission>(`/chains/${id}/commands/${command}`, {
      method: 'POST', body: JSON.stringify({ node, actor, fields }),
    }),
  submission: (id: string) => request<Submission>(`/submissions/${id}`),
  lookup: (id: string, lookup: string, fields: Record<string, string>, node?: string) =>
    request<LookupResult>(`/chains/${id}/lookups/${lookup}${nodeQuery(node)}`, {
      method: 'POST', body: JSON.stringify({ fields }),
    }),
}

// ------------------------------------------------------------------ On Cardano

export interface CardanoAnchor {
  chainId: string; height: number; stateRoot: string; utxoTx: string; utxoIndex: number
  scriptAddress: string; threadPolicy: string; threshold: number; members: number
}
export interface CardanoChain {
  chainId: string; useCase: 'certificates' | 'escrow' | 'rewards'; tip: number; lastAnchored: number
  blocksToAnchor: number; anchor?: CardanoAnchor; anchorError?: string
}
export interface CardanoOverview {
  enabled: boolean; reason?: string; wallet?: string; walletLovelace?: number; operatorTokenRequired?: boolean
  anchorNowAvailable?: boolean; anchorEveryBlocks?: number; transactionsPerHour?: number; chains?: CardanoChain[]
  certificates?: { policy?: string; address?: string; openTx?: string }
  rewards?: { policy?: string; address?: string; openTx?: string; snapshotHeight?: number; snapshotRoot?: string }
}
export interface CardanoJob { id: string; title: string; state: 'queued' | 'running' | 'done' | 'failed'; log: string[]; error?: string; result?: unknown }
export interface ReleaseLookup {
  proposal: string; released: boolean; appTip: number; anchoredHeight: number; entity?: string; policy?: string
  releasedHeight?: number; anchored?: boolean; registryOpen: boolean; minted: boolean; mintTx?: string; policyId?: string
}
export interface CertificateMint { proposal: string; tx: string; anchoredHeight: number }
export interface Escrow {
  order: string; ada: number; carrier: string; createTx: string; refundAfter: number; open?: boolean
  deliveryMessage?: string; deliveryHeight?: number; releaseTx?: string; refundTx?: string
}
export interface EscrowView { supplier: string; supplierLovelace: number; escrowAda: number; escrows: Escrow[] }
export interface RewardAccount {
  nodeId: string; label: string; memberKey: string; owner: string; snapshotBalance?: number; claimed?: boolean; claimTx?: string
}

const OPERATOR_KEY = 'yano-demo-operator-token'
export const operatorToken = {
  get: () => { try { return localStorage.getItem(OPERATOR_KEY) ?? '' } catch { return '' } },
  set: (t: string) => { try { localStorage.setItem(OPERATOR_KEY, t) } catch { /* private mode */ } },
}
const post = <T,>(path: string, body: unknown, operator = false) => request<T>(`/cardano${path}`, {
  method: 'POST', body: JSON.stringify(body ?? {}),
  headers: operator ? { 'X-Demo-Operator': operatorToken.get() } : {},
})

export const cardano = {
  overview: () => request<CardanoOverview>('/cardano/overview'),
  jobs: () => request<CardanoJob[]>('/cardano/jobs'),
  job: (id: string) => request<CardanoJob>(`/cardano/jobs/${id}`),
  lookup: (proposal: string) => request<ReleaseLookup>(`/cardano/certificates/lookup?proposal=${encodeURIComponent(proposal)}`),
  certificates: () => request<CertificateMint[]>('/cardano/certificates'),
  mint: (proposal: string) => post<{ job: string }>('/certificates/mint', { proposal }),
  openCertificates: () => post<{ job: string }>('/certificates/open', {}, true),
  release: (proposal: string, entity: string, content: string) =>
    post<{ job: string }>('/certificates/release', { proposal, entity, content }, true),
  escrows: () => request<EscrowView>('/cardano/escrow'),
  createEscrow: (order: string, carrier: string) => post<{ job: string }>('/escrow/create', { order, carrier }),
  deliver: (order: string, node: string) => post<{ job: string }>('/escrow/deliver', { order, node }),
  releaseEscrow: (createTx: string) => post<{ job: string }>('/escrow/release', { createTx }),
  refundEscrow: (createTx: string) => post<{ job: string }>('/escrow/refund', { createTx }),
  accounts: () => request<RewardAccount[]>('/cardano/rewards'),
  claim: (memberKey: string) => post<{ job: string }>('/rewards/claim', { memberKey }),
  openSeason: () => post<{ job: string }>('/rewards/open', {}, true),
  points: (memberKey: string, amount: number) => post<{ job: string }>('/rewards/points', { memberKey, amount }, true),
  populate: (chain: string) => post<{ job: string }>('/populate', { chain }, true),
  anchorNow: (chain: string) => post<{ job: string }>('/anchor-now', { chain }, true),
}

export function explorerLink(template: string | undefined, tx: string | null | undefined): string | null {
  if (!template || !tx) return null
  return template.replace('{tx}', tx)
}

export function short(hex: string | null | undefined, head = 8, tail = 6): string {
  if (!hex) return '—'
  return hex.length <= head + tail + 1 ? hex : `${hex.slice(0, head)}…${hex.slice(-tail)}`
}
