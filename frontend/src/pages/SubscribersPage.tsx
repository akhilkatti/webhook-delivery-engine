import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createSubscriber, fetchSubscribers, updateSubscriber } from '../api/client'
import type { CreatedSubscriber, Subscriber } from '../api/types'
import { BreakerBadge } from '../components/BreakerBadge'

export function SubscribersPage() {
    const qc = useQueryClient()
    const { data, isPending, error } = useQuery({
        queryKey: ['subscribers'],
        queryFn: fetchSubscribers,
        refetchInterval: 5_000,
    })

    // The secret lives only in component state: never cached, never persisted.
    const [created, setCreated] = useState<CreatedSubscriber | null>(null)
    const [copied, setCopied] = useState(false)

    const [name, setName] = useState('')
    const [url, setUrl] = useState('')
    const [limit, setLimit] = useState('60')

    const create = useMutation({
        mutationFn: createSubscriber,
        onSuccess: (sub) => {
            setCreated(sub)
            setCopied(false)
            setName(''); setUrl(''); setLimit('60')
            qc.invalidateQueries({ queryKey: ['subscribers'] })
        },
    })

    const update = useMutation({
        mutationFn: ({ id, ...body }: { id: string; rateLimitPerMin?: number; active?: boolean }) =>
            updateSubscriber(id, body),
        onSuccess: () => qc.invalidateQueries({ queryKey: ['subscribers'] }),
    })

    const submit = (e: React.SyntheticEvent) => {
        e.preventDefault()
        create.mutate({ name: name.trim(), url: url.trim(), rateLimitPerMin: Number(limit) })
    }

    const limitValid = Number.isInteger(Number(limit)) && Number(limit) >= 1 && Number(limit) <= 100000
    const urlValid = /^https?:\/\/.+/.test(url.trim())
    const canSubmit = name.trim() !== '' && urlValid && limitValid && !create.isPending

    const copySecret = async () => {
        if (!created) return
        try {
            await navigator.clipboard.writeText(created.secret)
            setCopied(true)
        } catch {
            // clipboard blocked (non-secure context): the secret is still selectable on screen
        }
    }

    return (
        <>
            <h1>Subscribers</h1>

            {created && (
                <div className="secret-banner" role="alert">
                    <strong>Subscriber “{created.name}” created. Copy its signing secret now.</strong>
                    <p>It is shown only once and can’t be retrieved later. Receivers use it to verify <code>X-Webhook-Signature</code>.</p>
                    <code className="secret">{created.secret}</code>
                    <div className="row">
                        <button type="button" className="primary" onClick={copySecret}>{copied ? 'Copied ✓' : 'Copy secret'}</button>
                        <button type="button" className="link-btn" onClick={() => setCreated(null)}>I’ve saved it, dismiss</button>
                    </div>
                </div>
            )}

            <form className="panel create-form" onSubmit={submit}>
                <h2>Add subscriber</h2>
                <div className="form-row">
                    <label>Name
                        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Acme Orders" />
                    </label>
                    <label className="grow">Endpoint URL
                        <input value={url} onChange={(e) => setUrl(e.target.value)}
                               placeholder="https://example.com/webhooks  (or http://localhost:8080/mock/demo)" />
                    </label>
                    <label>Limit / min
                        <input value={limit} inputMode="numeric" onChange={(e) => setLimit(e.target.value.replace(/\D/g, ''))} />
                    </label>
                    <button type="submit" className="primary" disabled={!canSubmit}>
                        {create.isPending ? 'Creating…' : 'Create'}
                    </button>
                </div>
                {url !== '' && !urlValid && <p className="error">URL must start with http:// or https://</p>}
                {create.error && <p className="error">Could not create: {create.error.message}</p>}
            </form>

            {isPending && <p>Loading…</p>}
            {error && <p className="error">Failed to load subscribers: {error.message}</p>}
            {update.error && <p className="error">Update failed: {update.error.message}</p>}

            {data && (
                <table className="simple">
                    <thead>
                    <tr><th>Name</th><th>URL</th><th>Limit / min</th><th>Breaker</th><th>Active</th></tr>
                    </thead>
                    <tbody>
                    {data.map((s) => (
                        <tr key={s.id} className={s.active ? '' : 'inactive-row'}>
                            <td>{s.name}</td>
                            <td className="mono url-cell" title={s.url}>{s.url}</td>
                            <td>
                                <LimitCell sub={s} busy={update.isPending}
                                           onCommit={(n) => update.mutate({ id: s.id, rateLimitPerMin: n })} />
                            </td>
                            <td><BreakerBadge state={s.breakerState} failureRate={s.failureRate} /></td>
                            <td>
                                <button type="button" className={`toggle ${s.active ? 'on' : ''}`} disabled={update.isPending}
                                        onClick={() => update.mutate({ id: s.id, active: !s.active })}
                                        aria-pressed={s.active} aria-label={`${s.active ? 'Deactivate' : 'Activate'} ${s.name}`}>
                                    {s.active ? 'Active' : 'Inactive'}
                                </button>
                            </td>
                        </tr>
                    ))}
                    </tbody>
                </table>
            )}
        </>
    )
}

/** Edits in place; saves on blur or Enter, only if the value is valid and changed. */
function LimitCell({ sub, busy, onCommit }: { sub: Subscriber; busy: boolean; onCommit: (n: number) => void }) {
    const [text, setText] = useState(String(sub.rateLimitPerMin))
    const n = Number(text)
    const valid = Number.isInteger(n) && n >= 1 && n <= 100000

    const commit = () => {
        if (!valid) { setText(String(sub.rateLimitPerMin)); return }   // revert bad input
        if (n !== sub.rateLimitPerMin) onCommit(n)
    }

    return (
        <input
            className="limit-input"
            value={text}
            disabled={busy}
            inputMode="numeric"
            aria-label={`Rate limit for ${sub.name}`}
            onChange={(e) => setText(e.target.value.replace(/\D/g, ''))}
            onBlur={commit}
            onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur() }}
        />
    )
}