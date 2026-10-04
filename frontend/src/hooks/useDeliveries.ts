import { keepPreviousData, useInfiniteQuery } from '@tanstack/react-query'
import { fetchDeliveries } from '../api/client'
import type { DeliveryFilters } from '../api/types'

export function useDeliveries(filters: DeliveryFilters) {
    return useInfiniteQuery({
        // 'list' vs 'detail' lets us target the grid's cache precisely in Block 3 (SSE patching).
        queryKey: ['deliveries', 'list', filters],
        queryFn: ({ pageParam }) => fetchDeliveries(filters, pageParam),
        initialPageParam: null as string | null,        // first page has no cursor
        getNextPageParam: (last) => last.nextCursor,    // null => no more pages
        placeholderData: keepPreviousData,              // keep old rows on screen while new filters load
    })
}