import { useCallback, useEffect, useRef, useState } from 'react'
import { useQueryClient, type InfiniteData } from '@tanstack/react-query'
import type { DeliveryChangedEvent, DeliveryFilters, DeliveryPage } from '../api/types'

export type StreamStatus = 'connecting' | 'live' | 'reconnecting'

const FLUSH_MS = 500
const STATS_MIN_INTERVAL_MS = 5_000
const RETRY_MS = 3_000

export function useDeliveryStream(filters: DeliveryFilters) {
    const qc = useQueryClient()
    const [status, setStatus] = useState<StreamStatus>('connecting')
    const [newCount, setNewCount] = useState(0)

    // Latest filters, readable from inside long-lived callbacks without reconnecting the stream.
    const filtersRef = useRef(filters)
    const unseen = useRef(new Set<string>())      // distinct delivery IDs that the current view doesn't show yet

    // A different view starts with a clean slate.
    const filterKey = JSON.stringify(filters)
    useEffect(() => {
        filtersRef.current = filters
        unseen.current.clear()
        setNewCount(0)
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [filterKey])

    useEffect(() => {
        let es: EventSource | null = null
        let flushTimer: ReturnType<typeof setTimeout> | undefined
        let retryTimer: ReturnType<typeof setTimeout> | undefined
        let disposed = false
        let everOpened = false
        let lastStatsInvalidate = 0
        const buffer = new Map<string, DeliveryChangedEvent>()   // latest event per delivery wins

        const isRelevant = (ev: DeliveryChangedEvent) => {
            const f = filtersRef.current
            if (f.subscriberId && ev.subscriberId !== f.subscriberId) return false
            if (f.status?.length && !f.status.includes(ev.status)) return false
            if (f.httpStatus != null && ev.lastHttpStatus !== f.httpStatus) return false
            return true
        }

        const applyBatch = (batch: DeliveryChangedEvent[]) => {
            const byId = new Map(batch.map((e) => [e.id, e]))
            const matched = new Set<string>()

            // Patch every cached list (any filter/sort combination the user has visited).
            qc.setQueriesData<InfiniteData<DeliveryPage, string | null>>(
                { queryKey: ['deliveries', 'list'] },
                (old) => {
                    if (!old) return old
                    let changed = false
                    const pages = old.pages.map((page) => {
                        let pageChanged = false
                        const items = page.items.map((item) => {
                            const ev = byId.get(item.id)
                            if (!ev) return item
                            matched.add(item.id)
                            // Ignore an event older than what the row already shows (out-of-order or post-refetch).
                            if (Date.parse(ev.updatedAt) < Date.parse(item.updatedAt)) return item
                            pageChanged = true
                            return {
                                ...item,
                                status: ev.status,
                                attemptCount: ev.attemptCount,
                                lastHttpStatus: ev.lastHttpStatus,
                                lastError: ev.lastError,
                                latencyMs: ev.latencyMs,
                                updatedAt: ev.updatedAt,
                            }
                        })
                        if (pageChanged) changed = true
                        return pageChanged ? { ...page, items } : page
                    })
                    return changed ? { ...old, pages } : old   // same reference = no re-render
                },
            )

            // Rows we couldn't patch: new deliveries, or ones beyond the loaded pages.
            let grew = false
            for (const ev of batch) {
                if (matched.has(ev.id) || unseen.current.has(ev.id) || !isRelevant(ev)) continue
                unseen.current.add(ev.id)
                grew = true
            }
            if (grew) setNewCount(unseen.current.size)

            // Open drawers refresh themselves; closed ones are only marked stale.
            for (const id of byId.keys()) qc.invalidateQueries({ queryKey: ['deliveries', 'detail', id] })

            // Stats are an aggregate query, so refresh them at most every few seconds.
            const now = Date.now()
            if (now - lastStatsInvalidate > STATS_MIN_INTERVAL_MS) {
                lastStatsInvalidate = now
                qc.invalidateQueries({ queryKey: ['stats'] })
            }
        }

        const flush = () => {
            flushTimer = undefined
            if (buffer.size === 0) return
            const batch = [...buffer.values()]
            buffer.clear()
            applyBatch(batch)
        }

        const connect = () => {
            if (disposed) return
            es = new EventSource('/api/stream')

            es.onopen = () => {
                setStatus('live')
                if (everOpened) {
                    // Anything that happened while we were disconnected is lost: catch up.
                    qc.invalidateQueries({ queryKey: ['deliveries'] })
                    qc.invalidateQueries({ queryKey: ['stats'] })
                }
                everOpened = true
            }

            es.addEventListener('delivery', (e) => {
                try {
                    const ev = JSON.parse((e as MessageEvent<string>).data) as DeliveryChangedEvent
                    buffer.set(ev.id, ev)
                    if (flushTimer === undefined) flushTimer = setTimeout(flush, FLUSH_MS)
                } catch {
                    // ignore a malformed message rather than killing the stream
                }
            })

            es.onerror = () => {
                setStatus('reconnecting')
                // Network drop: the browser retries by itself. HTTP error (e.g. 502 from the proxy): it gives up, so we retry.
                if (es && es.readyState === EventSource.CLOSED) {
                    es.close()
                    retryTimer = setTimeout(connect, RETRY_MS)
                }
            }
        }

        connect()
        return () => {
            disposed = true
            es?.close()
            clearTimeout(flushTimer)
            clearTimeout(retryTimer)
        }
    }, [qc])

    /** "N new updates" pill clicked: drop the counter and refetch the visible lists from page 1. */
    const showNew = useCallback(() => {
        unseen.current.clear()
        setNewCount(0)
        qc.invalidateQueries({ queryKey: ['deliveries', 'list'] })
    }, [qc])

    return { status, newCount, showNew }
}