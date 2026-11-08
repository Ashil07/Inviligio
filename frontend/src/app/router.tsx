import { createBrowserRouter, type RouteObject } from 'react-router'
import { NotFoundPage } from '@/components/NotFoundPage'
import { HomeRedirect, PublicOnly, RequireAuth, RequireRole } from '@/features/auth/guards'
import { LoginPage } from '@/features/auth/LoginPage'
import { RegisterCollegePage } from '@/features/auth/RegisterCollegePage'
import { CollegeSettingsPage } from '@/features/college/CollegeSettingsPage'
import { DepartmentsPage } from '@/features/departments/DepartmentsPage'
import { HallsPage } from '@/features/halls/HallsPage'
import { OnboardingWizard } from '@/features/onboarding/OnboardingWizard'
import { PolicySettingsPage, PolicyVersionPage } from '@/features/policy/PolicySettingsPage'
import { SubjectsPage } from '@/features/subjects/SubjectsPage'
import { SystemStatusPage } from '@/features/system/SystemStatusPage'
import { AdminLayout, AdminPage } from './AdminLayout'
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
          {
            element: <RequireRole role="EXAM_CELL_ADMIN" />,
            children: [
              {
                element: <AdminLayout />,
                children: [
                  { path: '/admin', element: <AdminHomePage /> },
                  { path: '/admin/setup', element: <OnboardingWizard /> },
                  {
                    element: <AdminPage />,
                    children: [
                      { path: '/admin/departments', element: <DepartmentsPage /> },
                      { path: '/admin/subjects', element: <SubjectsPage /> },
                      { path: '/admin/halls', element: <HallsPage /> },
                      { path: '/admin/settings/college', element: <CollegeSettingsPage /> },
                      { path: '/admin/settings/policy', element: <PolicySettingsPage /> },
                      { path: '/admin/settings/policy/versions/:version', element: <PolicyVersionPage /> },
                    ],
                  },
                ],
              },
            ],
          },
          { element: <RequireRole role="HOD" />, children: [{ path: '/hod', element: <HodHomePage /> }] },
          { element: <RequireRole role="FACULTY" />, children: [{ path: '/faculty', element: <FacultyHomePage /> }] },
        ],
      },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
]

export const router = createBrowserRouter(routes)
