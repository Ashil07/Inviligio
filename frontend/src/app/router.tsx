import { createBrowserRouter, type RouteObject } from 'react-router'
import { NotFoundPage } from '@/components/NotFoundPage'
import { HomeRedirect, PublicOnly, RequireAuth, RequireRole } from '@/features/auth/guards'
import { AllocationPage, ChartVersionPage } from '@/features/allocation/AllocationPage'
import { AcceptInvitePage } from '@/features/auth/AcceptInvitePage'
import { AdminAvailabilityPage, DepartmentRequestsPage, MyAvailabilityPage } from '@/features/availability/AvailabilityPages'
import { LoginPage } from '@/features/auth/LoginPage'
import { RegisterCollegePage } from '@/features/auth/RegisterCollegePage'
import { CollegeSettingsPage } from '@/features/college/CollegeSettingsPage'
import { DepartmentsPage } from '@/features/departments/DepartmentsPage'
import { FacultyDetailPage } from '@/features/faculty/FacultyDetailPage'
import { FacultyListPage } from '@/features/faculty/FacultyListPage'
import { HallsPage } from '@/features/halls/HallsPage'
import { ImportPage } from '@/features/importer/ImportPage'
import { OnboardingWizard } from '@/features/onboarding/OnboardingWizard'
import { PolicySettingsPage, PolicyVersionPage } from '@/features/policy/PolicySettingsPage'
import { PeriodDetailPage } from '@/features/schedule/PeriodDetailPage'
import { PeriodsPage } from '@/features/schedule/PeriodsPage'
import { SessionPage } from '@/features/schedule/SessionPage'
import { SubjectsPage } from '@/features/subjects/SubjectsPage'
import { SystemStatusPage } from '@/features/system/SystemStatusPage'
import { AdminLayout, AdminPage } from './AdminLayout'
import { AppLayout } from './AppLayout'
import { AreaLayout } from './AreaLayout'
import { AdminSwapsPage, FacultySwapsPage, HodSwapsPage } from '@/features/swaps/SwapPages'
import { AdminHomePage, FacultyHomePage, HodHomePage } from './RoleHomePages'

/**
 * Route tree. Role areas: /admin (EXAM_CELL_ADMIN), /hod (HOD), /faculty (FACULTY).
 * Guards are UX only; the backend enforces every permission.
 */
export const routes: RouteObject[] = [
  { path: '/status', element: <SystemStatusPage /> },
  { path: '/accept-invite', element: <AcceptInvitePage /> },
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
                      { path: '/admin/exams', element: <PeriodsPage /> },
                      { path: '/admin/exams/:periodId', element: <PeriodDetailPage /> },
                      { path: '/admin/exams/:periodId/allocation', element: <AllocationPage /> },
                      { path: '/admin/exams/:periodId/allocation/versions/:chartVersion', element: <ChartVersionPage /> },
                      { path: '/admin/exams/sessions/:sessionId', element: <SessionPage /> },
                      { path: '/admin/faculty', element: <FacultyListPage /> },
                      { path: '/admin/faculty/:id', element: <FacultyDetailPage /> },
                      { path: '/admin/import', element: <ImportPage /> },
                      { path: '/admin/availability', element: <AdminAvailabilityPage /> },
                      { path: '/admin/swaps', element: <AdminSwapsPage /> },
                      // Loaded on demand: the charts library is large and only this page needs it.
                      { path: '/admin/analytics', lazy: async () => ({ Component: (await import('@/features/analytics/FairnessPage')).FairnessPage }) },
                      { path: '/admin/settings/college', element: <CollegeSettingsPage /> },
                      { path: '/admin/settings/policy', element: <PolicySettingsPage /> },
                      { path: '/admin/settings/policy/versions/:version', element: <PolicyVersionPage /> },
                    ],
                  },
                ],
              },
            ],
          },
          {
            element: <RequireRole role="HOD" />,
            children: [
              {
                element: <AreaLayout label="Department" links={[{ to: '/hod', label: 'Overview', end: true }, { to: '/hod/requests', label: 'Leave requests' }, { to: '/hod/swaps', label: 'Swap requests' }]} />,
                children: [
                  { path: '/hod', element: <HodHomePage /> },
                  { path: '/hod/requests', element: <DepartmentRequestsPage /> },
                  { path: '/hod/swaps', element: <HodSwapsPage /> },
                ],
              },
            ],
          },
          {
            element: <RequireRole role="FACULTY" />,
            children: [
              {
                element: <AreaLayout label="Faculty" links={[{ to: '/faculty', label: 'My duties', end: true }, { to: '/faculty/availability', label: 'My availability' }, { to: '/faculty/swaps', label: 'Swaps' }]} />,
                children: [
                  { path: '/faculty', element: <FacultyHomePage /> },
                  { path: '/faculty/availability', element: <MyAvailabilityPage /> },
                  { path: '/faculty/swaps', element: <FacultySwapsPage /> },
                ],
              },
            ],
          },
        ],
      },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
]

export const router = createBrowserRouter(routes)
