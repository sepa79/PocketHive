import { useEffect, useState } from 'react'
import {
  listAdminUsers,
  replaceAdminUserGrants,
  upsertAdminUser,
  type AuthGrant,
  type AuthenticatedUser,
} from '../lib/auth'
import { useAuth } from '../lib/authContext'
import {
  AuthProducts,
  AuthServicePermissionIds,
  AuthServiceResourceSelectors,
  AuthServiceResourceTypes,
  PocketHivePermissionIds,
  PocketHiveResourceSelectors,
  PocketHiveResourceTypes,
} from '../lib/authContracts'

type EditableUser = {
  id: string
  username: string
  displayName: string
  active: boolean
}

function toEditableUser(user: AuthenticatedUser): EditableUser {
  return {
    id: user.id,
    username: user.username,
    displayName: user.displayName,
    active: user.active,
  }
}

function cloneGrants(grants: AuthGrant[]) {
  return grants.map((grant) => ({ ...grant }))
}

function createBlankUser(): EditableUser {
  return {
    id: crypto.randomUUID(),
    username: '',
    displayName: '',
    active: true,
  }
}

function createBlankGrant(): AuthGrant {
  return {
    product: AuthProducts.POCKETHIVE,
    permission: PocketHivePermissionIds.VIEW,
    resourceType: PocketHiveResourceTypes.DEPLOYMENT,
    resourceSelector: PocketHiveResourceSelectors.GLOBAL,
  }
}

const grantPresets = [
  {
    id: 'auth-admin',
    label: 'Auth admin',
    grant: {
      product: AuthProducts.AUTH_SERVICE,
      permission: AuthServicePermissionIds.ADMIN,
      resourceType: AuthServiceResourceTypes.GLOBAL,
      resourceSelector: AuthServiceResourceSelectors.GLOBAL,
    },
  },
  {
    id: 'ph-view',
    label: 'PH view all',
    grant: {
      product: AuthProducts.POCKETHIVE,
      permission: PocketHivePermissionIds.VIEW,
      resourceType: PocketHiveResourceTypes.DEPLOYMENT,
      resourceSelector: PocketHiveResourceSelectors.GLOBAL,
    },
  },
  {
    id: 'ph-run-folder',
    label: 'PH run folder',
    grant: {
      product: AuthProducts.POCKETHIVE,
      permission: PocketHivePermissionIds.RUN,
      resourceType: PocketHiveResourceTypes.FOLDER,
      resourceSelector: 'demo',
    },
  },
  {
    id: 'ph-all',
    label: 'PH all',
    grant: {
      product: AuthProducts.POCKETHIVE,
      permission: PocketHivePermissionIds.ALL,
      resourceType: PocketHiveResourceTypes.DEPLOYMENT,
      resourceSelector: PocketHiveResourceSelectors.GLOBAL,
    },
  },
]

export function UsersPage() {
  const auth = useAuth()
  const [loading, setLoading] = useState(true)
  const [savingUser, setSavingUser] = useState(false)
  const [savingGrants, setSavingGrants] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [users, setUsers] = useState<AuthenticatedUser[]>([])
  const [selectedUserId, setSelectedUserId] = useState<string | null>(null)
  const [draftUser, setDraftUser] = useState<EditableUser | null>(null)
  const [draftGrants, setDraftGrants] = useState<AuthGrant[]>([])

  async function reloadUsers() {
    try {
      setLoading(true)
      setError(null)
      const loadedUsers = await listAdminUsers()
      setUsers(loadedUsers)
      const nextSelectedUser = loadedUsers.find((entry) => entry.id === selectedUserId) ?? loadedUsers[0] ?? null
      setSelectedUserId(nextSelectedUser?.id ?? null)
      setDraftUser(nextSelectedUser ? toEditableUser(nextSelectedUser) : null)
      setDraftGrants(nextSelectedUser ? cloneGrants(nextSelectedUser.grants) : [])
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (!auth.isAuthAdmin) {
      setLoading(false)
      return
    }

    let cancelled = false

    async function load() {
      try {
        await reloadUsers()
        if (cancelled) return
      } catch (e) {
        if (cancelled) return
        setError(e instanceof Error ? e.message : 'Failed to load users')
      }
    }

    void load()

    return () => {
      cancelled = true
    }
  }, [auth.isAuthAdmin])

  function selectUser(user: AuthenticatedUser) {
    setSelectedUserId(user.id)
    setDraftUser(toEditableUser(user))
    setDraftGrants(cloneGrants(user.grants))
    setError(null)
    setMessage(null)
  }

  function startNewUser() {
    setSelectedUserId(null)
    setDraftUser(createBlankUser())
    setDraftGrants([])
    setError(null)
    setMessage(null)
  }

  function patchCurrentUser(updated: AuthenticatedUser) {
    setUsers((current) => {
      const withoutCurrent = current.filter((entry) => entry.id !== updated.id)
      const next = [...withoutCurrent, updated]
      next.sort((left, right) => left.username.localeCompare(right.username))
      return next
    })
    setSelectedUserId(updated.id)
    setDraftUser(toEditableUser(updated))
    setDraftGrants(cloneGrants(updated.grants))
    if (auth.user?.id === updated.id) {
      void auth.refresh()
    }
  }

  async function saveUser() {
    if (!draftUser) return
    try {
      setSavingUser(true)
      setError(null)
      setMessage(null)
      const saved = await upsertAdminUser(draftUser.id, {
        username: draftUser.username,
        displayName: draftUser.displayName,
        active: draftUser.active,
      })
      patchCurrentUser(saved)
      setMessage(`Saved user '${saved.username}'.`)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save user')
    } finally {
      setSavingUser(false)
    }
  }

  async function saveGrants() {
    if (!draftUser) return
    try {
      setSavingGrants(true)
      setError(null)
      setMessage(null)
      const saved = await replaceAdminUserGrants(draftUser.id, { grants: draftGrants })
      patchCurrentUser(saved)
      setMessage(`Saved grants for '${saved.username}'.`)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save grants')
    } finally {
      setSavingGrants(false)
    }
  }

  if (auth.status === 'loading') {
    return <div className="page">Resolving session…</div>
  }

  if (auth.status !== 'authenticated') {
    return (
      <div className="page">
        <h1 className="h1">Users</h1>
        <div className="card" style={{ marginTop: 12 }}>
          <div className="muted">Sign in first. This page requires an authenticated auth-service admin.</div>
        </div>
      </div>
    )
  }

  if (!auth.isAuthAdmin) {
    return (
      <div className="page">
        <h1 className="h1">Users</h1>
        <div className="card" style={{ marginTop: 12 }}>
          <div className="warningText">Auth admin permission required.</div>
          <div className="muted" style={{ marginTop: 8 }}>
            Current user can use PocketHive, but may not manage auth-service users or grants.
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="page usersPage">
      <div className="usersPageHeader">
        <div>
          <h1 className="h1">Users</h1>
          <div className="muted">Manage shared auth-service users and grant assignments.</div>
        </div>
        <div className="usersPageHeaderActions">
          <button type="button" className="btnSecondary" onClick={startNewUser}>
            New user
          </button>
          <button
            type="button"
            className="btnSecondary"
            onClick={() => void reloadUsers()}
          >
            Reload
          </button>
        </div>
      </div>

      {error ? (
        <div className="card usersPageBanner usersPageBannerError" role="alert">
          {error}
        </div>
      ) : null}
      {message ? <div className="card usersPageBanner usersPageBannerInfo">{message}</div> : null}

      <div className="usersWorkspace">
        <div className="card usersSidebar">
          <div className="usersSidebarHeader">
            <div className="h2">Directory</div>
            <div className="muted">{loading ? 'Loading…' : `${users.length} users`}</div>
          </div>
          <div className="usersList">
            {users.map((user) => (
              <button
                key={user.id}
                type="button"
                className={user.id === selectedUserId ? 'usersListItem usersListItemActive' : 'usersListItem'}
                onClick={() => selectUser(user)}
              >
                <div className="usersListTitle">{user.displayName}</div>
                <div className="usersListMeta">
                  {user.username} · {user.active ? 'active' : 'inactive'}
                </div>
              </button>
            ))}
            {!loading && users.length === 0 ? <div className="muted">No users configured.</div> : null}
          </div>
        </div>

        <div className="usersEditorColumn">
          <div className="card">
            <div className="usersSectionHeader">
              <div>
                <div className="h2">User</div>
                <div className="muted">Basic identity metadata. Save this before assigning grants to a new user.</div>
              </div>
              <button type="button" className="btnPrimary" disabled={!draftUser || savingUser} onClick={() => void saveUser()}>
                {savingUser ? 'Saving…' : 'Save user'}
              </button>
            </div>

            {draftUser ? (
              <div className="formGrid" style={{ marginTop: 12 }}>
                <label className="field">
                  <span className="fieldLabel">User ID</span>
                  <input className="textInput" value={draftUser.id} readOnly />
                </label>
                <label className="field">
                  <span className="fieldLabel">Active</span>
                  <select
                    className="textInput"
                    value={draftUser.active ? 'true' : 'false'}
                    onChange={(event) => {
                      const active = event.target.value === 'true'
                      setDraftUser((current) => (current ? { ...current, active } : current))
                    }}
                  >
                    <option value="true">active</option>
                    <option value="false">inactive</option>
                  </select>
                </label>
                <label className="field">
                  <span className="fieldLabel">Username</span>
                  <input
                    className="textInput"
                    value={draftUser.username}
                    onChange={(event) =>
                      setDraftUser((current) => (current ? { ...current, username: event.target.value } : current))
                    }
                  />
                </label>
                <label className="field">
                  <span className="fieldLabel">Display name</span>
                  <input
                    className="textInput"
                    value={draftUser.displayName}
                    onChange={(event) =>
                      setDraftUser((current) => (current ? { ...current, displayName: event.target.value } : current))
                    }
                  />
                </label>
              </div>
            ) : (
              <div className="muted" style={{ marginTop: 10 }}>
                Select a user or create a new one.
              </div>
            )}
          </div>

          <div className="card" style={{ marginTop: 12 }}>
            <div className="usersSectionHeader">
              <div>
                <div className="h2">Grants</div>
                <div className="muted">MVP shape: product + permission + resource type + selector.</div>
              </div>
              <button
                type="button"
                className="btnPrimary"
                disabled={!draftUser || savingGrants}
                onClick={() => void saveGrants()}
              >
                {savingGrants ? 'Saving…' : 'Save grants'}
              </button>
            </div>

            <div className="usersPresetRow">
              {grantPresets.map((preset) => (
                <button
                  key={preset.id}
                  type="button"
                  className="btnSecondary btnSecondaryCompact"
                  disabled={!draftUser}
                  onClick={() => setDraftGrants((current) => [...current, { ...preset.grant }])}
                >
                  {preset.label}
                </button>
              ))}
              <button
                type="button"
                className="btnSecondary btnSecondaryCompact"
                disabled={!draftUser}
                onClick={() => setDraftGrants((current) => [...current, createBlankGrant()])}
              >
                Add blank grant
              </button>
            </div>

            <div className="usersGrantTable">
              <div className="usersGrantHead">
                <span>Product</span>
                <span>Permission</span>
                <span>Resource type</span>
                <span>Selector</span>
                <span />
              </div>
              {draftGrants.map((grant, index) => (
                <div key={`${grant.product}:${grant.permission}:${index}`} className="usersGrantRow">
                  <select
                    className="textInput"
                    value={grant.product}
                    onChange={(event) =>
                      setDraftGrants((current) =>
                        current.map((entry, entryIndex) =>
                          entryIndex === index ? { ...entry, product: event.target.value } : entry,
                        ),
                      )
                    }
                  >
                    <option value={AuthProducts.AUTH_SERVICE}>{AuthProducts.AUTH_SERVICE}</option>
                    <option value={AuthProducts.POCKETHIVE}>{AuthProducts.POCKETHIVE}</option>
                    <option value={AuthProducts.HIVEWATCH}>{AuthProducts.HIVEWATCH}</option>
                  </select>
                  <input
                    className="textInput"
                    value={grant.permission}
                    onChange={(event) =>
                      setDraftGrants((current) =>
                        current.map((entry, entryIndex) =>
                          entryIndex === index ? { ...entry, permission: event.target.value } : entry,
                        ),
                      )
                    }
                  />
                  <input
                    className="textInput"
                    value={grant.resourceType}
                    onChange={(event) =>
                      setDraftGrants((current) =>
                        current.map((entry, entryIndex) =>
                          entryIndex === index ? { ...entry, resourceType: event.target.value } : entry,
                        ),
                      )
                    }
                  />
                  <input
                    className="textInput"
                    value={grant.resourceSelector}
                    onChange={(event) =>
                      setDraftGrants((current) =>
                        current.map((entry, entryIndex) =>
                          entryIndex === index ? { ...entry, resourceSelector: event.target.value } : entry,
                        ),
                      )
                    }
                  />
                  <button
                    type="button"
                    className="btnGhostDanger"
                    onClick={() =>
                      setDraftGrants((current) => current.filter((_, entryIndex) => entryIndex !== index))
                    }
                  >
                    Remove
                  </button>
                </div>
              ))}
              {draftUser && draftGrants.length === 0 ? (
                <div className="muted" style={{ marginTop: 10 }}>
                  No grants assigned.
                </div>
              ) : null}
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
