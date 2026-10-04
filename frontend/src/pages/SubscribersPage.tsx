import { useQuery } from '@tanstack/react-query'
import { fetchSubscribers } from '../api/client'

export function SubscribersPage() {
    const { data, isPending, error } = useQuery({
        queryKey: ['subscribers'],
        queryFn: fetchSubscribers,
        refetchInterval: 5_000, // breaker state changes on the backend
    })

    if (isPending) return <p>Loading…</p>
    if (error) return <p className="error">Failed to load subscribers: {error.message}</p>

    return (
        <>
            <h1>Subscribers</h1>
            <table className="simple">
                <thead>
                <tr><th>Name</th><th>URL</th><th>Limit/min</th><th>Active</th><th>Breaker</th></tr>
                </thead>
                <tbody>
                {data.map((s) => (
                    <tr key={s.id}>
                        <td>{s.name}</td>
                        <td className="mono">{s.url}</td>
                        <td>{s.rateLimitPerMin}</td>
                        <td>{s.active ? 'yes' : 'no'}</td>
                        <td>{s.breakerState}</td>
                    </tr>
                ))}
                </tbody>
            </table>
        </>
    )
}