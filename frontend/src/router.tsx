import { createRootRoute, createRoute, createRouter, Link, Outlet } from '@tanstack/react-router'
import { Dashboard } from './pages/Dashboard'
import { DeliveriesPage } from './pages/DeliveriesPage'
import { SubscribersPage } from './pages/SubscribersPage'

const rootRoute = createRootRoute({
    component: () => (
        <div className="app">
            <nav>
                <strong>Webhook Engine</strong>
                <Link to="/" activeProps={{ className: 'active' }} activeOptions={{ exact: true }}>Dashboard</Link>
                <Link to="/deliveries" activeProps={{ className: 'active' }}>Deliveries</Link>
                <Link to="/subscribers" activeProps={{ className: 'active' }}>Subscribers</Link>
            </nav>
            <main><Outlet /></main>
        </div>
    ),
})

const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: Dashboard })
const deliveriesRoute = createRoute({ getParentRoute: () => rootRoute, path: '/deliveries', component: DeliveriesPage })
const subscribersRoute = createRoute({ getParentRoute: () => rootRoute, path: '/subscribers', component: SubscribersPage })

const routeTree = rootRoute.addChildren([indexRoute, deliveriesRoute, subscribersRoute])

export const router = createRouter({ routeTree })

// Makes <Link to="..."> type-checked against the routes above.
declare module '@tanstack/react-router' {
    interface Register {
        router: typeof router
    }
}