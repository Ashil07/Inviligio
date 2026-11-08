import { z } from 'zod'

/** Same rule as backend `Codes.PATTERN`; codes are stored upper-case. */
export const codeSchema = z
  .string()
  .trim()
  .min(1, 'Code is required')
  .regex(/^[A-Za-z0-9][A-Za-z0-9_.-]{0,31}$/, 'Up to 32 letters, digits, ".", "-" or "_"')
