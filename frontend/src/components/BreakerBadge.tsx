import type { BreakerState } from '../api/types'

const LABEL: Partial<Record<BreakerState, string>> = {
    CLOSED: 'Healthy',
    OPEN: 'Open (paused)',
    HALF_OPEN: 'Probing',
}

export function BreakerBadge({ state, failureRate }: { state: BreakerState; failureRate: number }) {
    const rate = failureRate < 0 ? '' : ` · ${failureRate.toFixed(0)}% failing`
    return (
        <span className={`badge breaker-${state}`} title={`Circuit breaker: ${state}`}>
      {(LABEL[state] ?? state) + rate}
    </span>
    )
}