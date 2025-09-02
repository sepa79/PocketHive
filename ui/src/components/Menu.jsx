import { useState, useRef, useEffect } from 'react';

export default function Menu() {
  const [open, setOpen] = useState(false);
  const ref = useRef(null);
  useEffect(() => {
    const handler = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false); };
    document.addEventListener('click', handler);
    return () => document.removeEventListener('click', handler);
  }, []);
  return (
    <div className="menu" ref={ref} style={{ position: 'relative' }}>
      <button id="menu-btn" className="bg-toggle" style={{ cursor: 'pointer' }} onClick={() => setOpen(o => !o)}>☰ Menu</button>
      {open && (
        <div id="menu-dropdown" className="dropdown-panel" style={{ position: 'absolute', right: 0, top: '36px', minWidth: '240px' }}>
          <a href="readme.html" className="dropdown-item">README</a>
          <a href="bindings.html" className="dropdown-item">Buzz Bindings</a>
          <a href="changelog.html" className="dropdown-item">Changelog</a>
          <a href="docs.html" className="dropdown-item">API Docs</a>
        </div>
      )}
    </div>
  );
}
