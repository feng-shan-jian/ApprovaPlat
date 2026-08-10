import { expect } from '@playwright/test'

/**
 * 判断浏览器网络资源是否命中指定真实后端入口。
 * @param {{url: () => string, request: () => {method: () => string}}} resource Playwright Response 对象。
 * @param {string} endpoint 不含 `/dev-api` 前缀的后端路径。
 * @param {string} method 期望 HTTP 方法。
 * @returns {boolean} true 表示路径和方法同时匹配。
 */
export function matchesEndpoint(resource, endpoint, method = 'GET') {
  const pathname = new URL(resource.url()).pathname
  return pathname.endsWith(endpoint) && resource.request().method() === method
}

/**
 * 核对真实 AjaxResult 分页或对象响应，禁止仅凭 HTTP 200 判定业务成功。
 * @param {import('@playwright/test').Response} response Playwright 捕获的真实响应。
 * @param {string} endpoint 用于错误定位的后端入口。
 * @returns {Promise<object>} 已确认业务码为 200 的 JSON 正文。
 */
export async function expectAjaxSuccess(response, endpoint) {
  expect(response.status(), `${endpoint} HTTP 状态`).toBe(200)
  const payload = await response.json()
  expect(payload?.code, `${endpoint} AjaxResult.code`).toBe(200)
  return payload
}

/**
 * 在触发页面操作前注册响应监听，确保断言的是该次真实 API 调用而非缓存状态。
 * @param {import('@playwright/test').Page} page 当前浏览器页面。
 * @param {string} endpoint 后端入口路径。
 * @param {() => Promise<unknown>} trigger 触发请求的浏览器操作。
 * @param {string} method 期望 HTTP 方法。
 * @returns {Promise<import('@playwright/test').Response>} 与操作对应的真实响应。
 */
export async function captureResponse(page, endpoint, trigger, method = 'GET') {
  const responsePromise = page.waitForResponse(response => matchesEndpoint(response, endpoint, method))
  await trigger()
  return responsePromise
}
