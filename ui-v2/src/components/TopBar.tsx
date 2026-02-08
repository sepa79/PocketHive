import { NavLink, useNavigate } from 'react-router-dom'
import { useEffect, useRef, useState } from 'react'
import { Breadcrumbs } from './Breadcrumbs'
import { ConnectivityIndicator } from './ConnectivityIndicator'
import { UserMenu } from './UserMenu'
import { LogoMark } from './LogoMark'
import { subscribeWireLog, type WireLogEntry } from '../lib/controlPlane/wireLogStore'

type NoticeKind = 'alert' | 'outcome' | 'invalid'

type TopBarNotice = {
  id: string
  kind: NoticeKind
  label: string
  detail?: string
  receivedAt: string
}

const MAX_NOTICES = 3

function readString(value: unknown): string | null {
  return typeof value === 'string' ? value : null
}

function noticeFromEntry(entry: WireLogEntry): TopBarNotice | null {
  if (entry.errors.length > 0) {
    const error = entry.errors[0]
    return {
      id: entry.id,
      kind: 'invalid',
      label: error.message,
      detail: error.errorCode,
      receivedAt: entry.receivedAt,
    }
  }

  const envelope = entry.envelope
  if (!envelope) return null

  if (envelope.kind === 'event') {
    const code = readString(envelope.data?.code) ?? envelope.type
    const message = readString(envelope.data?.message)
    return {
      id: entry.id,
      kind: 'alert',
      label: message ? `${code}: ${message}` : code,
      detail: envelope.origin,
      receivedAt: entry.receivedAt,
    }
  }

  if (envelope.kind === 'outcome') {
    const status = readString(envelope.data?.status) ?? 'status'
    return {
      id: entry.id,
      kind: 'outcome',
      label: `${envelope.type} -> ${status}`,
      detail: envelope.origin,
      receivedAt: entry.receivedAt,
    }
  }

  return null
}

function buildNotices(entries: WireLogEntry[]) {
  const notices: TopBarNotice[] = []
  for (let i = entries.length - 1; i >= 0 && notices.length < MAX_NOTICES; i -= 1) {
    const notice = noticeFromEntry(entries[i])
    if (notice) notices.unshift(notice)
  }
  return notices
}

function TopBarNotices({ onNavigate }: { onNavigate: (path: string) => void }) {
  const [notices, setNotices] = useState<TopBarNotice[]>([])

  useEffect(() => {
    const unsubscribe = subscribeWireLog((entries) => setNotices(buildNotices(entries)))
    return () => {
      unsubscribe()
    }
  }, [])

  if (notices.length === 0) {
    return (
      <div className="topBarNotices">
        <span className="topBarNoticesEmpty">No notifications yet</span>
      </div>
    )
  }

  return (
    <div className="topBarNotices">
      {notices.map((notice) => (
        <button
          key={notice.id}
          type="button"
          className={`topBarNotice topBarNotice-${notice.kind}`}
          title={`${notice.receivedAt}${notice.detail ? ` · ${notice.detail}` : ''}`}
          onClick={() => onNavigate('/buzz')}
        >
          <span className="topBarNoticeLabel">{notice.label}</span>
        </button>
      ))}
    </div>
  )
}

function TopBarSearch() {
  const inputRef = useRef<HTMLInputElement | null>(null)
  const [query, setQuery] = useState('')
  const [miss, setMiss] = useState(false)

  const runFind = (needle: string, backward: boolean) => {
    const finder = (window as Window & { find?: (...args: any[]) => boolean }).find
    if (!finder) return false
    return finder(needle, false, backward, true, false, false, false)
  }

  const findNext = (backward = false) => {
    const needle = query.trim()
    if (!needle) return
    const found = runFind(needle, backward)
    setMiss(!found)
  }

  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'f') {
        event.preventDefault()
        inputRef.current?.focus()
        inputRef.current?.select()
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [])

  return (
    <div className={`topBarSearch ${miss ? 'topBarSearchMiss' : ''}`}>
      <input
        ref={inputRef}
        className="topBarSearchInput"
        placeholder="Search page..."
        value={query}
        onChange={(event) => {
          setQuery(event.target.value)
          setMiss(false)
        }}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault()
            findNext(event.shiftKey)
          }
        }}
      />
      <div className="topBarSearchActions">
        <button
          type="button"
          className="iconButton iconButtonBare"
          title="Find previous"
          aria-label="Find previous"
          onClick={() => findNext(true)}
        >
          ↑
        </button>
        <button
          type="button"
          className="iconButton iconButtonBare"
          title="Find next"
          aria-label="Find next"
          onClick={() => findNext(false)}
        >
          ↓
        </button>
        <button
          type="button"
          className="iconButton iconButtonBare"
          title="Clear search"
          aria-label="Clear search"
          onClick={() => {
            setQuery('')
            setMiss(false)
            inputRef.current?.blur()
          }}
        >
          ×
        </button>
      </div>
    </div>
  )
}

type ToolLink = {
  id: string
  title: string
  href: string
  icon: string
}

const toolLinks: ToolLink[] = [
  { id: 'docs', title: 'Docs', href: '/docs/', icon: '/icons/docs.svg' },
  { id: 'rabbitmq', title: 'RabbitMQ', href: '/rabbitmq/', icon: '/icons/rabbitmq.svg' },
  { id: 'prometheus', title: 'Prometheus', href: '/prometheus/', icon: '/icons/prometheus.svg' },
  { id: 'grafana', title: 'Grafana', href: '/grafana/', icon: '/icons/grafana.svg' },
  { id: 'redis', title: 'Redis', href: '/redis/', icon: '/icons/redis.svg' },
  { id: 'wiremock', title: 'WireMock', href: '/wiremock/', icon: '/icons/wiremock.svg' },
]

export function TopBar() {
  const navigate = useNavigate()

  return (
    <div className="topBarInner">
      <div className="topBarLeft">
        <NavLink to="/" className="logoLink logoLinkBare" aria-label="PocketHive home" title="Home">
          <span className="brandMark" aria-hidden="true">
            <LogoMark size={28} />
          </span>
          <span className="brandWordmark" aria-hidden="true">
            <span className="brandWordPocket">Pocket</span>
            <span className="brandWordHive">Hive</span>
          </span>
        </NavLink>
        <Breadcrumbs />
      </div>
      <div className="topBarCenter">
        <TopBarNotices onNavigate={navigate} />
        <TopBarSearch />
      </div>
      <div className="topBarRight">
        <div className="topBarTools">
          {toolLinks.map((tool) => (
            <a
              key={tool.id}
              className="toolLink"
              data-tool={tool.id}
              href={tool.href}
              title={tool.title}
              aria-label={tool.title}
              target="_blank"
              rel="noreferrer"
            >
              <img src={tool.icon} alt="" aria-hidden="true" />
            </a>
          ))}
        </div>
        <ConnectivityIndicator />
        <button
          type="button"
          className="iconButton iconButtonBare"
          title="Help"
          onClick={() => navigate('/help')}
          aria-label="Help"
        >
          <span className="odysseyIcon" aria-hidden="true" />
        </button>
        <UserMenu />
      </div>
    </div>
  )
}
