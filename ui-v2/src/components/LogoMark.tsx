export function LogoMark({ size = 22 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 256 256"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      <defs>
        <radialGradient id="phLensGrad" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stopColor="#FF3C2E" />
          <stop offset="40%" stopColor="#B20E00" />
          <stop offset="100%" stopColor="#400000" />
        </radialGradient>
      </defs>
      <polygon
        points="128.0,15.36 225.55,71.68 225.55,184.32 128.0,240.64 30.45,184.32 30.45,71.68"
        fill="none"
        stroke="#FFC107"
        strokeWidth="15.36"
        strokeLinejoin="round"
      />
      <line x1="128.00" y1="128.00" x2="128.00" y2="66.05" stroke="#FFC107" strokeWidth="6.4" />
      <line x1="128.00" y1="128.00" x2="181.65" y2="97.02" stroke="#FFC107" strokeWidth="6.4" />
      <line x1="128.00" y1="128.00" x2="181.65" y2="158.98" stroke="#FFC107" strokeWidth="6.4" />
      <line x1="128.00" y1="128.00" x2="128.00" y2="189.95" stroke="#FFC107" strokeWidth="6.4" />
      <line x1="128.00" y1="128.00" x2="74.35" y2="158.98" stroke="#FFC107" strokeWidth="6.4" />
      <line x1="128.00" y1="128.00" x2="74.35" y2="97.02" stroke="#FFC107" strokeWidth="6.4" />
      <rect
        x="94.21"
        y="77.31"
        width="67.58"
        height="101.38"
        rx="10.24"
        fill="#0f1116"
        stroke="#ffffff"
        strokeWidth="3.07"
      />
      <circle cx="128.00" cy="119.89" r="28.16" fill="#222" stroke="#999" strokeWidth="3.07" />
      <circle cx="128.00" cy="119.89" r="19.71" fill="url(#phLensGrad)" />
      <g fill="#444">
        <rect x="104.21" y="150.69" width="47.58" height="4.00" />
        <rect x="104.21" y="157.69" width="47.58" height="4.00" />
        <rect x="104.21" y="164.69" width="47.58" height="4.00" />
      </g>
      <circle cx="128.00" cy="66.05" r="10.24" fill="#ffffff" stroke="#0f1116" strokeWidth="4.61" />
      <circle cx="181.65" cy="97.02" r="10.24" fill="#ffffff" stroke="#0f1116" strokeWidth="4.61" />
      <circle cx="181.65" cy="158.98" r="10.24" fill="#ffffff" stroke="#0f1116" strokeWidth="4.61" />
      <circle cx="128.00" cy="189.95" r="10.24" fill="#ffffff" stroke="#0f1116" strokeWidth="4.61" />
      <circle cx="74.35" cy="158.98" r="10.24" fill="#ffffff" stroke="#0f1116" strokeWidth="4.61" />
      <circle cx="74.35" cy="97.02" r="10.24" fill="#ffffff" stroke="#0f1116" strokeWidth="4.61" />
    </svg>
  )
}
