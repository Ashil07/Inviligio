import { createBrowserRouter, type RouteObject } from 'react-router'
import { NotFoundPage } from '@/components/NotFoundPage'
import { HomeRedirect, PublicOnly, RequireAuth, RequireRole } from '@/features/auth/guards'
import { LoginPage } from '@/features/auth/LoginPage'
import { RegisterCollegePage } from '@/features/auth/RegisterCollegePage'
import { SystemStatusPage } from '@/features/system/SystemStatusPage'
import { AppLayout } from './AppLayout'
import { AdminHomePage, FacultyHomePage, HodHomePage } from './RoleHomePages'

/**
 * Route tree. Role areas: /admin (EXAM_CELL_ADMIN), /hod (HOD), /faculty (FACULTY).
 * Guards are UX only; the backend enforces every permission.
 */
export const routes: RouteObject[] = [
  { path: '/status', element: <SystemStatusPage /> },
  {
    element: <PublicOnly />,
    children: [
      { path: '/login', element: <LoginPage /> },
      { path: '/register', element: <RegisterCollegePage /> },
    ],
  },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { path: '/', element: <HomeRedirect /> },
          { element: <RequireRole role="EXAM_CELL_ADMIN" />, children: [{ path: '/admin', element: <AdminHomePage /> }] },
          { element: <RequireRole role="HOD" />, children: [{ path: '/hod', element: <HodHomePage /> }] },
          { element: <RequireRole role="FACULTY" />, children: [{ path: '/faculty', element: <FacultyHomePage /> }] },
        ],
      },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
]

export const router = createBrowserRouter(routes)
