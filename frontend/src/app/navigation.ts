import {
  ArrowLeftRight,
  BookOpen,
  Building,
  CalendarDays,
  CalendarOff,
  ClipboardList,
  DoorOpen,
  FileText,
  IndianRupee,
  Inbox,
  LayoutGrid,
  Scale,
  Settings,
  Upload,
  Users,
  type LucideIcon,
} from 'lucide-react'
import type { Role } from '@/features/auth/api'

export interface NavItem {
  to: string
  label: string
  icon: LucideIcon
  /** Active only on exactly `to`; otherwise also on every path below `prefix` (default `to`). */
  end?: boolean
  prefix?: string
}

export interface NavGroup {
  label?: string
  items: NavItem[]
}

export const AREA_ROLE = { admin: 'EXAM_CELL_ADMIN', hod: 'HOD', faculty: 'FACULTY' } as const satisfies Record<string, Role>
export type Area = keyof typeof AREA_ROLE

export function areaOf(pathname: string): Area | null {
  const first = pathname.split('/')[1]
  return first === 'admin' || first === 'hod' || first === 'faculty' ? first : null
}

export function isActive(item: NavItem, pathname: string) {
  if (item.end) return pathname === item.to
  const prefix = item.prefix ?? item.to
  return pathname === prefix || pathname.startsWith(prefix + '/')
}

const ADMIN_ONBOARDING: NavGroup[] = [
  {
    items: [
      { to: '/admin', label: 'Overview', icon: LayoutGrid, end: true },
      { to: '/admin/setup', label: 'College setup', icon: Settings },
    ],
  },
]

/** Exam-cell navigation, grouped by the job it serves. College and policy settings share one entry. */
const ADMIN: NavGroup[] = [
  { items: [{ to: '/admin', label: 'Overview', icon: LayoutGrid, end: true }] },
  {
    label: 'Examinations',
    items: [
      { to: '/admin/exams', label: 'Exam periods', icon: CalendarDays },
      { to: '/admin/availability', label: 'Availability', icon: CalendarOff },
      { to: '/admin/swaps', label: 'Swap requests', icon: ArrowLeftRight },
    ],
  },
  {
    label: 'Insights',
    items: [
      { to: '/admin/analytics', label: 'Fairness', icon: Scale },
      { to: '/admin/reports', label: 'Reports', icon: FileText },
      { to: '/admin/remuneration', label: 'Remuneration', icon: IndianRupee },
    ],
  },
  {
    label: 'Directory',
    items: [
      { to: '/admin/faculty', label: 'Faculty', icon: Users },
      { to: '/admin/departments', label: 'Departments', icon: Building },
      { to: '/admin/subjects', label: 'Subjects', icon: BookOpen },
      { to: '/admin/halls', label: 'Halls', icon: DoorOpen },
      { to: '/admin/import', label: 'Bulk import', icon: Upload },
    ],
  },
  { label: 'College', items: [{ to: '/admin/settings/college', label: 'Settings', icon: Settings, prefix: '/admin/settings' }] },
]

const HOD: NavGroup[] = [
  {
    items: [
      { to: '/hod', label: 'Overview', icon: LayoutGrid, end: true },
      { to: '/hod/requests', label: 'Leave requests', icon: Inbox },
      { to: '/hod/swaps', label: 'Swap requests', icon: ArrowLeftRight },
    ],
  },
]

const FACULTY: NavGroup[] = [
  {
    items: [
      { to: '/faculty', label: 'My duties', icon: ClipboardList, end: true },
      { to: '/faculty/availability', label: 'My availability', icon: CalendarOff },
      { to: '/faculty/swaps', label: 'Swaps', icon: ArrowLeftRight },
    ],
  },
]

export function navFor(area: Area, onboarding: boolean): NavGroup[] {
  if (area === 'admin') return onboarding ? ADMIN_ONBOARDING : ADMIN
  return area === 'hod' ? HOD : FACULTY
}

export const AREA_TITLE: Record<Area, string> = { admin: 'Examination cell', hod: 'Department', faculty: 'My duties' }
