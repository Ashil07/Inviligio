import { Table } from '@/components/ui/layout'
import { STATUS_STYLE, TYPE_LABEL, describeWhen, type AvailabilityRequest } from './api'

/** Requests with the actions the viewer may take (review, cancel). */
export function RequestsTable({
  requests,
  showFaculty,
  onReview,
  onCancel,
  canCancel,
}: {
  requests: AvailabilityRequest[]
  showFaculty: boolean
  onReview?: (r: AvailabilityRequest, approve: boolean) => void
  onCancel?: (r: AvailabilityRequest) => void
  canCancel?: (r: AvailabilityRequest) => boolean
}) {
  const head = [...(showFaculty ? ['Faculty'] : []), 'When', 'Type', 'Reason', 'Status', '']
  return (
    <Table head={head} empty={requests.length === 0 && 'No requests.'}>
      {requests.map((r) => (
        <tr key={r.id}>
          {showFaculty && (
            <td className="px-4 py-2">
              <span className="font-medium text-slate-900">{r.faculty.name}</span>{' '}
              <span className="text-slate-500">{r.faculty.code} · {r.departmentCode}</span>
            </td>
          )}
          <td className="px-4 py-2 text-slate-700">{describeWhen(r)}</td>
          <td className="px-4 py-2 text-slate-600">{TYPE_LABEL[r.type]}</td>
          <td className="px-4 py-2 text-slate-600">{r.reason ?? '-'}</td>
          <td className="px-4 py-2">
            <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_STYLE[r.status]}`}>{r.status.toLowerCase()}</span>
            {r.reviewNote && <p className="mt-1 text-xs text-slate-500">{r.reviewNote}{r.reviewedByName ? ` - ${r.reviewedByName}` : ''}</p>}
          </td>
          <td className="px-4 py-2">
            <div className="flex justify-end gap-3 whitespace-nowrap">
              {onReview && r.status === 'PENDING' && (
                <>
                  <button type="button" className="text-emerald-700 hover:underline" onClick={() => onReview(r, true)} aria-label={`Approve ${r.faculty.name} ${describeWhen(r)}`}>
                    Approve
                  </button>
                  <button type="button" className="text-red-700 hover:underline" onClick={() => onReview(r, false)} aria-label={`Reject ${r.faculty.name} ${describeWhen(r)}`}>
                    Reject
                  </button>
                </>
              )}
              {onCancel && (canCancel?.(r) ?? true) && (r.status === 'PENDING' || r.status === 'APPROVED') && (
                <button type="button" className="text-slate-700 hover:underline" onClick={() => onCancel(r)} aria-label={`Cancel ${describeWhen(r)}`}>
                  Cancel
                </button>
              )}
            </div>
          </td>
        </tr>
      ))}
    </Table>
  )
}
