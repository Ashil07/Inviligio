import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useParams } from 'react-router'
import { Button, ErrorAlert } from '@/components/ui/form'
import { SuccessNotice } from '@/components/ui/fields'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { toApiProblem } from '@/lib/api/client'
import { applyFieldErrors, formatDateTime } from '@/lib/forms'
import { usePolicy, usePolicyVersion, usePolicyVersions, useSavePolicy, type PolicyView } from './api'
import { AllPolicySections } from './PolicySections'
import { policySettingsSchema, type PolicySettings } from './schema'

export function PolicySettingsPage() {
  const policy = usePolicy()
  // Lives here, not in the editor: the editor remounts when the saved version arrives.
  const [savedVersion, setSavedVersion] = useState<number | null>(null)
  if (policy.error) return <ErrorAlert>{toApiProblem(policy.error).detail ?? 'Could not load the policy.'}</ErrorAlert>
  if (!policy.data) return <FullPageSpinner label="Loading policy" />
  // Remount the editor when a new version arrives so the form starts from it.
  return (
    <div className="space-y-8">
      <PolicyEditor
        key={policy.data.version}
        policy={policy.data}
        savedVersion={savedVersion}
        onSaved={setSavedVersion}
        onReload={() => void policy.refetch()}
      />
      <VersionHistory />
    </div>
  )
}

function PolicyEditor({ policy, savedVersion, onSaved, onReload }: {
  policy: PolicyView
  savedVersion: number | null
  onSaved: (version: number | null) => void
  onReload: () => void
}) {
  const form = useForm<PolicySettings>({ resolver: zodResolver(policySettingsSchema), defaultValues: policy.settings })
  const save = useSavePolicy()
  const [problem, setProblem] = useState<{ detail: string; stale: boolean } | null>(null)

  const onSubmit = form.handleSubmit(async (settings) => {
    setProblem(null)
    onSaved(null)
    try {
      const saved = await save.mutateAsync({ basedOnVersion: policy.version, settings })
      onSaved(saved.version)
    } catch (e) {
      const p = toApiProblem(e)
      if (!applyFieldErrors(p, form.setError, 'settings.')) {
        setProblem({ detail: p.detail ?? 'Could not save the policy.', stale: p.code === 'POLICY_CHANGED' })
      }
    }
  })

  return (
    <form onSubmit={(e) => void onSubmit(e)} noValidate className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900">Examination policy</h1>
          <p className="mt-1 text-sm text-slate-600">
            {policy.saved
              ? `Version ${policy.version}, saved by ${policy.createdByName ?? 'unknown'} on ${formatDateTime(policy.createdAt)}.`
              : 'No policy saved yet. These are the suggested defaults.'}{' '}
            Saving creates a new version; charts already generated keep the version they were built with.
          </p>
        </div>
        <Button type="submit" loading={save.isPending} disabled={!form.formState.isDirty && policy.saved}>
          Save as new version
        </Button>
      </div>
      {savedVersion !== null && <SuccessNotice>Saved as version {savedVersion}.</SuccessNotice>}
      {problem && (
        <ErrorAlert>
          {problem.detail}{' '}
          {problem.stale && (
            <button type="button" onClick={onReload} className="font-medium underline">
              Load the latest version
            </button>
          )}
        </ErrorAlert>
      )}
      <AllPolicySections form={form} />
    </form>
  )
}

function VersionHistory() {
  const versions = usePolicyVersions()
  if (!versions.data || versions.data.length === 0) return null
  return (
    <section>
      <h2 className="text-lg font-semibold text-slate-900">Version history</h2>
      <div className="mt-3 overflow-x-auto rounded-lg border border-slate-200 bg-white">
        <table className="min-w-full divide-y divide-slate-200 text-sm">
          <thead className="bg-slate-50 text-left text-slate-600">
            <tr>
              <th scope="col" className="px-4 py-2 font-medium">Version</th>
              <th scope="col" className="px-4 py-2 font-medium">Saved</th>
              <th scope="col" className="px-4 py-2 font-medium">By</th>
              <th scope="col" className="px-4 py-2"><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {versions.data.map((v) => (
              <tr key={v.version}>
                <td className="px-4 py-2 font-medium text-slate-900">{v.version}</td>
                <td className="px-4 py-2 text-slate-600">{formatDateTime(v.createdAt)}</td>
                <td className="px-4 py-2 text-slate-600">{v.createdByName ?? '-'}</td>
                <td className="px-4 py-2 text-right">
                  <Link to={`/admin/settings/policy/versions/${v.version}`} className="text-indigo-700 hover:underline">
                    View
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}

/** Read-only view of one past version. */
export function PolicyVersionPage() {
  const version = Number(useParams().version)
  const query = usePolicyVersion(version)
  if (query.error) return <ErrorAlert>{toApiProblem(query.error).detail ?? 'Could not load this version.'}</ErrorAlert>
  if (!query.data) return <FullPageSpinner label="Loading policy version" />
  return <ReadOnlyPolicy policy={query.data} />
}

function ReadOnlyPolicy({ policy }: { policy: PolicyView }) {
  const form = useForm<PolicySettings>({ defaultValues: policy.settings })
  return (
    <div className="space-y-6">
      <div>
        <Link to="/admin/settings/policy" className="text-sm text-indigo-700 hover:underline">
          ← Back to current policy
        </Link>
        <h1 className="mt-2 text-2xl font-semibold text-slate-900">Policy version {policy.version}</h1>
        <p className="mt-1 text-sm text-slate-600">
          Saved by {policy.createdByName ?? 'unknown'} on {formatDateTime(policy.createdAt)}. Past versions cannot be edited.
        </p>
      </div>
      <AllPolicySections form={form} disabled />
    </div>
  )
}
