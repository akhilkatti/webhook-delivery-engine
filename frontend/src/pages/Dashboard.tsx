import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { fetchStats, fetchSubscribers } from '../api/client'
import { ALL_STATUSES } from '../api/types'
import { BreakerBadge } from '../components/BreakerBadge'

const WINDOWS = [
    { hours: 1, label: '1 h' },
    { hours: 24, label: '24 h' },
    { hours: 168, label: '7 d' },
]

export function Dashboard() {
    const [hours, setHours] = useState(24)

    const stats = useQuery({
        queryKey: ['stats', hours],
        queryFn: () => fetchStats(hours),
        refetchInterval: 5_000,
    })
    const subscribers = useQuery({
        queryKey: ['subscribers'],
        queryFn: fetchSubscribers,
        refetchInterval: 5_000,   // breaker state changes on the backend
    })

    const s = stats.data

    return (
        <>
            <div className="page-head">
                <h1>Dashboard</h1>
                <div className="seg-control">
                    {WINDOWS.map((w) => (
                        <button key={w.hours} type="button" className={hours === w.hours ? 'on' : ''}
                                onClick={() => setHours(w.hours)}>
                            {w.label}
                        </button>
                    ))}
                </div>
            </div>

            {stats.error && <p className="error">Failed to load stats: {stats.error.message}</p>}

            <div className="cards">
                <div className="card">
                    <span>Deliveries ({WINDOWS.find((w) => w.hours === hours)?.label})</span>
                    <strong>{s ? s.total.toLocaleString() : '…'}</strong>
                </div>
                <div className="card">
                    <span>Success rate (finished only)</span>
                    <strong>{!s || s.successRate == null ? '—' : `${(s.successRate * 100).toFixed(1)}%`}</strong>
                </div>
                <div className="card">
                    <span>p95 latency (successful)</span>
                    <strong>{!s || s.p95LatencyMs == null ? '—' : `${s.p95LatencyMs} ms`}</strong>
                </div>
                <Link
                    to="/deliveries"
                    search={{ sort: 'createdAt', dir: 'desc', status: ['DLQ'] }}
                    className={`card card-link ${s && s.dlqTotal > 0 ? 'card-alert' : ''}`}
                >
                    <span>In DLQ (all time) →</span>
                    <strong>{s ? s.dlqTotal.toLocaleString() : '…'}</strong>
                </Link>
            </div>

            <section className="panel">
                <h2>Status breakdown</h2>
                {s && s.total > 0 ? (
                    <>
                        <div className="statusbar">
                            {ALL_STATUSES.filter((k) => s.byStatus[k] > 0).map((k) => (
                                <div key={k} className={`seg seg-${k}`} style={{ flexGrow: s.byStatus[k] }}
                                     title={`${k}: ${s.byStatus[k]}`} />
                            ))}
                        </div>
                        <div className="legend">
                            {ALL_STATUSES.map((k) => (
                                <span key={k}>
                  <i className={`seg-${k}`} /> {k.replace('_', ' ')} <b>{s.byStatus[k].toLocaleString()}</b>
                </span>
                            ))}
                        </div>
                    </>
                ) : (
                    <p className="muted">No deliveries in this window.</p>
                )}
            </section>

            <section className="panel">
                <h2>Subscriber health</h2>
                {subscribers.data?.length === 0 && <p className="muted">No subscribers yet.</p>}
                <ul className="health-list">
                    {subscribers.data?.map((sub) => (
                        <li key={sub.id}>
              <span className={sub.active ? '' : 'muted'}>
                {sub.name}{!sub.active && ' (inactive)'}
              </span>
                            <BreakerBadge state={sub.breakerState} failureRate={sub.failureRate} />
                        </li>
                    ))}
                </ul>
            </section>
        </>
    )
}