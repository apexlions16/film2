import { useEffect, useState } from 'react'
import { readLibrary, subscribeLibrary } from '../lib/userLibrary'

export function useUserLibrary() {
  const [state, setState] = useState(readLibrary)
  useEffect(() => subscribeLibrary(() => setState(readLibrary())), [])
  return state
}
