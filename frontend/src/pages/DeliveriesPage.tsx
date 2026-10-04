import { useQuery } from '@tanstack/react-query'
import { fetchDeliveries } from '../api/client'

export function DeliveriesPage() {
    const { data, isPending, error } = useQuery({
        queryKey: ['deliveries-smoke-test'],
        queryFn: () => fetchDeliveries({ sort: 'createdAt', dir: 'desc' }, null, 10),
    })

    if (isPending) return <p>Loading…</p>
    if (error) return <p className="error">Failed to load deliveries: {error.message}</p>

    return (
        <>
            <h1>Deliveries</h1>
            <p>Smoke test: loaded {data.items.length} rows (hasMore: {String(data.hasMore)}). The grid arrives in Block 2.</p>
            <ul>
                {data.items.map((d) => (
                    <li key={d.id}>{d.subscriberName} — {d.status} — attempts {d.attemptCount}</li>
                ))}
            </ul>
        </>
    )
}