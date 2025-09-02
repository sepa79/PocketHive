import { useState } from 'react';
import Menu from './components/Menu.jsx';
import HiveView from './components/HiveView.jsx';

function Placeholder({ name }) {
  return <div style={{ padding: '20px' }}>{name} view coming soon…</div>;
}

export default function App() {
  const [tab, setTab] = useState('hive');
  return (
    <>
      <header>
        <img className="logo" src="/assets/logo.svg" alt="PocketHive Logo" />
        <nav className="nav-tabs">
          <button id="tab-hive" className={`tab-btn ${tab === 'hive' ? 'tab-active' : ''}`} type="button" onClick={() => setTab('hive')}>
            <img src="/assets/hive.svg" alt="" className="tab-icon" />Hive
          </button>
          <button id="tab-buzz" className={`tab-btn ${tab === 'buzz' ? 'tab-active' : ''}`} type="button" onClick={() => setTab('buzz')}>
            <img src="/assets/buzz.svg" alt="" className="tab-icon" />Buzz
          </button>
          <button id="tab-nectar" className={`tab-btn ${tab === 'nectar' ? 'tab-active' : ''}`} type="button" onClick={() => setTab('nectar')}>
            <img src="/assets/nectar.svg" alt="" className="tab-icon" />Nectar
          </button>
        </nav>
        <div className="header-right">
          <div className="service-links">
            <a href="#" id="link-rabbitmq" target="_blank" rel="noopener" aria-label="RabbitMQ"><img src="/assets/icons/rabbitmq.svg" alt="RabbitMQ" /></a>
            <a href="#" id="link-prometheus" target="_blank" rel="noopener" aria-label="Prometheus"><img src="/assets/icons/prometheus.svg" alt="Prometheus" /></a>
            <a href="#" id="link-grafana" target="_blank" rel="noopener" aria-label="Grafana"><img src="/assets/icons/grafana.svg" alt="Grafana" /></a>
            <a href="#" id="link-wiremock" target="_blank" rel="noopener" aria-label="WireMock"><img src="/assets/icons/wiremock.svg" alt="WireMock" /></a>
          </div>
          <Menu />
        </div>
      </header>
      <main>
        {tab === 'hive' && <HiveView />}
        {tab === 'buzz' && <Placeholder name="Buzz" />}
        {tab === 'nectar' && <Placeholder name="Nectar" />}
      </main>
    </>
  );
}
