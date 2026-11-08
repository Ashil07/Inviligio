import { useState } from 'react'
import { Button, ErrorAlert, TextField } from '@/components/ui/form'
import { CheckboxField, Section, SuccessNotice } from '@/components/ui/fields'
import type { Role } from '@/features/auth/api'
import { toApiProblem } from '@/lib/api/client'
import { formatDateTime } from '@/lib/forms'
import {
  invitationLink,
  useAccess,
  useInvite,
  useRevokeInvite,
  useUpdateAccess,
  type AccessDetail,
  type FacultyDetail,
  type IssuedInvitation,
} from './api'

const EXTRA_ROLES: { role: Role; label: string }[] = [
  { role: 'HOD', label: 'Head of department (sees their department)' },
  { role: 'EXAM_CELL_ADMIN', label: 'Exam cell administrator (full access)' },
]

/** Whether and how this faculty member can sign in. Every login also has the Faculty role. */
export function AccessSection({ faculty }: { faculty: FacultyDetail }) {
  const access = useAccess(faculty.id)
  return (
    <Section title="Sign-in access" description="Faculty sign in to see their duties, submit availability and request swaps.">
      {access.error && <ErrorAlert>{toApiProblem(access.error).detail ?? 'Could not load access.'}</ErrorAlert>}
      {access.data && (access.data.status === 'ACTIVE' || access.data.status === 'DISABLED') ? (
        <ManageLogin faculty={faculty} access={access.data} />
      ) : access.data ? (
        <Invite faculty={faculty} access={access.data} />
      ) : null}
    </Section>
  )
}

function RolePicker({ value, onChange, disabled }: { value: Role[]; onChange: (roles: Role[]) => void; disabled?: boolean }) {
  return (
    <div className="space-y-2">
      <CheckboxField label="Faculty (always included)" checked disabled readOnly />
      {EXTRA_ROLES.map(({ role, label }) => (
        <CheckboxField
          key={role}
          label={label}
          checked={value.includes(role)}
          disabled={disabled}
          onChange={(e) => onChange(e.target.checked ? [...value, role] : value.filter((r) => r !== role))}
        />
      ))}
    </div>
  )
}

function Invite({ faculty, access }: { faculty: FacultyDetail; access: AccessDetail }) {
  const invite = useInvite(faculty.id)
  const revoke = useRevokeInvite(faculty.id)
  const [roles, setRoles] = useState<Role[]>(access.invitation?.roles.filter((r) => r !== 'FACULTY') ?? [])
  const [issued, setIssued] = useState<IssuedInvitation | null>(null)
  const [copied, setCopied] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)

  async function create() {
    setProblem(null)
    setCopied(false)
    try {
      setIssued(await invite.mutateAsync(roles))
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not create the invitation.')
    }
  }

  async function cancel() {
    setProblem(null)
    try {
      await revoke.mutateAsync()
      setIssued(null)
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not revoke the invitation.')
    }
  }

  const link = issued ? invitationLink(issued.token) : null

  return (
    <div className="space-y-4">
      {problem && <ErrorAlert>{problem}</ErrorAlert>}
      {!faculty.email && <p className="text-sm text-amber-800">Add an email address above before inviting.</p>}
      {access.status === 'INVITED' && access.invitation && !link && (
        <p className="text-sm text-slate-700">
          Invitation sent to <strong>{access.invitation.email}</strong>, valid until {formatDateTime(access.invitation.expiresAt)}.
          The link is shown only when it is created; create a new one if it was lost.
        </p>
      )}
      {access.status === 'NONE' && <p className="text-sm text-slate-700">No login yet.</p>}
      {link && issued && (
        <div className="space-y-2">
          <SuccessNotice>
            Invitation created. Send this link to {faculty.email}. It works once and expires {formatDateTime(issued.expiresAt)}.
          </SuccessNotice>
          <div className="flex gap-2">
            <div className="flex-1">
              <TextField label="Invitation link" readOnly value={link} onFocus={(e) => e.target.select()} />
            </div>
            <div className="flex items-end">
              <Button
                type="button"
                variant="secondary"
                onClick={() => void navigator.clipboard?.writeText(link).then(() => setCopied(true))}
              >
                {copied ? 'Copied' : 'Copy'}
              </Button>
            </div>
          </div>
        </div>
      )}
      <RolePicker value={roles} onChange={setRoles} />
      <div className="flex gap-2">
        <Button type="button" loading={invite.isPending} disabled={!faculty.email || !faculty.active} onClick={() => void create()}>
          {access.status === 'INVITED' ? 'Create a new link' : 'Create invitation link'}
        </Button>
        {access.status === 'INVITED' && (
          <Button type="button" variant="secondary" loading={revoke.isPending} onClick={() => void cancel()}>
            Revoke invitation
          </Button>
        )}
      </div>
    </div>
  )
}

function ManageLogin({ faculty, access }: { faculty: FacultyDetail; access: AccessDetail }) {
  const update = useUpdateAccess(faculty.id)
  const [roles, setRoles] = useState<Role[]>(access.roles.filter((r) => r !== 'FACULTY'))
  const [active, setActive] = useState(access.status === 'ACTIVE')
  const [problem, setProblem] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  async function save() {
    setProblem(null)
    setSaved(false)
    try {
      await update.mutateAsync({ roles, active })
      setSaved(true)
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'Could not update access.')
    }
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-slate-700">
        Signs in as <strong>{access.loginEmail}</strong>. {access.status === 'DISABLED' && 'Access is currently disabled.'}
      </p>
      {access.currentUser && <p className="text-sm text-amber-800">This is your own login. Another administrator must change it.</p>}
      {saved && <SuccessNotice>Access updated. Changes apply at their next sign-in or within 15 minutes.</SuccessNotice>}
      {problem && <ErrorAlert>{problem}</ErrorAlert>}
      <RolePicker value={roles} onChange={setRoles} disabled={access.currentUser} />
      <CheckboxField label="Can sign in" checked={active} disabled={access.currentUser} onChange={(e) => setActive(e.target.checked)} />
      <Button type="button" loading={update.isPending} disabled={access.currentUser} onClick={() => void save()}>
        Save access
      </Button>
    </div>
  )
}
