import { useEffect, useMemo, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeHighlight from 'rehype-highlight'
import mermaid from 'mermaid'
import type { Components, UrlTransform } from 'react-markdown'
import 'highlight.js/styles/github-dark.css'

type DocFormat = 'markdown' | 'yaml'

type DocId = 'readme' | 'bindings' | 'changelog' | 'api'

type DocConfig = {
  title: string
  source: string
  format: DocFormat
  description?: string
}

const DOCS: Record<DocId, DocConfig> = {
  readme: {
    title: 'PocketHive README',
    source: '/docs/README.md',
    format: 'markdown',
  },
  bindings: {
    title: 'Buzz Bindings',
    source: '/docs/rules/control-plane-rules.md',
    format: 'markdown',
    description: 'Control-plane rules that drive the Buzz bindings.',
  },
  changelog: {
    title: 'Changelog',
    source: '/docs/CHANGELOG.md',
    format: 'markdown',
  },
  api: {
    title: 'AsyncAPI Specification',
    source: '/docs/spec/asyncapi.yaml',
    format: 'yaml',
    description: 'Full AsyncAPI contract for the orchestrator topics.',
  },
}

function normalizeAssetUrl(uri?: string | null) {
  if (!uri) return ''
  if (/^[a-z]+:/i.test(uri) || uri.startsWith('#')) {
    return uri
  }
  if (uri.startsWith('/')) {
    return uri
  }
  if (uri.startsWith('./')) {
    return normalizeAssetUrl(uri.replace(/^\.\//, ''))
  }
  if (uri.startsWith('ui/')) {
    return `/${uri.replace(/^ui\//, '')}`
  }
  return `/${uri.replace(/^\//, '')}`
}

let mermaidInitialized = false

function escapeHtml(value: string) {
  return value.replace(/[&<>"]/g, (char) => {
    switch (char) {
      case '&':
        return '&amp;'
      case '<':
        return '&lt;'
      case '>':
        return '&gt;'
      case '"':
        return '&quot;'
      default:
        return char
    }
  })
}

function ensureMermaid() {
  if (!mermaidInitialized) {
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: 'loose',
      theme: 'dark',
    })
    mermaidInitialized = true
  }
}

let mermaidId = 0

const markdownUrlTransform: UrlTransform = (url) => normalizeAssetUrl(url)

function MermaidDiagram({ code }: { code: string }) {
  const containerRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    ensureMermaid()
    let isMounted = true

    const render = async () => {
      try {
        const renderId = `mermaid-${mermaidId++}`
        const { svg } = await mermaid.render(renderId, code)
        if (isMounted && containerRef.current) {
          containerRef.current.innerHTML = svg
        }
      } catch (err) {
        if (isMounted && containerRef.current) {
          const message = err instanceof Error ? err.message : String(err)
          containerRef.current.innerHTML = `<pre class="doc-code doc-code--error">${escapeHtml(message)}</pre>`
        }
      }
    }

    render()

    return () => {
      isMounted = false
      if (containerRef.current) {
        containerRef.current.innerHTML = ''
      }
    }
  }, [code])

  return <div className="mermaid-diagram" ref={containerRef} data-testid="mermaid-diagram" />
}

export default function DocPage() {
  const { docId } = useParams<{ docId: DocId }>()
  const config = docId ? DOCS[docId] : undefined
  const [content, setContent] = useState<string>('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState<boolean>(false)

  useEffect(() => {
    if (!config) {
      return
    }

    let isCurrent = true
    setLoading(true)
    setError(null)

    const load = async () => {
      try {
        const res = await fetch(config.source, { cache: 'no-store' })
        if (!res.ok) {
          throw new Error(`Failed to load document (${res.status})`)
        }
        const text = await res.text()
        if (!isCurrent) return
        const formatted =
          config.format === 'yaml' ? `\n\n\`\`\`yaml\n${text}\n\`\`\`` : text
        setContent(formatted)
      } catch (err) {
        if (!isCurrent) return
        setError(err instanceof Error ? err.message : 'Unknown error')
        setContent('')
      } finally {
        if (isCurrent) {
          setLoading(false)
        }
      }
    }

    load()

    return () => {
      isCurrent = false
    }
  }, [config])

  useEffect(() => {
    if (config) {
      document.title = `${config.title} – PocketHive`
    }
  }, [config])

  const components = useMemo<Components>(() => ({
    code({ inline, className, children, ...props }) {
      const match = /language-(\w+)/.exec(className || '')
      if (!inline && match && match[1] === 'mermaid') {
        return <MermaidDiagram code={String(children)} />
      }
      return (
        <code className={className ? `doc-code ${className}` : 'doc-code'} {...props}>
          {children}
        </code>
      )
    },
    table({ className, ...props }) {
      return <table className={`doc-table ${className ?? ''}`.trim()} {...props} />
    },
    th({ className, ...props }) {
      return <th className={`doc-table__header ${className ?? ''}`.trim()} {...props} />
    },
    td({ className, ...props }) {
      return <td className={`doc-table__cell ${className ?? ''}`.trim()} {...props} />
    },
    a({ href = '', ...props }) {
      const normalized = normalizeAssetUrl(href)
      const external = /^https?:/i.test(normalized)
      return (
        <a
          href={normalized}
          target={external ? '_blank' : undefined}
          rel={external ? 'noopener noreferrer' : undefined}
          {...props}
        />
      )
    },
    img({ src = '', alt = '', ...props }) {
      return <img src={normalizeAssetUrl(src)} alt={alt} {...props} />
    },
  }), [])

  if (!config) {
    return (
      <div className="doc-page">
        <div className="doc-page__body">
          <p className="doc-message">The requested document could not be found.</p>
        </div>
      </div>
    )
  }

  return (
    <div className="doc-page">
      <header className="doc-page__header">
        <div>
          <h1 className="doc-page__title">{config.title}</h1>
          {config.description && <p className="doc-page__description">{config.description}</p>}
        </div>
      </header>
      <div className="doc-page__body">
        {loading && <p className="doc-message">Loading document…</p>}
        {!loading && error && <p className="doc-message doc-message--error">{error}</p>}
        {!loading && !error && (
          <ReactMarkdown
            className="doc-markdown"
            remarkPlugins={[remarkGfm]}
            rehypePlugins={[rehypeHighlight]}
            components={components}
            urlTransform={markdownUrlTransform}
          >
            {content}
          </ReactMarkdown>
        )}
      </div>
    </div>
  )
}
