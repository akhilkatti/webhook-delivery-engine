import { createRootRoute, createRoute, createRouter, Link, Outlet } from '@tanstack/react-router'
import { Dashboard } from './pages/Dashboard'
import { DeliveriesPage } from './pages/DeliveriesPage'
import { SubscribersPage } from './pages/SubscribersPage'
import type { DeliveryFilters, DeliveryStatus } from './api/types'
import { ALL_STATUSES } from './api/types'

const rootRoute = createRootRoute({
    component: () => (
        <div className="app">
            <nav>
                <strong>Webhook Engine</strong>
                <Link to="/" activeProps={{ className: 'active' }} activeOptions={{ exact: true }}>Dashboard</Link>
                <Link to="/deliveries" search={{ sort: 'createdAt', dir: 'desc' }}  activeProps={{ className: 'active' }}>Deliveries</Link>
                <Link to="/subscribers" activeProps={{ className: 'active' }}>Subscribers</Link>
            </nav>
            <main><Outlet /></main>
        </div>
    ),
})

const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: Dashboard })
const STATUS_SET = new Set<string>(ALL_STATUSES)

/** Turns whatever is in the URL into a safe DeliveryFilters. Garbage in the URL can't crash the page. */
function validateDeliverySearch(raw: Record<string, unknown>): DeliveryFilters {
    const status = Array.isArray(raw.status)
        ? raw.status.filter((s): s is DeliveryStatus => typeof s === 'string' && STATUS_SET.has(s))
        : []
    return {
        status: status.length ? status : undefined,
        subscriberId: typeof raw.subscriberId === 'string' && raw.subscriberId ? raw.subscriberId : undefined,
        httpStatus: typeof raw.httpStatus === 'number' && Number.isInteger(raw.httpStatus) ? raw.httpStatus : undefined,
        from: typeof raw.from === 'string' && raw.from ? raw.from : undefined,
        to: typeof raw.to === 'string' && raw.to ? raw.to : undefined,
        sort: raw.sort === 'attemptCount' || raw.sort === 'latencyMs' ? raw.sort : 'createdAt',
        dir: raw.dir === 'asc' ? 'asc' : 'desc',
    }
}
const deliveriesRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/deliveries',
    validateSearch: validateDeliverySearch,
    component: DeliveriesPage,
})
const subscribersRoute = createRoute({ getParentRoute: () => rootRoute, path: '/subscribers', component: SubscribersPage })

const routeTree = rootRoute.addChildren([indexRoute, deliveriesRoute, subscribersRoute])

export const router = createRouter({ routeTree })

// Makes <Link to="..."> type-checked against the routes above.
declare module '@tanstack/react-router' {
    interface Register {
        router: typeof router
    }
}