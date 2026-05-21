export function Icon({
  name,
}: {
  name: 'home' | 'hive' | 'proxy' | 'journal' | 'scenarios' | 'other' | 'user' | 'buzz' | 'users' | 'edit' | 'trash'
}) {
  const common = { width: 18, height: 18, viewBox: '0 0 24 24', fill: 'none', xmlns: 'http://www.w3.org/2000/svg' }
  switch (name) {
    case 'home':
      return (
        <svg {...common}>
          <path d="M4 10.5 12 4l8 6.5V20a1 1 0 0 1-1 1h-5v-6H10v6H5a1 1 0 0 1-1-1v-9.5Z" stroke="currentColor" strokeWidth="1.6" />
        </svg>
      )
    case 'hive':
      return (
        <svg width={18} height={18} viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <polygon points="12,2 21,7 21,17 12,22 3,17 3,7" fill="#FFC107" stroke="#0f1116" strokeWidth="2" />
          <line x1="3" y1="9" x2="21" y2="9" stroke="#0f1116" strokeWidth="2" />
          <line x1="3" y1="15" x2="21" y2="15" stroke="#0f1116" strokeWidth="2" />
        </svg>
      )
    case 'journal':
      return (
        <svg {...common}>
          <path d="M7 4h10a2 2 0 0 1 2 2v14H7a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z" stroke="currentColor" strokeWidth="1.6" />
          <path d="M9 8h8M9 12h8M9 16h6" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
        </svg>
      )
    case 'proxy':
      return (
        <svg {...common}>
          <rect x="4" y="6" width="6" height="5" rx="1.4" stroke="currentColor" strokeWidth="1.6" />
          <rect x="14" y="13" width="6" height="5" rx="1.4" stroke="currentColor" strokeWidth="1.6" />
          <path d="M10 8.5h4a2 2 0 0 1 2 2v2.5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
          <path d="M14 15.5h-4a2 2 0 0 1-2-2V11" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
        </svg>
      )
    case 'scenarios':
      return (
        <svg {...common}>
          <path d="M6 4h9l3 3v13a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1Z" stroke="currentColor" strokeWidth="1.6" />
          <path d="M15 4v4h4" stroke="currentColor" strokeWidth="1.6" />
        </svg>
      )
    case 'other':
      return (
        <svg {...common}>
          <path d="M6 12h.01M12 12h.01M18 12h.01" stroke="currentColor" strokeWidth="3.2" strokeLinecap="round" />
        </svg>
      )
    case 'buzz':
      return (
        <svg {...common}>
          <circle cx="12" cy="12" r="2.2" stroke="currentColor" strokeWidth="1.6" />
          <path d="M7.2 9.4a6 6 0 0 1 0 5.2" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
          <path d="M16.8 9.4a6 6 0 0 0 0 5.2" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
          <path d="M5.2 7.2a9 9 0 0 1 0 9.6" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
          <path d="M18.8 7.2a9 9 0 0 0 0 9.6" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
        </svg>
      )
    case 'user':
      return (
        <svg {...common}>
          <path d="M12 12.2a4 4 0 1 0-4-4 4 4 0 0 0 4 4Z" stroke="currentColor" strokeWidth="1.6" />
          <path
            d="M4.5 20a7.5 7.5 0 0 1 15 0"
            stroke="currentColor"
            strokeWidth="1.6"
            strokeLinecap="round"
          />
        </svg>
      )
    case 'users':
      return (
        <svg {...common}>
          <path d="M9 11a3 3 0 1 0-3-3 3 3 0 0 0 3 3Z" stroke="currentColor" strokeWidth="1.6" />
          <path d="M16 10a2.5 2.5 0 1 0-2.5-2.5A2.5 2.5 0 0 0 16 10Z" stroke="currentColor" strokeWidth="1.6" />
          <path d="M3.8 19a5.2 5.2 0 0 1 10.4 0" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
          <path d="M14.2 19a4.1 4.1 0 0 1 5-2.8" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
        </svg>
      )
    case 'edit':
      return (
        <svg {...common}>
          <path d="M4.5 19.5h4.2L18.6 9.6a2.1 2.1 0 0 0 0-3L17.4 5.4a2.1 2.1 0 0 0-3 0L4.5 15.3v4.2Z" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
          <path d="M13.2 6.6l4.2 4.2" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
        </svg>
      )
    case 'trash':
      return (
        <svg {...common}>
          <path d="M5 7h14" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
          <path d="M9 7V5.5A1.5 1.5 0 0 1 10.5 4h3A1.5 1.5 0 0 1 15 5.5V7" stroke="currentColor" strokeWidth="1.6" />
          <path d="M7.2 7l.8 12a1.5 1.5 0 0 0 1.5 1.4h5a1.5 1.5 0 0 0 1.5-1.4l.8-12" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
          <path d="M10.5 11v5M13.5 11v5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
        </svg>
      )
  }
}
