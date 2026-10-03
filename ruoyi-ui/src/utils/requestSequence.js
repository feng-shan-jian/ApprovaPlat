import { onBeforeUnmount } from 'vue'

/** One sequence belongs to one independently displayed result set. */
export function createRequestSequence() {
  let sequence = 0
  return {
    begin() {
      const request = ++sequence
      return () => request === sequence
    },
    invalidate() { sequence += 1 }
  }
}

export function useRequestSequence() {
  const requests = createRequestSequence()
  onBeforeUnmount(requests.invalidate)
  return requests
}
