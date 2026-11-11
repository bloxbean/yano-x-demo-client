import { ArrowLeft } from 'lucide-react'
import { ConfigPanel } from './ConfigPanel'
import { go } from './ui'

/** Configuration of a chain the demo has no interactive module for (history, settlement, payments, …). */
export function ConfigPage({ chainId }: { chainId: string }) {
  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <button onClick={() => go('/')} className="inline-flex items-center gap-1.5 text-sm text-muted hover:text-accent">
        <ArrowLeft size={15} /> All chains
      </button>
      <div>
        <div className="text-xs font-semibold uppercase tracking-[0.18em] text-faint">Configuration</div>
        <h1 className="mt-1 font-mono text-3xl font-bold">{chainId}</h1>
      </div>
      <ConfigPanel chainId={chainId} />
    </div>
  )
}
