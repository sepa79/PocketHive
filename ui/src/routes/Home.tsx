import { useQuery } from '@tanstack/react-query';

export default function Home() {
  const { data, isLoading } = useQuery({
    queryKey: ['welcome'],
    queryFn: () => Promise.resolve('Data loaded!'),
  });

  return (
    <div className="space-y-2">
      <h1 className="text-2xl font-bold">Home</h1>
      <p>Welcome to PocketHive.</p>
      <p>{isLoading ? 'Loading...' : data}</p>
    </div>
  );
}
