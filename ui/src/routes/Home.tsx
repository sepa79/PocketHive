import Card from '@components/ui/Card'
import { useQuery } from '@tanstack/react-query'

export default function Home() {
  const { data, isLoading } = useQuery({
    queryKey: ['welcome'],
    queryFn: () => Promise.resolve('Data loaded!')
  })

  return (
    <>
      <Card title="Home" subtitle="Welcome to PocketHive.">
        <p className="opacity-90">This is the themed React shell. Use the top/side navigation to explore Hive, Buzz, and Nectar placeholders.</p>
        <p className="mt-4">{isLoading ? 'Loading...' : data}</p>
      </Card>

      <Card title="Quick links">
        <ul className="list-disc pl-6 text-sm text-phl-muted dark:text-ph-muted">
          <li>Scenarios &amp; Flows (coming soon)</li>
          <li>RabbitMQ metrics (coming soon)</li>
          <li>Logs &amp; diagnostics (coming soon)</li>
        </ul>
      </Card>
    </>
  )
}
