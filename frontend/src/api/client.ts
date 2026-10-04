import type {
    CreatedSubscriber, DeliveryDetail, DeliveryFilters, DeliveryPage, Stats, Subscriber,
} from './types'

export class ApiError extends Error {
    status: number
    constructor(status: number, message: string) {
        super(message)
        this.status = status
    }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
    const res = await fetch(path, {
        ...init,
        headers: { 'Content-Type': 'application/json', ...init?.headers },
    })
    if (!res.ok) {
        let message = res.statusText
        try {
            const body = await res.json()
            message = body.message ?? body.detail ?? message
        } catch {
            // body wasn't JSON; keep statusText
        }
        throw new ApiError(res.status, message)
    }
    if (res.status === 204) return undefined as T
    return (await res.json()) as T
}

export function fetchDeliveries(
    filters: DeliveryFilters,
    cursor?: string | null,
    limit = 50,
): Promise<DeliveryPage> {
    const p = new URLSearchParams()
    if (filters.status?.length) p.set('status', filters.status.join(','))
    if (filters.subscriberId) p.set('subscriberId', filters.subscriberId)
    if (filters.httpStatus != null) p.set('httpStatus', String(filters.httpStatus))
    if (filters.from) p.set('from', filters.from)
    if (filters.to) p.set('to', filters.to)
    p.set('sort', filters.sort)
    p.set('dir', filters.dir)
    p.set('limit', String(limit))
    if (cursor) p.set('cursor', cursor)
    return request<DeliveryPage>(`/api/deliveries?${p}`)
}

export const fetchDelivery = (id: string) => request<DeliveryDetail>(`/api/deliveries/${id}`)

export const replayDelivery = (id: string) =>
    request<{ id: string; status: string }>(`/api/deliveries/${id}/replay`, { method: 'POST' })

export const fetchSubscribers = () => request<Subscriber[]>('/api/subscribers')

export const createSubscriber = (body: { name: string; url: string; rateLimitPerMin?: number }) =>
    request<CreatedSubscriber>('/api/subscribers', { method: 'POST', body: JSON.stringify(body) })

export const fetchStats = (hours = 24) => request<Stats>(`/api/stats?hours=${hours}`)