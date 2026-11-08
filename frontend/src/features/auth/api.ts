import { api } from '@/lib/api/client'

export type Role = 'EXAM_CELL_ADMIN' | 'HOD' | 'FACULTY'
export type CollegeStatus = 'ONBOARDING' | 'ACTIVE' | 'SUSPENDED'

export interface SessionInfo {
  user: { id: string; email: string; fullName: string }
  college: { id: string; code: string; name: string; status: CollegeStatus }
  membershipId: string
  roles: Role[]
}

export interface AuthResponse {
  accessToken: string
  tokenType: 'Bearer'
  expiresAt: string
  session: SessionInfo
}

export interface CollegeOption {
  id: string
  code: string
  name: string
}

export type LoginResponse =
  | { status: 'AUTHENTICATED'; auth: AuthResponse }
  | { status: 'COLLEGE_SELECTION_REQUIRED'; colleges: CollegeOption[] }

export interface LoginRequest {
  email: string
  password: string
  collegeId?: string
}

export interface RegisterCollegeRequest {
  collegeName: string
  collegeCode: string
  adminFullName: string
  adminEmail: string
  password: string
}

export async function login(request: LoginRequest): Promise<LoginResponse> {
  const { data } = await api.post<LoginResponse>('/auth/login', request)
  return data
}

export async function registerCollege(request: RegisterCollegeRequest): Promise<AuthResponse> {
  const { data } = await api.post<AuthResponse>('/auth/register-college', request)
  return data
}

export async function refreshSession(): Promise<AuthResponse> {
  const { data } = await api.post<AuthResponse>('/auth/refresh')
  return data
}

export async function logout(): Promise<void> {
  await api.post('/auth/logout')
}

/** Area of the app a role lands in, in priority order. */
export const ROLE_HOME: Record<Role, string> = {
  EXAM_CELL_ADMIN: '/admin',
  HOD: '/hod',
  FACULTY: '/faculty',
}

export const ROLE_LABEL: Record<Role, string> = {
  EXAM_CELL_ADMIN: 'Exam cell',
  HOD: 'Head of department',
  FACULTY: 'Faculty',
}

export const ROLE_PRIORITY: Role[] = ['EXAM_CELL_ADMIN', 'HOD', 'FACULTY']
