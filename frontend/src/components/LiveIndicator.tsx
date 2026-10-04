import type { StreamStatus } from '../hooks/useDeliveryStream'

const LABEL: Record<StreamStatus, string> = {
    connecting: 'Connecting…',
    live: 'Live',
    reconnecting: 'Reconnecting…',
}

export function LiveIndicator({ status }: { status: StreamStatus }) {
    return (
        <span className={`live live-${status}`} title="Server-Sent Events connection">
      <span className="dot" />
            {LABEL[status]}
    </span>
    )
}