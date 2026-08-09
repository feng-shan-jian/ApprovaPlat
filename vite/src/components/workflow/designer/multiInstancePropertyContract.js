/**
 * 构造多实例命令允许保留的作者属性，统一移除只适用于单实例任务的参与者规则。
 * @param {Array<{name:string,value:string}>} properties 当前任务待写入的完整属性集合。
 * @param {Set<string>} singleInstancePropertyNames 单实例参与者规则属性名集合。
 * @returns {Array<{name:string,value:string}>} 保持原顺序且不含单实例规则的新数组。
 */
export function multiInstanceAuthorProperties(properties, singleInstancePropertyNames) {
  const source = Array.isArray(properties) ? properties : []
  const excluded = singleInstancePropertyNames instanceof Set
    ? singleInstancePropertyNames
    : new Set()
  return source.filter(property => !excluded.has(String(property?.name || '')))
}
