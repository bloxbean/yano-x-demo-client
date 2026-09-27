// Typed client for the demo backend (/api/demo). The browser never talks to Yano nodes directly.

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
}
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
  config: () => request<ClientConfig>('/config'),
  members: () => request<MemberInfo[]>('/members'),
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

export function explorerLink(template: string | undefined, tx: string | null | undefined): string | null {
  if (!template || !tx) return null
  return template.replace('{tx}', tx)
}

export function short(hex: string | null | undefined, head = 8, tail = 6): string {
  if (!hex) return '—'
  return hex.length <= head + tail + 1 ? hex : `${hex.slice(0, head)}…${hex.slice(-tail)}`
}
