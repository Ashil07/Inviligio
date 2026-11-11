import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { Link, useNavigate } from 'react-router'
import { z } from 'zod'
import { Button, ErrorAlert, TextField } from '@/components/ui/form'
import { toApiProblem } from '@/lib/api/client'
import { registerCollege } from './api'
import { AuthCard } from './AuthCard'
import { useAuth } from './useAuth'

const schema = z
  .object({
    collegeName: z.string().trim().min(1, 'College name is required').max(200),
    collegeCode: z
      .string()
      .trim()
      .regex(/^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$/, '2-32 letters, digits, "-" or "_"'),
    adminFullName: z.string().trim().min(1, 'Your name is required').max(200),
    adminEmail: z.string().trim().min(1, 'Email is required').pipe(z.email('Enter a valid email address')),
    password: z.string().min(8, 'At least 8 characters').max(72, 'At most 72 characters'),
    confirmPassword: z.string(),
  })
  .refine((v) => v.password === v.confirmPassword, {
    path: ['confirmPassword'],
    message: 'Passwords do not match',
  })
type FormValues = z.infer<typeof schema>

const FIELDS = ['collegeName', 'collegeCode', 'adminFullName', 'adminEmail', 'password'] as const

export function RegisterCollegePage() {
  const { signIn } = useAuth()
  const navigate = useNavigate()
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { collegeName: '', collegeCode: '', adminFullName: '', adminEmail: '', password: '', confirmPassword: '' },
  })
  const mutation = useMutation({ mutationFn: registerCollege })
  const problem = mutation.error ? toApiProblem(mutation.error) : null
  const errors = form.formState.errors

  const onSubmit = form.handleSubmit(async ({ confirmPassword: _confirm, ...request }) => {
    try {
      const auth = await mutation.mutateAsync(request)
      signIn(auth)
      navigate('/', { replace: true })
    } catch (error) {
      // Show server-side validation next to the matching field.
      const fieldErrors = toApiProblem(error).fieldErrors ?? {}
      for (const field of FIELDS) {
        const message = fieldErrors[field]
        if (message) form.setError(field, { message })
      }
    }
  })

  return (
    <AuthCard
      title="Register your college"
      subtitle="Create the examination cell account. You will configure policies, departments and faculty next."
      footer={
        <>
          Already registered?{' '}
          <Link to="/login" className="font-medium link">
            Sign in
          </Link>
        </>
      }
    >
      <form onSubmit={(e) => void onSubmit(e)} noValidate className="space-y-4">
        {problem && !problem.fieldErrors && <ErrorAlert>{problem.detail ?? 'Registration failed.'}</ErrorAlert>}
        <TextField label="College name" error={errors.collegeName?.message} {...form.register('collegeName')} />
        <TextField
          label="College code"
          hint="Short unique code, e.g. GEC-TCR. Stored in capitals."
          className="uppercase"
          error={errors.collegeCode?.message}
          {...form.register('collegeCode')}
        />
        <TextField label="Your full name" autoComplete="name" error={errors.adminFullName?.message} {...form.register('adminFullName')} />
        <TextField label="Email" type="email" autoComplete="username" error={errors.adminEmail?.message} {...form.register('adminEmail')} />
        <TextField label="Password" type="password" autoComplete="new-password" error={errors.password?.message} {...form.register('password')} />
        <TextField
          label="Confirm password"
          type="password"
          autoComplete="new-password"
          error={errors.confirmPassword?.message}
          {...form.register('confirmPassword')}
        />
        <Button type="submit" loading={mutation.isPending} className="w-full">
          Create college account
        </Button>
      </form>
    </AuthCard>
  )
}
