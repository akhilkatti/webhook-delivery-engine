import { useQuery } from '@tanstack/react-query'
import { fetchStats } from '../api/client'

export function Dashboard() {
    const { data, isPending, error } = useQuery({
        queryKey: ['stats', 24],
        queryFn: () => fetchStats(24),
        refetchInterval: 10_000,
    })

    if (isPending) return <p>Loading…</p>
    if (error) return <p className="error">Failed to load stats: {error.message}</p>

    return (
        <>
            <h1>Dashboard</h1>
            <div className="cards">
                <div className="card"><span>Deliveries (24h)</span><strong>{data.total}</strong></div>
                <div className="card">
                    <span>Success rate</span>
                    <strong>{data.successRate == null ? '—' : `${(data.successRate * 100).toFixed(1)}%`}</strong>
                </div>
                <div className="card"><span>p95 latency</span><strong>{data.p95LatencyMs == null ? '—' : `${data.p95LatencyMs} ms`}</strong></div>
                <div className="card"><span>In DLQ (all time)</span><strong>{data.dlqTotal}</strong></div>
            </div>
        </>
    )
}