import { useEffect, useState, type ReactNode } from 'react'
import clsx from 'clsx'
import type { Status } from './api'

/** Accent classes per chain (static strings so Tailwind keeps them). */
export const ACCENTS: Record<string, { text: string; bg: string; border: string; glow: string }> = {
  cyan: { text: 'text-cyan', bg: 'bg-cyan/10', border: 'border-cyan/30', glow: 'from-cyan/20' },
  violet: { text: 'text-violet', bg: 'bg-violet/10', border: 'border-violet/30', glow: 'from-violet/20' },
  mint: { text: 'text-accent', bg: 'bg-accent/10', border: 'border-accent/30', glow: 'from-accent/20' },
  amber: { text: 'text-amber', bg: 'bg-amber/10', border: 'border-amber/30', glow: 'from-amber/20' },
  sky: { text: 'text-sky', bg: 'bg-sky/10', border: 'border-sky/30', glow: 'from-sky/20' },
  lime: { text: 'text-lime', bg: 'bg-lime/10', border: 'border-lime/30', glow: 'from-lime/20' },
  rose: { text: 'text-rose', bg: 'bg-rose/10', border: 'border-rose/30', glow: 'from-rose/20' },
  slate: { text: 'text-muted', bg: 'bg-ink/5', border: 'border-line', glow: 'from-ink/5' },
}
export const accent = (name: string) => ACCENTS[name] ?? ACCENTS.slate

export function Card({ className, children }: { className?: string; children: ReactNode }) {
  return (
    <div className={clsx('rounded-2xl border border-line bg-surface shadow-[0_1px_2px_rgba(11,17,21,0.04)]', className)}>{children}</div>
  )
}

export function Badge({ children, tone = 'default', className }: {
  children: ReactNode
  tone?: 'default' | 'accent' | 'lime' | 'amber' | 'rose' | 'cyan'
  className?: string
}) {
  const tones = {
    default: 'border-line bg-ink/5 text-muted',
    accent: 'border-accent/30 bg-accent/10 text-accent',
    lime: 'border-lime/30 bg-lime/10 text-lime',
    amber: 'border-amber/30 bg-amber/10 text-amber',
    rose: 'border-rose/30 bg-rose/10 text-rose',
    cyan: 'border-cyan/30 bg-cyan/10 text-cyan',
  }
  return (
    <span className={clsx('inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[11px] font-medium',
      tones[tone], className)}>
      {children}
    </span>
  )
}

export function Button({ children, onClick, variant = 'primary', disabled, type = 'button', className, title }: {
  children: ReactNode
  onClick?: () => void
  variant?: 'primary' | 'ghost' | 'subtle'
  disabled?: boolean
  type?: 'button' | 'submit'
  className?: string
  title?: string
}) {
  const variants = {
    primary: 'bg-accent text-bg hover:bg-accent-strong shadow-[0_8px_20px_-10px_rgba(19,134,106,0.6)]',
    ghost: 'border border-line text-ink hover:border-accent/50 hover:text-accent',
    subtle: 'text-muted hover:text-ink hover:bg-ink/5',
  }
  return (
    <button type={type} onClick={onClick} disabled={disabled} title={title}
      className={clsx('inline-flex items-center justify-center gap-2 rounded-xl px-4 py-2 text-sm font-semibold transition',
        'disabled:cursor-not-allowed disabled:opacity-50 focus-visible:outline-2 focus-visible:outline-accent',
        variants[variant], className)}>
      {children}
    </button>
  )
}

export function Spinner({ className }: { className?: string }) {
  return (
    <span className={clsx('inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-t-transparent',
      className)} aria-hidden />
  )
}

export function LiveDot({ tone = 'accent' }: { tone?: 'accent' | 'amber' | 'rose' }) {
  const color = { accent: 'bg-accent', amber: 'bg-amber', rose: 'bg-rose' }[tone]
  return <span className={clsx('pulse-dot inline-block h-2 w-2 rounded-full', color)} aria-hidden />
}

export const STATUS_STYLE: Record<Status, { label: string; tone: 'accent' | 'amber' | 'rose' | 'cyan'; ring: string }> = {
  APPLIED: { label: 'Applied', tone: 'accent', ring: 'border-accent/40' },
  RECORDED: { label: 'Recorded', tone: 'cyan', ring: 'border-cyan/40' },
  NO_EFFECT: { label: 'No effect', tone: 'amber', ring: 'border-amber/40' },
  REJECTED: { label: 'Refused', tone: 'rose', ring: 'border-rose/40' },
}

export function Mono({ children, className }: { children: ReactNode; className?: string }) {
  return <span className={clsx('font-mono text-[12px] text-muted', className)}>{children}</span>
}

/** Brand mark on its dark tile (the plane is off-white/lime, so it needs the tile on a light page). */
export function Logo({ size = 32 }: { size?: number }) {
  return <img src="/favicon.svg" width={size} height={size} alt="Yano X" className="rounded-[22%] shadow-sm" />
}

/** Minimal hash router: #/ and #/chain/<id>. */
export function useRoute(): string[] {
  const read = () => window.location.hash.replace(/^#\/?/, '').split('/').filter(Boolean)
  const [route, setRoute] = useState(read)
  useEffect(() => {
    const onChange = () => {
      setRoute(read())
      window.scrollTo({ top: 0 })
    }
    window.addEventListener('hashchange', onChange)
    return () => window.removeEventListener('hashchange', onChange)
  }, [])
  return route
}

export const go = (path: string) => { window.location.hash = path }
