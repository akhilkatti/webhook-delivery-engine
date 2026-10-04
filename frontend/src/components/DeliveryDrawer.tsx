import { useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import { fetchDelivery } from '../api/client'
import { useReplay } from '../hooks/useReplay'
import { StatusBadge } from './StatusBadge'

const fmt = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : '—')

function prettyJson(raw: string) {
    try { return JSON.stringify(JSON.parse(raw), null, 2) } catch { return raw }
}

export function DeliveryDrawer({ id, onClose }: { id: string; onClose: () => void }) {
    const { data, isPending, error } = useQuery({
        queryKey: ['deliveries', 'detail', id],
        queryFn: () => fetchDelivery(id),
    })
    const replay = useReplay()

    useEffect(() => {
        const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
        window.addEventListener('keydown', onKey)
        return () => window.removeEventListener('keydown', onKey)
    }, [onClose])

    return (
        <>
            <div className="backdrop" onClick={onClose} />
            <aside className="drawer" role="dialog" aria-label="Delivery detail">
                <header>
                    <h2>Delivery</h2>
                    <button type="button" className="link-btn" onClick={onClose}>Close ✕</button>
                </header>

                {isPending && <p>Loading…</p>}
                {error && <p className="error">Failed to load: {error.message}</p>}

                {data && (
                    <>
                        <dl>
                            <dt>ID</dt><dd className="mono">{data.delivery.id}</dd>
                            <dt>Status</dt><dd><StatusBadge status={data.delivery.status} /></dd>
                            <dt>Subscriber</dt><dd>{data.delivery.subscriberName}</dd>
                            <dt>Event type</dt><dd>{data.eventType}</dd>
                            <dt>Attempts</dt><dd>{data.delivery.attemptCount}</dd>
                            <dt>Last HTTP status</dt><dd>{data.delivery.lastHttpStatus ?? '—'}</dd>
                            <dt>Latency</dt><dd>{data.delivery.latencyMs == null ? '—' : `${data.delivery.latencyMs} ms`}</dd>
                            <dt>Next attempt</dt><dd>{fmt(data.delivery.nextAttemptAt)}</dd>
                            <dt>Created</dt><dd>{fmt(data.delivery.createdAt)}</dd>
                            <dt>Updated</dt><dd>{fmt(data.delivery.updatedAt)}</dd>
                        </dl>

                        {data.delivery.lastError && (
                            <>
                                <h3>Last error</h3>
                                <pre className="error-box">{data.delivery.lastError}</pre>
                            </>
                        )}

                        <h3>Payload</h3>
                        <pre className="payload">{prettyJson(data.payload)}</pre>

                        {data.delivery.status === 'DLQ' && (
                            <>
                                <button type="button" className="primary" disabled={replay.isPending}
                                        onClick={() => replay.mutate(id)}>
                                    {replay.isPending ? 'Replaying…' : 'Replay delivery'}
                                </button>
                                {replay.error && <p className="error">{replay.error.message}</p>}
                            </>
                        )}
                    </>
                )}
            </aside>
        </>
    )
}