export const ALL_STATUSES = ['PENDING', 'IN_FLIGHT', 'RETRYING', 'SUCCESS', 'DLQ'] as const
export type DeliveryStatus = (typeof ALL_STATUSES)[number]

export type SortField = 'createdAt' | 'attemptCount' | 'latencyMs'
export type SortDir = 'asc' | 'desc'

export interface Delivery {
    id: string
    eventId: string
    subscriberId: string
    subscriberName: string
    status: DeliveryStatus
    attemptCount: number
    nextAttemptAt: string | null
    lastHttpStatus: number | null
    lastError: string | null
    latencyMs: number | null
    createdAt: string
    updatedAt: string
}

export interface DeliveryPage {
    items: Delivery[]
    nextCursor: string | null
    hasMore: boolean
}

export interface DeliveryDetail {
    delivery: Delivery
    eventType: string
    payload: string
}

export interface DeliveryFilters {
    status?: DeliveryStatus[]
    subscriberId?: string
    httpStatus?: number
    from?: string // ISO-8601
    to?: string
    sort: SortField
    dir: SortDir
}

export type BreakerState = 'CLOSED' | 'OPEN' | 'HALF_OPEN' | 'DISABLED' | 'FORCED_OPEN' | 'METRICS_ONLY'

export interface Subscriber {
    id: string
    name: string
    url: string
    rateLimitPerMin: number
    active: boolean
    createdAt: string
    breakerState: BreakerState
    failureRate: number // -1 until enough calls were recorded
}

export interface CreatedSubscriber {
    id: string
    name: string
    url: string
    rateLimitPerMin: number
    active: boolean
    createdAt: string
    secret: string // shown once
}

export interface Stats {
    windowHours: number
    total: number
    byStatus: Record<DeliveryStatus, number>
    successRate: number | null
    p95LatencyMs: number | null
    dlqTotal: number
}

/** Shape of each SSE "delivery" message. */
export interface DeliveryChangedEvent {
    id: string
    subscriberId: string
    status: DeliveryStatus
    attemptCount: number
    lastHttpStatus: number | null
    lastError: string | null
    latencyMs: number | null
    updatedAt: string
}