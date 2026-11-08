/** Backend `PageResponse`. */
export interface Page<T> {
  items: T[]
  page: number
  size: number
  total: number
}
