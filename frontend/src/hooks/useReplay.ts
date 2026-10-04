import { useMutation, useQueryClient } from '@tanstack/react-query'
import { replayDelivery } from '../api/client'

export function useReplay() {
    const qc = useQueryClient()
    return useMutation({
        mutationFn: (id: string) => replayDelivery(id),
        onSuccess: () => {
            // A replayed row leaves the DLQ, so refetch rather than patch: it may no longer match the filter.
            qc.invalidateQueries({ queryKey: ['deliveries'] })
            qc.invalidateQueries({ queryKey: ['stats'] })
        },
    })
}