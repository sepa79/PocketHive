export function Icon({ name }: { name: 'home' | 'hive' | 'journal' | 'scenarios' | 'other' | 'user' }) {
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
  }
}
