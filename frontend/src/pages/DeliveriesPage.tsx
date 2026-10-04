import { useEffect, useMemo, useRef, useState } from 'react'
import { getRouteApi } from '@tanstack/react-router'
import {
    createColumnHelper, flexRender, getCoreRowModel, useReactTable,
    type SortingState,
} from '@tanstack/react-table'
import { useVirtualizer } from '@tanstack/react-virtual'
import type { Delivery, DeliveryFilters, SortField } from '../api/types'
import { useDeliveries } from '../hooks/useDeliveries'
import { useReplay } from '../hooks/useReplay'
import { StatusBadge } from '../components/StatusBadge'
import { FilterBar } from '../components/FilterBar'
import { DeliveryDrawer } from '../components/DeliveryDrawer'

const route = getRouteApi('/deliveries')   // typed access to this route's search params, without a circular import
const ROW_HEIGHT = 40
const columnHelper = createColumnHelper<Delivery>()

export function DeliveriesPage() {
    const filters = route.useSearch()
    const navigate = route.useNavigate()
    const [selectedId, setSelectedId] = useState<string | null>(null)
    const replay = useReplay()

    // ---- data: pages of 50 flattened into one array ----
    const { data, isPending, error, refetch, fetchNextPage, hasNextPage, isFetchingNextPage, isPlaceholderData } =
        useDeliveries(filters)
    const rows = useMemo(() => data?.pages.flatMap((p) => p.items) ?? [], [data])

    // ---- URL updates (replace: true so filter tweaks don't flood browser history) ----
    const updateFilters = (patch: Partial<DeliveryFilters>) =>
        navigate({ search: (prev) => ({ ...prev, ...patch }), replace: true })
    const clearFilters = () =>
        navigate({ search: (prev) => ({ sort: prev.sort, dir: prev.dir }), replace: true })

    // ---- table: all state comes from the URL, all sorting happens on the server ----
    const columns = useMemo(
        () => [
            columnHelper.accessor('createdAt', {
                header: 'Created', size: 190, enableSorting: true, sortDescFirst: true,
                cell: (c) => new Date(c.getValue()).toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'medium' }),
            }),
            columnHelper.accessor('subscriberName', { header: 'Subscriber', size: 170, enableSorting: false }),
            columnHelper.accessor('status', {
                header: 'Status', size: 120, enableSorting: false,
                cell: (c) => <StatusBadge status={c.getValue()} />,
            }),
            columnHelper.accessor('attemptCount', {
                header: 'Attempts', size: 100, enableSorting: true, sortDescFirst: true,
            }),
            columnHelper.accessor('lastHttpStatus', {
                header: 'HTTP', size: 80, enableSorting: false, cell: (c) => c.getValue() ?? '—',
            }),
            columnHelper.accessor('latencyMs', {
                header: 'Latency', size: 110, enableSorting: true, sortDescFirst: true,
                cell: (c) => (c.getValue() == null ? '—' : `${c.getValue()} ms`),
            }),
            columnHelper.accessor('lastError', {
                header: 'Last error', size: 260, enableSorting: false,
                cell: (c) => <span title={c.getValue() ?? ''}>{c.getValue() ?? ''}</span>,
            }),
            columnHelper.display({
                id: 'actions', header: '', size: 90,
                cell: (c) =>
                    c.row.original.status === 'DLQ' ? (
                        <button
                            type="button" className="small-btn" disabled={replay.isPending}
                            onClick={(e) => { e.stopPropagation(); replay.mutate(c.row.original.id) }}
                        >
                            Replay
                        </button>
                    ) : null,
            }),
        ],
        [replay.mutate, replay.isPending],
    )

    const sorting: SortingState = [{ id: filters.sort, desc: filters.dir === 'desc' }]

    const table = useReactTable({
        data: rows,
        columns,
        state: { sorting },
        getRowId: (d) => d.id,
        getCoreRowModel: getCoreRowModel(),
        manualSorting: true,            // server sorts; the table must not re-sort loaded rows
        manualFiltering: true,
        manualPagination: true,
        enableMultiSort: false,
        enableSortingRemoval: false,    // always sorted by something (keeps cursors valid)
        onSortingChange: (updater) => {
            const next = typeof updater === 'function' ? updater(sorting) : updater
            const first = next[0]
            if (!first) return
            updateFilters({ sort: first.id as SortField, dir: first.desc ? 'desc' : 'asc' })
        },
    })

    // ---- virtualization: render only the rows near the viewport ----
    const scrollRef = useRef<HTMLDivElement>(null)
    const virtualizer = useVirtualizer({
        count: rows.length,
        getScrollElement: () => scrollRef.current,
        estimateSize: () => ROW_HEIGHT,
        overscan: 10,
    })
    const virtualItems = virtualizer.getVirtualItems()
    const lastVisibleIndex = virtualItems.length ? virtualItems[virtualItems.length - 1].index : -1

    // Infinite scroll: when the viewport gets near the end of what's loaded, fetch the next cursor page.
    useEffect(() => {
        if (isPlaceholderData || !hasNextPage || isFetchingNextPage) return
        if (lastVisibleIndex >= rows.length - 15) fetchNextPage()
    }, [lastVisibleIndex, rows.length, hasNextPage, isFetchingNextPage, isPlaceholderData, fetchNextPage])

    // New sort/filters = a different result set, so start from the top.
    const filterKey = JSON.stringify(filters)
    useEffect(() => { scrollRef.current?.scrollTo({ top: 0 }) }, [filterKey])

    const template = table.getVisibleLeafColumns().map((c) => `${c.getSize()}px`).join(' ')
    const totalWidth = table.getTotalSize()

    return (
        <>
            <h1>Deliveries</h1>
            <FilterBar filters={filters} onChange={updateFilters} onClear={clearFilters} />

            <div className="grid-status">
                {isPending ? 'Loading…' : `${rows.length.toLocaleString()} loaded${hasNextPage ? ' (scroll for more)' : ''}`}
                {isFetchingNextPage && ' · loading more…'}
            </div>

            {error ? (
                <p className="error">
                    Failed to load deliveries: {error.message}{' '}
                    <button type="button" className="link-btn" onClick={() => refetch()}>Retry</button>
                </p>
            ) : (
                <div ref={scrollRef} className={`grid-scroll ${isPlaceholderData ? 'stale' : ''}`}>
                    <div className="grid-head" style={{ gridTemplateColumns: template, width: totalWidth }}>
                        {table.getHeaderGroups()[0].headers.map((h) => {
                            const sorted = h.column.getIsSorted()
                            return (
                                <div
                                    key={h.id}
                                    className={`grid-cell ${h.column.getCanSort() ? 'sortable' : ''}`}
                                    onClick={h.column.getToggleSortingHandler()}
                                >
                                    {flexRender(h.column.columnDef.header, h.getContext())}
                                    {sorted === 'asc' ? ' ▲' : sorted === 'desc' ? ' ▼' : ''}
                                </div>
                            )
                        })}
                    </div>

                    {!isPending && rows.length === 0 ? (
                        <p className="empty">No deliveries match these filters.</p>
                    ) : (
                        <div style={{ height: virtualizer.getTotalSize(), width: totalWidth, position: 'relative' }}>
                            {virtualItems.map((v) => {
                                const row = table.getRowModel().rows[v.index]
                                if (!row) return null
                                return (
                                    <div
                                        key={row.id}
                                        className="grid-row"
                                        style={{
                                            gridTemplateColumns: template,
                                            height: v.size,
                                            transform: `translateY(${v.start}px)`,
                                        }}
                                        onClick={() => setSelectedId(row.original.id)}
                                    >
                                        {row.getVisibleCells().map((cell) => (
                                            <div key={cell.id} className="grid-cell">
                                                {flexRender(cell.column.columnDef.cell, cell.getContext())}
                                            </div>
                                        ))}
                                    </div>
                                )
                            })}
                        </div>
                    )}
                </div>
            )}

            {selectedId && <DeliveryDrawer id={selectedId} onClose={() => setSelectedId(null)} />}
        </>
    )
}