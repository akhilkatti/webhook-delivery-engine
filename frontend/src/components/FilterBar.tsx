import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { fetchSubscribers } from '../api/client'
import { ALL_STATUSES } from '../api/types'
import type { DeliveryFilters, DeliveryStatus } from '../api/types'
import { useDebounced } from '../hooks/useDebounced'

interface Props {
    filters: DeliveryFilters
    onChange: (patch: Partial<DeliveryFilters>) => void
    onClear: () => void
}

// <input type="datetime-local"> speaks local time without a zone; the API wants ISO instants.
const toLocalInput = (iso?: string) => {
    if (!iso) return ''
    const d = new Date(iso)
    return new Date(d.getTime() - d.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
}
const toIso = (local: string) => (local ? new Date(local).toISOString() : undefined)

export function FilterBar({ filters, onChange, onClear }: Props) {
    const { data: subscribers } = useQuery({ queryKey: ['subscribers'], queryFn: fetchSubscribers })

    // HTTP status is free text, so it's debounced; the other controls are discrete and apply instantly.
    const [httpText, setHttpText] = useState(filters.httpStatus?.toString() ?? '')
    const debouncedHttp = useDebounced(httpText, 400)

    useEffect(() => {
        const n = debouncedHttp === '' ? undefined : Number(debouncedHttp)
        if (n !== undefined && !Number.isInteger(n)) return
        if (n !== filters.httpStatus) onChange({ httpStatus: n })
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [debouncedHttp])

    // If the URL changes from outside (Clear, back button), reflect it in the box.
    useEffect(() => {
        const current = httpText === '' ? undefined : Number(httpText)
        if (filters.httpStatus !== current) setHttpText(filters.httpStatus?.toString() ?? '')
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [filters.httpStatus])

    const selected = filters.status ?? []
    const toggleStatus = (s: DeliveryStatus) => {
        const next = selected.includes(s) ? selected.filter((x) => x !== s) : [...selected, s]
        onChange({ status: next.length ? next : undefined })
    }

    return (
        <div className="filters">
            <div className="chips">
                {ALL_STATUSES.map((s) => (
                    <button
                        key={s}
                        type="button"
                        className={`chip ${selected.includes(s) ? 'on' : ''}`}
                        onClick={() => toggleStatus(s)}
                    >
                        {s.replace('_', ' ')}
                    </button>
                ))}
            </div>

            <select
                value={filters.subscriberId ?? ''}
                onChange={(e) => onChange({ subscriberId: e.target.value || undefined })}
            >
                <option value="">All subscribers</option>
                {subscribers?.map((s) => (
                    <option key={s.id} value={s.id}>{s.name}</option>
                ))}
            </select>

            <label>
                From <input type="datetime-local" value={toLocalInput(filters.from)}
                            onChange={(e) => onChange({ from: toIso(e.target.value) })} />
            </label>
            <label>
                To <input type="datetime-local" value={toLocalInput(filters.to)}
                          onChange={(e) => onChange({ to: toIso(e.target.value) })} />
            </label>

            <input
                className="http-input"
                inputMode="numeric"
                placeholder="HTTP status"
                value={httpText}
                onChange={(e) => setHttpText(e.target.value.replace(/\D/g, ''))}
            />

            <button type="button" className="link-btn" onClick={onClear}>Clear filters</button>
        </div>
    )
}