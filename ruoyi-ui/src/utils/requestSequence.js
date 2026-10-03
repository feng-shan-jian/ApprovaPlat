import { onBeforeUnmount } from 'vue'

/** One sequence belongs to one independently displayed result set. */
export function createRequestSequence() {
  let sequence = 0
  let disposed = false
  return {
    begin() {
      const request = ++sequence
      return () => !disposed && request === sequence
    },
    invalidate() { sequence += 1 },
    // Unmount is terminal, including work resumed by a pending write afterward.
    dispose() { disposed = true }
  }
}

export function useRequestSequence() {
  const requests = createRequestSequence()
  onBeforeUnmount(requests.dispose)
  return requests
}
