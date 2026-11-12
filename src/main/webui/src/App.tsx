import { useQuery } from '@tanstack/react-query'
import { Anchor, Globe2, Info, Server } from 'lucide-react'
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, quorumText, type ClientConfig, type MemberInfo } from './api'
import { Catalog } from './Catalog'
import type { ConfigFile } from './chainConfig'
import { ConfigPage } from './ConfigPage'
import { CardanoPage, useCardano } from './OnCardano'
import { Workspace } from './Workspace'
import { Logo, go, useRoute } from './ui'

interface DemoContextValue {
  config: ClientConfig | undefined
  members: MemberInfo[]
  readNode: string | undefined
  setReadNode: (node: string) => void
  submissions: { id: string; chainId: string }[]
  track: (id: string, chainId: string) => void
  /** App-chain files the visitor opened in this page (kept in memory only, never sent anywhere). */
  uploads: ConfigFile[]
  addUploads: (files: ConfigFile[]) => void
}

const DemoContext = createContext<DemoContextValue | null>(null)
export const useDemo = () => {
  const value = useContext(DemoContext)
  if (!value) throw new Error('DemoContext missing')
  return value
}

const READ_NODE_KEY = 'yano-demo-read-node'
const SUBMISSIONS_KEY = 'yano-demo-submissions'

function load<T>(key: string, fallback: T, storage: () => Storage): T {
  try {
    const raw = storage().getItem(key)
    return raw ? (JSON.parse(raw) as T) : fallback
  } catch {
    return fallback
  }
}

function DemoProvider({ children }: { children: ReactNode }) {
  const config = useQuery({ queryKey: ['config'], queryFn: api.config, staleTime: Infinity })
  const members = useQuery({ queryKey: ['members'], queryFn: api.members, staleTime: 60_000 })
  const [readNode, setReadNodeState] = useState<string | undefined>(() => load(READ_NODE_KEY, undefined, () => localStorage))
  const [submissions, setSubmissions] = useState<{ id: string; chainId: string }[]>(
    () => load(SUBMISSIONS_KEY, [], () => sessionStorage))

  useEffect(() => {
    const nodes = config.data?.nodes ?? []
    if (nodes.length && (!readNode || !nodes.some((n) => n.id === readNode))) {
      setReadNodeState((nodes.find((n) => n.isDefault) ?? nodes[0]).id)
    }
  }, [config.data, readNode])

  const setReadNode = useCallback((node: string) => {
    setReadNodeState(node)
    try { localStorage.setItem(READ_NODE_KEY, JSON.stringify(node)) } catch { /* private mode */ }
  }, [])

  const [uploads, setUploads] = useState<ConfigFile[]>([])
  const addUploads = useCallback((files: ConfigFile[]) => {
    setUploads((previous) => [...files, ...previous.filter((p) => !files.some((f) => f.path === p.path))])
  }, [])

  const track = useCallback((id: string, chainId: string) => {
    setSubmissions((previous) => {
      const next = [{ id, chainId }, ...previous].slice(0, 40)
      try { sessionStorage.setItem(SUBMISSIONS_KEY, JSON.stringify(next)) } catch { /* ignore */ }
      return next
    })
  }, [])

  const value = useMemo(() => ({
    config: config.data, members: members.data ?? [], readNode, setReadNode, submissions, track, uploads, addUploads,
  }), [config.data, members.data, readNode, setReadNode, submissions, track, uploads, addUploads])

  return <DemoContext.Provider value={value}>{children}</DemoContext.Provider>
}

function Header() {
  const { config, readNode, setReadNode } = useDemo()
  const onCardano = useCardano()
  return (
    <header className="sticky top-0 z-30 border-b border-line/70 bg-surface/85 backdrop-blur-md">
      <div className="mx-auto flex max-w-7xl items-center gap-4 px-4 py-3 sm:px-6">
        <button onClick={() => go('/')} className="group flex items-center gap-3" aria-label="Home">
          <span className="relative">
            <Logo size={34} />
          </span>
          <span className="flex flex-col items-start leading-tight">
            <span className="font-display text-lg font-bold tracking-tight">
              Yano <span className="text-lime">X</span>
              <span className="ml-2 font-sans text-sm font-medium text-muted">Demo client</span>
            </span>
            <span className="hidden text-[11px] text-faint sm:block">{config?.clusterName ?? 'Connecting…'}</span>
          </span>
        </button>
        <div className="ml-auto flex items-center gap-2">
          {onCardano.data?.enabled && (
            <button onClick={() => go('/cardano')} title="Validators on Cardano that act on anchored app-ledger facts"
              className="inline-flex items-center gap-1.5 rounded-full border border-lime/30 bg-lime/10 px-3 py-1 text-[12px] font-medium text-lime hover:bg-lime/15">
              <Anchor size={12} /> Anchor &amp; mint
            </button>
          )}
          <span className="hidden items-center gap-1.5 rounded-full border border-amber/30 bg-amber/10 px-3 py-1 text-[11px] font-medium text-amber md:inline-flex"
            title="Everything submitted here is written to a public test network and is permanent.">
            <Globe2 size={12} /> Public test network · data is permanent
          </span>
          {config && (
            <label className="flex items-center gap-2 rounded-xl border border-line bg-surface px-2.5 py-1.5 text-xs text-muted"
              title="The member node used for reading state. Writes always go through the member you act as.">
              <Server size={13} className="text-accent" />
              <span className="hidden sm:inline">Read from</span>
              <select value={readNode ?? ''} onChange={(e) => setReadNode(e.target.value)}
                className="bg-transparent font-medium text-ink outline-none">
                {config.nodes.map((node) => (
                  <option key={node.id} value={node.id} className="bg-surface">{node.label} · {node.host}</option>
                ))}
              </select>
            </label>
          )}
        </div>
      </div>
    </header>
  )
}

function Footer() {
  const { config } = useDemo()
  return (
    <footer className="mx-auto mt-16 max-w-7xl border-t border-line/60 px-4 py-8 text-xs text-faint sm:px-6">
      <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
        <span className="flex items-center gap-2"><Logo size={16} /> Yano X — app ledgers that settle on Cardano</span>
        <a className="hover:text-accent" href="https://yano-x.io" target="_blank" rel="noreferrer">yano-x.io</a>
        <a className="hover:text-accent" href="https://github.com/bloxbean/yano-x" target="_blank" rel="noreferrer">GitHub</a>
        <span className="flex items-center gap-1.5"><Info size={12} /> Every write is signed by a member node, finalized by {quorumText(config)} signatures and periodically anchored on Cardano preprod.</span>
      </div>
    </footer>
  )
}

export default function App() {
  const route = useRoute()
  return (
    <DemoProvider>
      <Header />
      <main className="mx-auto max-w-7xl px-4 pt-6 sm:px-6">
        {route[0] === 'cardano' ? <CardanoPage />
          : route[0] === 'chain' && route[1] ? <Workspace chainId={route[1]} />
          : route[0] === 'config' && route[1] ? <ConfigPage chainId={route[1]} /> : <Catalog />}
      </main>
      <Footer />
    </DemoProvider>
  )
}
