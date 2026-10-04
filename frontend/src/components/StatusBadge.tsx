import type { DeliveryStatus } from '../api/types'

export function StatusBadge({ status }: { status: DeliveryStatus }) {
    return <span className={`badge badge-${status}`}>{status.replace('_', ' ')}</span>
}