import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { z } from 'zod'
import { Button, ErrorAlert, TextField } from '@/components/ui/form'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { api, toApiProblem } from '@/lib/api/client'
import { formatDateTime } from '@/lib/forms'
import { ROLE_LABEL, type AuthResponse, type Role } from './api'
import { AuthCard } from './AuthCard'
import { useAuth } from './useAuth'

interface InvitationPreview {
  collegeName: string
  collegeCode: string
  facultyName: string
  email: string
  roles: Role[]
  expiresAt: string
  existingAccount: boolean
}

const newAccountSchema = z
  .object({
    fullName: z.string().trim().min(1, 'Your name is required').max(200),
    password: z.string().min(8, 'At least 8 characters').max(72, 'At most 72 characters'),
    confirmPassword: z.string(),
  })
  .refine((v) => v.password === v.confirmPassword, { path: ['confirmPassword'], message: 'Passwords do not match' })

const existingAccountSchema = z.object({
  fullName: z.string(),
  password: z.string().min(1, 'Enter your current password'),
  confirmPassword: z.string(),
})

type FormValues = z.infer<typeof newAccountSchema>

/** Public page opened from an invitation link: `/accept-invite?token=...`. */
export function AcceptInvitePage() {
  const token = useSearchParams()[0].get('token') ?? ''
  const preview = useQuery({
    queryKey: ['invitation', token],
    queryFn: async () => (await api.post<InvitationPreview>('/auth/invitations/preview', { token })).data,
    enabled: token.length > 0,
    retry: false,
  })

  if (!token || preview.error) {
    return (
      <AuthCard title="Invitation not valid">
        <ErrorAlert>
          {(preview.error && toApiProblem(preview.error).detail) ??
            'This invitation link is invalid or has expired. Ask your examination cell for a new one.'}
        </ErrorAlert>
        <p className="mt-4 text-sm">
          <Link to="/login" className="font-medium text-indigo-700 hover:underline">Go to sign in</Link>
        </p>
      </AuthCard>
    )
  }
  if (!preview.data) return <FullPageSpinner label="Checking invitation" />
  return <AcceptForm token={token} invitation={preview.data} />
}

function AcceptForm({ token, invitation }: { token: string; invitation: InvitationPreview }) {
  const { signIn } = useAuth()
  const navigate = useNavigate()
  const form = useForm<FormValues>({
    resolver: zodResolver(invitation.existingAccount ? existingAccountSchema : newAccountSchema),
    defaultValues: { fullName: invitation.facultyName, password: '', confirmPassword: '' },
  })
  const accept = useMutation({
    mutationFn: async (body: { token: string; password: string; fullName?: string }) =>
      (await api.post<AuthResponse>('/auth/invitations/accept', body)).data,
  })
  const problem = accept.error ? toApiProblem(accept.error) : null
  const e = form.formState.errors

  const onSubmit = form.handleSubmit(async ({ fullName, password }) => {
    try {
      const auth = await accept.mutateAsync({ token, password, fullName: invitation.existingAccount ? undefined : fullName })
      signIn(auth)
      navigate('/', { replace: true })
    } catch {
      // shown from accept.error
    }
  })

  const roleNames = invitation.roles.map((r) => ROLE_LABEL[r]).join(', ')

  return (
    <AuthCard
      title={`Join ${invitation.collegeName}`}
      subtitle={`You have been invited as ${roleNames}. The invitation is valid until ${formatDateTime(invitation.expiresAt)}.`}
    >
      <form onSubmit={(ev) => void onSubmit(ev)} noValidate className="space-y-4">
        {problem && <ErrorAlert>{problem.detail ?? 'Could not accept the invitation.'}</ErrorAlert>}
        <TextField label="Email" value={invitation.email} readOnly disabled />
        {invitation.existingAccount ? (
          <>
            <p className="text-sm text-slate-600">
              You already have an Invigilo account with this email. Enter its password to add {invitation.collegeCode} to it.
            </p>
            <TextField label="Password" type="password" autoComplete="current-password" error={e.password?.message} {...form.register('password')} />
          </>
        ) : (
          <>
            <TextField label="Your full name" autoComplete="name" error={e.fullName?.message} {...form.register('fullName')} />
            <TextField label="Choose a password" type="password" autoComplete="new-password" error={e.password?.message} {...form.register('password')} />
            <TextField
              label="Confirm password"
              type="password"
              autoComplete="new-password"
              error={e.confirmPassword?.message}
              {...form.register('confirmPassword')}
            />
          </>
        )}
        <Button type="submit" loading={accept.isPending} className="w-full">
          {invitation.existingAccount ? 'Link and sign in' : 'Create account and sign in'}
        </Button>
      </form>
    </AuthCard>
  )
}
