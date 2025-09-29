import type { FC, ReactNode } from 'react'

const Section: FC<{ title: string; description: ReactNode }> = ({ title, description }) => (
  <section className="space-y-2 rounded-lg border border-slate-800 bg-slate-900/60 p-6 shadow-sm">
    <h2 className="text-xl font-semibold text-slate-100">{title}</h2>
    <p className="text-sm leading-relaxed text-slate-300">{description}</p>
  </section>
)

const ScenarioApp: FC = () => (
  <div className="min-h-screen bg-gradient-to-br from-slate-950 via-slate-900 to-slate-950 p-8 text-slate-100">
    <div className="mx-auto flex w-full max-w-4xl flex-col gap-6">
      <header className="space-y-2 text-center">
        <p className="text-sm uppercase tracking-[0.35em] text-amber-400">PocketHive Remote</p>
        <h1 className="text-4xl font-bold">Scenario Builder Placeholder</h1>
        <p className="text-base text-slate-300">
          This layout confirms the Module Federation remote is wired correctly. Replace this scaffold with the
          interactive Scenario Builder once the remote APIs are available.
        </p>
      </header>

      <Section
        title="Getting Started"
        description='Import the "@ph/scenario/ScenarioApp" module from the remote entry to mount this placeholder in any host UI.'
      />

      <Section
        title="Next Steps"
        description={
          <>
            <span>
              Build out the Scenario Builder screens here. Shared UI elements can be composed from the PocketHive design
              system to ensure the embedded experience matches the host application.
            </span>
          </>
        }
      />
    </div>
  </div>
)

export default ScenarioApp
