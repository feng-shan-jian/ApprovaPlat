// Search and selected-value resolution share qualification, but have different
// supported pools. Auto-copy currently has search consumers only.
const contracts = Object.freeze({
  assignees: Object.freeze({ type: 'user', capability: 'approval', resolve: true }),
  candidateUsers: Object.freeze({ type: 'user', capability: 'claim', resolve: true }),
  candidateGroups: Object.freeze({ type: 'group', capability: 'claim', resolve: true }),
  candidateRoles: Object.freeze({ type: 'role', capability: 'claim', resolve: true }),
  activeUsers: Object.freeze({ type: 'user', capability: '', resolve: true }),
  activeRoles: Object.freeze({ type: 'role', capability: '', resolve: true }),
  activeDepts: Object.freeze({ type: 'dept', capability: '', resolve: true }),
  autoCopyUsers: Object.freeze({ type: 'user', capability: 'copy', resolve: false }),
  autoCopyGroups: Object.freeze({ type: 'group', capability: 'copy', resolve: false })
})

export function designerIdentityContract(target, operation = 'search') {
  const contract = Object.hasOwn(contracts, target) ? contracts[target] : undefined
  if (operation !== 'search' && operation !== 'resolve') return undefined
  return contract && (operation === 'search' || contract.resolve) ? contract : undefined
}

export function identityRequestTarget(request, operation = 'search') {
  const target = String(request?.target || '')
  const contract = designerIdentityContract(target, operation)
  return contract && contract.type === request?.type && contract.capability === (request?.capability || '') ? target : ''
}
