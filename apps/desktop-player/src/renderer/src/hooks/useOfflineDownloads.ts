import { useEffect, useMemo, useState } from 'react'

export function useOfflineDownloads() {
  const [records, setRecords] = useState<Film2OfflineRecord[]>([])

  useEffect(() => {
    let mounted = true
    void window.film2?.offline.list().then((items) => { if (mounted) setRecords(items) })
    const unsubscribe = window.film2?.offline.onProgress((record) => {
      setRecords((current) => {
        if (record.error === 'removed') return current.filter((item) => item.key !== record.key)
        const exists = current.some((item) => item.key === record.key)
        return exists
          ? current.map((item) => item.key === record.key ? record : item)
          : [record, ...current]
      })
    })
    return () => {
      mounted = false
      unsubscribe?.()
    }
  }, [])

  const byKey = useMemo(() => new Map(records.map((record) => [record.key, record])), [records])
  return { records, byKey }
}
