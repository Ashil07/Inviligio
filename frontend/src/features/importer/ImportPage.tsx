import { useMutation } from '@tanstack/react-query'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Button, ErrorAlert } from '@/components/ui/form'
import { Section, SelectField, SuccessNotice } from '@/components/ui/fields'
import { PageHeader, Table } from '@/components/ui/layout'
import { api, toApiProblem } from '@/lib/api/client'

type ImportType = 'departments' | 'subjects' | 'halls' | 'faculty'

interface ImportReport {
  type: string
  dryRun: boolean
  applied: boolean
  rows: number
  toCreate: number
  toUpdate: number
  created: number
  updated: number
  unchanged: number
  errors: { line: number; column: string | null; message: string }[]
}

const TYPES: { value: ImportType; label: string }[] = [
  { value: 'departments', label: 'Departments' },
  { value: 'subjects', label: 'Subjects' },
  { value: 'halls', label: 'Halls' },
  { value: 'faculty', label: 'Faculty' },
]

async function upload(type: ImportType, file: File, dryRun: boolean): Promise<ImportReport> {
  const form = new FormData()
  form.append('file', file)
  const { data } = await api.post<ImportReport>(`/imports/${type}`, form, {
    params: { dryRun },
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return data
}

async function downloadTemplate(type: ImportType) {
  const { data } = await api.get<Blob>(`/imports/${type}/template`, { responseType: 'blob' })
  const url = URL.createObjectURL(data)
  const a = document.createElement('a')
  a.href = url
  a.download = `invigilo-${type}-template.csv`
  a.click()
  URL.revokeObjectURL(url)
}

/** Check a CSV file first (nothing is saved), then import it. All rows are applied or none. */
export function ImportPage() {
  const queryClient = useQueryClient()
  const [type, setType] = useState<ImportType>('departments')
  const [file, setFile] = useState<File | null>(null)
  const [report, setReport] = useState<ImportReport | null>(null)
  const run = useMutation({ mutationFn: ({ dryRun }: { dryRun: boolean }) => upload(type, file!, dryRun) })
  const [problem, setProblem] = useState<string | null>(null)

  async function go(dryRun: boolean) {
    setProblem(null)
    try {
      const r = await run.mutateAsync({ dryRun })
      setReport(r)
      if (r.applied) await queryClient.invalidateQueries({ queryKey: [type] })
    } catch (e) {
      setProblem(toApiProblem(e).detail ?? 'The file could not be processed.')
    }
  }

  const checkedOk = report && report.dryRun && report.errors.length === 0

  return (
    <div className="space-y-6">
      <PageHeader
        title="Import from CSV"
        description="Import departments first, then subjects and halls, then faculty. Rows are matched by code: existing records are updated, new ones created, nothing is deleted."
      />
      <Section title="1. Choose what to import">
        <div className="grid gap-4 sm:grid-cols-2">
          <SelectField
            label="Records"
            value={type}
            onChange={(e) => {
              setType(e.target.value as ImportType)
              setReport(null)
            }}
            options={TYPES}
          />
          <div className="flex items-end">
            <Button type="button" variant="secondary" onClick={() => void downloadTemplate(type)}>
              Download template
            </Button>
          </div>
        </div>
        <div>
          <label htmlFor="import-file" className="block text-sm font-medium text-slate-700">CSV file (UTF-8, up to 5000 rows)</label>
          <input
            id="import-file"
            type="file"
            accept=".csv,text/csv"
            className="mt-1 block text-sm"
            onChange={(e) => {
              setFile(e.target.files?.[0] ?? null)
              setReport(null)
            }}
          />
        </div>
        <div className="flex gap-2">
          <Button type="button" disabled={!file} loading={run.isPending && run.variables?.dryRun} onClick={() => void go(true)}>
            Check file
          </Button>
          {checkedOk && (
            <Button type="button" loading={run.isPending && !run.variables?.dryRun} onClick={() => void go(false)}>
              Import {report.rows} rows
            </Button>
          )}
        </div>
      </Section>

      {problem && <ErrorAlert>{problem}</ErrorAlert>}

      {report && report.applied && (
        <SuccessNotice>
          Imported {report.rows} rows: {report.created} created, {report.updated} updated, {report.unchanged} unchanged.
        </SuccessNotice>
      )}
      {checkedOk && (
        <SuccessNotice>
          No problems found in {report.rows} rows: {report.toCreate} will be created and {report.toUpdate} existing records updated. Nothing has been saved yet.
        </SuccessNotice>
      )}
      {report && report.errors.length > 0 && (
        <section className="space-y-2">
          <ErrorAlert>
            {report.applied ? '' : 'Nothing was imported. '}Fix these {report.errors.length} problem(s) and check the file again.
          </ErrorAlert>
          <Table head={['Line', 'Column', 'Problem']}>
            {report.errors.map((e, i) => (
              <tr key={i}>
                <td className="px-4 py-2 text-slate-700">{e.line || '-'}</td>
                <td className="px-4 py-2 font-mono text-xs text-slate-700">{e.column ?? '-'}</td>
                <td className="px-4 py-2 text-slate-700">{e.message}</td>
              </tr>
            ))}
          </Table>
        </section>
      )}
    </div>
  )
}
