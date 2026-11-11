import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useLocation, useNavigate } from 'react-router'
import { z } from 'zod'
import { Button, ErrorAlert, TextField } from '@/components/ui/form'
import { toApiProblem } from '@/lib/api/client'
import { login, type CollegeOption } from './api'
import { AuthCard } from './AuthCard'
import { useAuth } from './useAuth'

const schema = z.object({
  email: z.string().trim().min(1, 'Email is required').pipe(z.email('Enter a valid email address')),
  password: z.string().min(1, 'Password is required'),
})
type FormValues = z.infer<typeof schema>

export function LoginPage() {
  const { signIn } = useAuth()
  const navigate = useNavigate()
  const from = (useLocation().state as { from?: string } | null)?.from
  const [colleges, setColleges] = useState<CollegeOption[] | null>(null)
  const [collegeId, setCollegeId] = useState('')

  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: { email: '', password: '' } })
  const mutation = useMutation({ mutationFn: login })
  const problem = mutation.error ? toApiProblem(mutation.error) : null

  const onSubmit = form.handleSubmit(async (values) => {
    let result
    try {
      result = await mutation.mutateAsync({ ...values, collegeId: colleges ? collegeId : undefined })
    } catch {
      return // shown from mutation.error
    }
    if (result.status === 'AUTHENTICATED') {
      signIn(result.auth)
      navigate(from && from !== '/login' ? from : '/', { replace: true })
    } else {
      setColleges(result.colleges)
      setCollegeId(result.colleges[0]?.id ?? '')
    }
  })

  return (
    <AuthCard
      title={colleges ? 'Choose a college' : 'Sign in'}
      subtitle={colleges ? 'Your account has access to more than one college.' : undefined}
      footer={
        <>
          New college?{' '}
          <Link to="/register" className="font-medium link">
            Register your examination cell
          </Link>
        </>
      }
    >
      <form onSubmit={(e) => void onSubmit(e)} noValidate className="space-y-4">
        {problem && <ErrorAlert>{problem.detail ?? 'Sign-in failed. Please try again.'}</ErrorAlert>}

        {colleges ? (
          <fieldset className="space-y-2">
            <legend className="sr-only">College</legend>
            {colleges.map((c) => (
              <label
                key={c.id}
                className="flex cursor-pointer items-center gap-3 rounded-md border border-neutral-200 p-3 hover:bg-neutral-50 has-checked:border-primary-500 has-checked:bg-primary-50"
              >
                <input
                  type="radio"
                  name="college"
                  value={c.id}
                  checked={collegeId === c.id}
                  onChange={() => setCollegeId(c.id)}
                  className="accent-primary-600"
                />
                <span className="text-sm">
                  <span className="font-medium text-neutral-900">{c.name}</span>
                  <span className="ml-2 text-neutral-500">{c.code}</span>
                </span>
              </label>
            ))}
          </fieldset>
        ) : (
          <>
            <TextField
              label="Email"
              type="email"
              autoComplete="username"
              error={form.formState.errors.email?.message}
              {...form.register('email')}
            />
            <TextField
              label="Password"
              type="password"
              autoComplete="current-password"
              error={form.formState.errors.password?.message}
              {...form.register('password')}
            />
          </>
        )}

        <Button type="submit" loading={mutation.isPending} className="w-full">
          {colleges ? 'Continue' : 'Sign in'}
        </Button>
        {colleges && (
          <Button
            type="button"
            variant="secondary"
            className="w-full"
            onClick={() => {
              setColleges(null)
              mutation.reset()
            }}
          >
            Use a different account
          </Button>
        )}
      </form>
    </AuthCard>
  )
}
