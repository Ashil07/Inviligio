import { createBrowserRouter } from 'react-router'
import { NotFoundPage } from '@/components/NotFoundPage'
import { SystemStatusPage } from '@/features/system/SystemStatusPage'

/**
 * Route tree. As features land, add role-guarded layouts here, e.g.
 *   /admin/*   EXAM_CELL_ADMIN
 *   /hod/*     HOD
 *   /faculty/* FACULTY
 * Route guards are UX only; the backend enforces every permission.
 */
export const router = createBrowserRouter([
  { path: '/', element: <SystemStatusPage /> },
  { path: '*', element: <NotFoundPage /> },
])
