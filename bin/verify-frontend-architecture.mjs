import { readFileSync, readdirSync } from 'node:fs'
import { resolve, extname } from 'node:path'
import { createRequire } from 'node:module'

// Reuse Vue's installed parser; inspect declarations/imports and forbidden test
// compilation entry points, not implementation text or component method length.
const root = resolve(process.argv[2])
const require = createRequire(resolve(process.argv[3] || root, 'ruoyi-ui/package.json'))
const { parse, babelParse } = require('@vue/compiler-sfc')
const contractPath = 'ruoyi-ui/src/components/workflow/designer/designerIdentityContract.js'
const owners = [
  'ruoyi-ui/src/components/workflow/designer/formParticipantDomain.js',
  'ruoyi-ui/src/views/workflow/model/design.vue'
]
const pools = new Set(['assignees', 'candidateUsers', 'candidateGroups', 'candidateRoles',
  'activeUsers', 'activeRoles', 'activeDepts', 'autoCopyUsers', 'autoCopyGroups'])
const errors = []
const declarations = []
function files(directory) {
  return readdirSync(resolve(root, directory), { withFileTypes: true }).flatMap(entry => {
    const path = `${directory}/${entry.name}`
    return entry.isDirectory() ? files(path) : ['.js', '.vue'].includes(extname(path)) ? [path] : []
  })
}
function ast(path) {
  const source = readFileSync(resolve(root, path), 'utf8')
  const scripts = path.endsWith('.vue') ? (() => {
    const { descriptor, errors: parsingErrors } = parse(source, { filename: path })
    if (parsingErrors.length) throw new Error(`${path}: ${parsingErrors.join('; ')}`)
    return [descriptor.script?.content, descriptor.scriptSetup?.content].filter(Boolean)
  })() : [source]
  return scripts.map(script => babelParse(script, { sourceType: 'module' }))
}
function walk(node, visit) {
  if (!node || typeof node !== 'object') return
  if (typeof node.type === 'string') visit(node)
  for (const [key, value] of Object.entries(node)) {
    if (['loc', 'start', 'end', 'comments', 'tokens'].includes(key)) continue
    if (Array.isArray(value)) value.forEach(child => walk(child, visit))
    else if (value && typeof value === 'object') walk(value, visit)
  }
}
const key = node => node?.name ?? node?.value
const unwrap = node => node?.type === 'CallExpression' && node.callee?.object?.name === 'Object'
  && node.callee?.property?.name === 'freeze' ? node.arguments[0] : node
for (const path of [...files('ruoyi-ui/src/components/workflow/designer'), ...files('ruoyi-ui/src/views/workflow/model')]) {
  const trees = ast(path)
  let importsContract = false
  for (const tree of trees) walk(tree, node => {
    if (node.type === 'ImportDeclaration' && /(?:^|\/)designerIdentityContract\.js$/.test(node.source.value)) importsContract = true
    if (node.type === 'FunctionDeclaration' && ['designerIdentityContract', 'identityRequestTarget'].includes(node.id?.name)
        && path !== contractPath) errors.push(`${path}: duplicate contract function ${node.id.name}`)
    if (node.type !== 'ObjectExpression') return
    const mappedPools = node.properties.filter(property => pools.has(key(property.key)) && (() => {
      const value = unwrap(property.value)
      return value?.type === 'ObjectExpression' && ['type', 'capability'].every(field => value.properties.some(p => key(p.key) === field))
    })())
    if (mappedPools.length) declarations.push({ path, pools: mappedPools.map(p => key(p.key)) })
  })
  if (owners.includes(path) && !importsContract) errors.push(`${path}: missing dependency on ${contractPath}`)
}
if (declarations.length !== 1 || declarations[0]?.path !== contractPath || declarations[0]?.pools.length !== pools.size) {
  errors.push(`identity contract must have one nine-pool declaration at ${contractPath}; found ${JSON.stringify(declarations)}`)
}
const migratedTests = [...files('ruoyi-ui/tests/contracts'), ...files('ruoyi-ui/tests/e2e/fixtures'),
  ...['component-credentials', 'component-initialization', 'component-request-ownership', 'designer-edit-session', 'designer-identity-wiring', 'upload-settlement']
    .map(name => `ruoyi-ui/tests/e2e/${name}.spec.js`)]
for (const path of migratedTests) for (const tree of ast(path)) walk(tree, node => {
  const call = node.type === 'CallExpression' || node.type === 'NewExpression'
  const name = node.callee?.name ?? key(node.callee?.property)
  if (call && ['Function', 'eval', 'runInContext', 'runInNewContext', 'runInThisContext', 'compileFunction', 'readFile', 'readFileSync'].includes(name)) {
    errors.push(`${path}:${node.loc.start.line}: migrated test may not reconstruct private source via ${name}`)
  }
})
if (errors.length) throw new Error(errors.join('\n'))
console.log(`Frontend architecture passed: one identity declaration, two importing owners, ${migratedTests.length} test/fixture files without source reconstruction.`)
