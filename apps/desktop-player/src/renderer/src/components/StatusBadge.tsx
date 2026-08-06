import type { JSX } from 'react'

import type { AssetStatus } from '../lib/types'
import styles from './StatusBadge.module.css'

const LABELS: Record<Exclude<AssetStatus, 'ready'>, string> = {
  pending: 'Hazırlanıyor',
  processing: 'Hazırlanıyor',
  error: 'Sorun oluştu'
}

export function StatusBadge({ status }: { status: AssetStatus }): JSX.Element | null {
  if (status === 'ready') return null
  const isError = status === 'error'
  return (
    <span className={`${styles.badge} ${isError ? styles.error : ''}`}>{LABELS[status]}</span>
  )
}
